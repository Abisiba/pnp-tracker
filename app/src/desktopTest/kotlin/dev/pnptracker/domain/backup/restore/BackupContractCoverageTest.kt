package dev.pnptracker.domain.backup.restore

import dev.pnptracker.data.database.CURRENT_SCHEMA_VERSION
import dev.pnptracker.data.database.RESTORE_ORDER
import dev.pnptracker.domain.backup.backupManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class SchemaColumn(
    val name: String,
    val notNull: Boolean,
)

private data class SchemaTable(
    val columns: List<SchemaColumn>,
    val primaryKey: List<String>,
    val uniqueIndexes: List<List<String>>,
    val foreignKeys: List<SchemaForeignKey>,
)

private data class SchemaForeignKey(
    val columns: List<String>,
    val parent: String,
)

/**
 * Holds the reader's idea of the database against the database's own.
 *
 * The reader carries three lists written out by hand: which tables it writes and
 * in what order, which references have to point at something, and which keys no
 * two rows may share. Each of them is a decision and none of them can be derived
 * from the code they check — derived from the schema they would agree with the
 * schema whatever it said, and the case worth catching is precisely the one where
 * the schema moves and nobody tells the reader.
 *
 * That case is not hypothetical and it is not loud. Add a foreign key in schema
 * version nine, forget the validator, and every existing test still passes: a
 * backup with a dangling reference gets all the way to somebody's restore and the
 * first thing that notices is a transaction failing halfway through it. So the
 * committed `8.json` is read here as JSON and the three lists are held against
 * it, in the format's own words, using the manifest that already pairs each
 * column with the field that carries it.
 */
class BackupContractCoverageTest {
    private val schema: Map<String, SchemaTable> = readCommittedSchema()
    private val arrayOf: Map<String, String> = backupManifest.associate { it.table to it.arrayName }
    private val fieldOf: Map<Pair<String, String>, String> =
        backupManifest.flatMap { table -> table.columns.map { (table.table to it.column) to it.field } }.toMap()

    @Test
    fun `the schema this build reads backups from is the schema this build has`() {
        assertEquals(CURRENT_SCHEMA_VERSION.toInt(), SUPPORTED_SOURCE_SCHEMA_VERSION)
        assertEquals(committedVersion(), SUPPORTED_SOURCE_SCHEMA_VERSION)
    }

    @Test
    fun `the schema really was read, and has the fifteen tables the backup carries`() {
        assertEquals(15, schema.size, "expected fifteen tables, found ${schema.keys.sorted()}")
        assertTrue(schema.values.all { it.columns.isNotEmpty() }, "a table came back with no columns")
    }

    @Test
    fun `the tables are written in the order the restore needs them, and none is left out`() {
        assertEquals(
            backupManifest.map { it.table },
            RESTORE_ORDER.map { it.first },
            "the writing order and PLAN 14.4.2's order are not the same",
        )
    }

    @Test
    fun `every column of every table is written`() {
        RESTORE_ORDER.forEach { (table, columns) ->
            val written = columns.split(", ")
            assertEquals(written.size, written.toSet().size, "$table names a column twice: $written")
            assertEquals(
                schema
                    .getValue(table)
                    .columns
                    .map { it.name }
                    .sorted(),
                written.sorted(),
                "the columns written into $table and the columns it has are not the same set",
            )
        }
    }

    @Test
    fun `every foreign key of the schema is a reference the reader checks`() {
        val declared =
            schema.flatMap { (table, definition) ->
                definition.foreignKeys.map { key ->
                    val column = key.columns.single()
                    BackupReference(
                        from = arrayOf.getValue(table),
                        field = fieldOf.getValue(table to column),
                        to = arrayOf.getValue(key.parent),
                        optional =
                            !schema
                                .getValue(table)
                                .columns
                                .single { it.name == column }
                                .notNull,
                    )
                }
            }
        assertEquals(declared.toSet(), backupReferences.toSet())
        assertEquals(declared.size, backupReferences.size, "a reference is listed twice")
    }

    @Test
    fun `every key no two rows may share is a key the reader checks`() {
        val declared =
            schema.flatMap { (table, definition) ->
                val keys = listOf(definition.primaryKey) + definition.uniqueIndexes
                keys.map { columns ->
                    BackupUniqueKey(
                        array = arrayOf.getValue(table),
                        fields = columns.map { fieldOf.getValue(table to it) },
                        nullsAreExempt = columns.any { column -> !definition.columns.single { it.name == column }.notNull },
                    )
                }
            }
        assertEquals(declared.toSet(), backupUniqueKeys.toSet())
        assertEquals(declared.size, backupUniqueKeys.size, "a key is listed twice")
    }

    @Test
    fun `a reference may only point at a table the file carries whole`() {
        val arrays = backupManifest.map { it.arrayName }.toSet()
        backupReferences.forEach { reference ->
            assertTrue(reference.from in arrays, "${reference.from} is not one of the arrays")
            assertTrue(reference.to in arrays, "${reference.to} is not one of the arrays")
        }
    }

    private fun committedVersion(): Int =
        Json
            .parseToJsonElement(Files.readString(schemaFile()))
            .jsonObject
            .getValue("database")
            .jsonObject
            .getValue("version")
            .jsonPrimitive
            .int

    private fun readCommittedSchema(): Map<String, SchemaTable> {
        val root =
            Json
                .parseToJsonElement(Files.readString(schemaFile()))
                .jsonObject
                .getValue("database")
                .jsonObject
        return root.getValue("entities").jsonArray.associate { entity ->
            val declaration = entity.jsonObject
            val table = declaration.getValue("tableName").jsonPrimitive.content
            val columns =
                declaration.getValue("fields").jsonArray.map { field ->
                    val column = field.jsonObject
                    SchemaColumn(
                        name = column.getValue("columnName").jsonPrimitive.content,
                        notNull = column["notNull"]?.jsonPrimitive?.boolean ?: false,
                    )
                }
            val indexes =
                (declaration["indices"]?.jsonArray ?: emptyList())
                    .map { it.jsonObject }
                    .filter { it.getValue("unique").jsonPrimitive.boolean }
                    .map { index -> index.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content } }
            val references =
                (declaration["foreignKeys"]?.jsonArray ?: emptyList())
                    .map { it.jsonObject }
                    .map { key ->
                        SchemaForeignKey(
                            columns = key.getValue("columns").jsonArray.map { it.jsonPrimitive.content },
                            parent = key.getValue("table").jsonPrimitive.content,
                        )
                    }
            table to
                SchemaTable(
                    columns = columns,
                    primaryKey =
                        declaration.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map {
                            it.jsonPrimitive.content
                        },
                    uniqueIndexes = indexes,
                    foreignKeys = references,
                )
        }
    }

    private fun schemaFile(): Path {
        val candidates =
            listOf(
                Path.of("schemas", SCHEMA_PACKAGE, "8.json"),
                Path.of("app", "schemas", SCHEMA_PACKAGE, "8.json"),
            )
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("cannot find the committed Room schema; looked in $candidates")
    }

    private companion object {
        const val SCHEMA_PACKAGE = "dev.pnptracker.data.database.AppDatabase"
    }
}
