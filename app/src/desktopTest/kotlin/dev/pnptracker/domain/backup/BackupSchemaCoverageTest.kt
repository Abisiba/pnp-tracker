package dev.pnptracker.domain.backup

import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the backup format against the database it claims to carry.
 *
 * The question this answers is the one that will not answer itself: somebody
 * adds a column in a future schema, every test passes, and the column is missing
 * from every backup taken afterwards. Nothing about writing a backup notices
 * that — the writer only writes what it was told about — so the check has to
 * come from outside, from the schema Room itself committed.
 *
 * There are three parties and no two of them are derived from each other. The
 * committed `8.json` says what the database has. [backupManifest] says, in the
 * test's own words, what the format is supposed to carry. The serializers say
 * what the record types actually declare. Every assertion below is one of those
 * three disagreeing with another.
 *
 * The schema is read as JSON rather than scraped with a pattern, so a change in
 * how Room formats the file cannot quietly turn this test into one that checks
 * nothing.
 */
class BackupSchemaCoverageTest {
    private val schema: Map<String, List<SchemaColumn>> = readCommittedSchema()

    @Test
    fun `the backup carries every table the database has and no other`() {
        assertEquals(
            schema.keys.sorted(),
            backupManifest.map { it.table }.sorted(),
            "the tables of schema version 8 and the tables the backup carries are not the same set",
        )
    }

    @Test
    fun `the schema really was read and really has fifteen tables`() {
        // A schema that came back empty would make every set comparison above
        // pass by agreeing about nothing.
        assertEquals(15, schema.size, "expected fifteen application tables, found ${schema.keys.sorted()}")
        assertTrue(schema.values.all { it.isNotEmpty() }, "a table came back with no columns")
    }

    @Test
    fun `no table is carried twice`() {
        val tables = backupManifest.map { it.table }
        assertEquals(tables.size, tables.toSet().size, "a table appears more than once: $tables")
        val arrays = backupManifest.map { it.arrayName }
        assertEquals(arrays.size, arrays.toSet().size, "an array name is used more than once: $arrays")
    }

    @Test
    fun `every column of every table has a field to carry it`() {
        backupManifest.forEach { contract ->
            val columns = schema.getValue(contract.table).map { it.name }
            assertEquals(
                columns.sorted(),
                contract.columns.map { it.column }.sorted(),
                "the columns of ${contract.table} and the columns the backup carries are not the same set",
            )
        }
    }

    @Test
    fun `every field the manifest promises is a field the record actually declares`() {
        backupManifest.forEach { contract ->
            assertEquals(
                contract.columns.map { it.field },
                contract.descriptor.elementNames.toList(),
                "${contract.table}: the manifest and ${contract.descriptor.serialName} disagree about the " +
                    "fields, or about the order they are written in",
            )
        }
    }

    @Test
    fun `a column that may be absent is carried by a field that may be null`() {
        backupManifest.forEach { contract ->
            val columns = schema.getValue(contract.table)
            val nullable = columns.filterNot { it.notNull }.map { it.name }.sorted()
            val optional =
                contract.descriptor
                    .elementDescriptors
                    .toList()
                    .withIndex()
                    .filter { it.value.isNullable }
                    .map { contract.columns[it.index].column }
                    .sorted()
            assertEquals(
                nullable,
                optional,
                "${contract.table}: the columns that may be null and the fields that may be null are not the same set",
            )
        }
    }

    @Test
    fun `field names follow the format's own naming and not the database's`() {
        backupManifest.forEach { contract ->
            contract.columns.forEach { (column, field) ->
                assertTrue(
                    '_' !in field,
                    "${contract.table}.$column is carried by '$field', which is a column name rather than a field name",
                )
            }
            // Where the two spellings differ the pairing is a decision, and this
            // is what proves the manifest states it rather than computing it.
            val renamed = contract.columns.count { it.column != it.field }
            assertTrue(
                renamed > 0 || contract.columns.all { '_' !in it.column },
                "${contract.table} has columns with underscores but no field differs from its column",
            )
        }
    }

    @Test
    fun `data carries the fifteen arrays in the order the restore needs them`() {
        assertEquals(
            backupManifest.map { it.arrayName },
            serializer<BackupData>().descriptor.elementNames.toList(),
            "the arrays of the document are not the manifest's tables, in the manifest's order",
        )
    }

    @Test
    fun `every table is ordered by columns it actually has`() {
        backupManifest.forEach { contract ->
            val columns = schema.getValue(contract.table).map { it.name }.toSet()
            assertTrue(contract.orderedBy.isNotEmpty(), "${contract.table} declares no order")
            contract.orderedBy.forEach { column ->
                assertTrue(column in columns, "${contract.table} is ordered by '$column', which it does not have")
            }
        }
    }

    @Test
    fun `nothing that belongs to the database rather than the user is carried`() {
        val internals = listOf("room_master_table", "sqlite_sequence", "sqlite_stat1", "android_metadata")
        internals.forEach { table ->
            assertTrue(
                backupManifest.none { it.table == table },
                "$table is the database's own bookkeeping and does not belong in a backup",
            )
        }
    }

    @Test
    fun `the envelope declares its fields in the order the format fixes`() {
        assertEquals(
            listOf("format", "formatVersion", "appVersion", "sourceSchemaVersion", "createdAt", "dataSha256", "data"),
            serializer<BackupEnvelopeV1>().descriptor.elementNames.toList(),
        )
    }

    private data class SchemaColumn(
        val name: String,
        val notNull: Boolean,
    )

    private fun readCommittedSchema(): Map<String, List<SchemaColumn>> {
        val file = schemaDirectory().resolve("8.json")
        val parsed = Json.parseToJsonElement(Files.readString(file))
        val root = parsed.jsonObject
        val database = root.getValue("database").jsonObject
        val entities = database.getValue("entities") as JsonArray
        val views = database["views"] as? JsonArray
        assertTrue(views == null || views.isEmpty(), "the schema has views, which this test does not account for")
        return entities.associate { entity ->
            val declaration = entity.jsonObject
            val table = declaration.getValue("tableName").jsonPrimitive.content
            val fields =
                declaration.getValue("fields").jsonArray.map { field ->
                    val column = field.jsonObject
                    SchemaColumn(
                        name = column.getValue("columnName").jsonPrimitive.content,
                        notNull = column["notNull"]?.jsonPrimitive?.boolean ?: false,
                    )
                }
            table to fields
        }
    }

    private fun schemaDirectory(): Path {
        // Tests run with the module as the working directory, but a run started
        // from the repository root is just as valid; both are tried rather than
        // assumed.
        val candidates =
            listOf(
                Path.of("schemas", SCHEMA_PACKAGE),
                Path.of("app", "schemas", SCHEMA_PACKAGE),
            )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("cannot find the committed Room schemas; looked in $candidates")
    }

    private companion object {
        const val SCHEMA_PACKAGE = "dev.pnptracker.data.database.AppDatabase"
    }
}
