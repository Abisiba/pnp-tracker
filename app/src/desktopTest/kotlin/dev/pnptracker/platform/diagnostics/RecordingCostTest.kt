package dev.pnptracker.platform.diagnostics

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftImport
import dev.pnptracker.data.database.executeRawSql
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.ImportDraftRemovalStore
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.CellColumnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What recording costs the work it follows: nothing.
 *
 * Three claims. A record is handed over after the transaction has ended, so no
 * disk of the log's is ever inside one (PLAN 14.7.2, PLAN 16). Failures at the
 * same moment become separate whole lines rather than one torn one. And a defect
 * — a broken invariant rather than a refusal — is not filed as a failure of the
 * user's work at all; it rises exactly as it did (PLAN 14.4.5).
 */
class RecordingCostTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    @Test
    fun `a record is only ever handed over after the transaction has let go`() =
        runBlocking {
            database = DatabaseFactory().open(directory.databaseFile)
            val task = insertGameCellAndTask(database)
            val draft = aDraftImport(database, blocks = 1, fingerprint = "%064x".format(11))
            val blockId =
                database
                    .importDao()
                    .rawBlocksOfBatch(draft.batchId)
                    .first()
                    .id
            executeRawSql(
                database,
                "UPDATE tasks SET source_raw_import_block_id = ? WHERE id = ?",
                blockId.toString(),
                task.id.toString(),
            )
            val probe = ProbingDiagnostics(database)

            val outcome = ImportDraftRemovalStore(database.importDao(), probe).remove(draft.batchId)

            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.HELD_BY_RECORDS), outcome)
            assertEquals(listOf(true), probe.couldWriteWhileRecording, "the record was handed over inside the transaction")
        }

    @Test
    fun `eight failures at the same moment become eight whole lines`() =
        runBlocking {
            val refusing = RefusingWrites()
            database = DatabaseFactory(driver = refusing).open(directory.databaseFile)
            val task = insertGameCellAndTask(database)
            val gameId =
                database
                    .gameDao()
                    .activeGames()
                    .first()
                    .id
            // Armed only now: the fixture above is written the ordinary way.
            refusing.refuseEveryWrite()
            val log = QueuedDiagnostics.inDirectory(directory.root.resolve("state/pnp-tracker/logs"), AppInfo.Current)
            val store = CellTextStore(database.cellSegmentDao(), diagnostics = log)

            val refusals =
                withContext(Dispatchers.IO) {
                    (1..8)
                        .map {
                            async {
                                assertFailsWith<CellTextException> {
                                    store.saveDocumentText(gameId, CellColumnType.THREE_D, task.name, "${task.name} $it")
                                }
                            }
                        }.awaitAll()
                }
            log.close()

            assertEquals(8, refusals.size)
            val lines =
                Files
                    .readAllLines(directory.root.resolve("state/pnp-tracker/logs/pnp-tanilama.jsonl"), StandardCharsets.UTF_8)
                    .filter { it.isNotBlank() }
            assertEquals(8, lines.size, "one line for each failure")
            val read = lines.map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(
                (1..8).toList(),
                read.map {
                    it
                        .getValue("seq")
                        .jsonPrimitive.content
                        .toInt()
                },
            )
            assertTrue(read.all { it.getValue("event").jsonPrimitive.content == "storage.write_failed" })
        }

    @Test
    fun `a defect of this application's own is not recorded and is not dressed up`() =
        runBlocking {
            database = DatabaseFactory().open(directory.databaseFile)
            val diagnostics = RecordingDiagnostics()

            assertFailsWith<IllegalArgumentException> {
                ColorCatalogueStore(database.colorDao(), diagnostics = diagnostics).createColor("Kırmızı", "bu bir renk değil")
            }

            assertEquals(emptyList(), diagnostics.records.map { it.event.code })
        }
}

/**
 * A log that asks, while it is being given a record, whether the database would
 * take a write right now.
 *
 * If a boundary recorded while it still held the writer connection, this would
 * wait for a connection that cannot come and answer false.
 */
private class ProbingDiagnostics(
    private val database: AppDatabase,
) : Diagnostics {
    val couldWriteWhileRecording = mutableListOf<Boolean>()

    override fun record(record: DiagnosticRecord) {
        val free =
            runBlocking {
                withTimeoutOrNull(2_000) {
                    database.useWriterConnection { transactor -> transactor.immediateTransaction<Unit> { } }
                    true
                } ?: false
            }
        couldWriteWhileRecording.add(free)
    }
}

/** A real database that refuses every write this application makes. */
private class RefusingWrites(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver {
    @Volatile
    private var armed = false

    fun refuseEveryWrite() {
        armed = true
    }

    override fun open(fileName: String): SQLiteConnection = Guarded(delegate.open(fileName))

    private inner class Guarded(
        private val connection: SQLiteConnection,
    ) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = Refusing(connection.prepare(sql), sql)

        override fun inTransaction(): Boolean = connection.inTransaction()

        override fun close() = connection.close()
    }

    private inner class Refusing(
        private val statement: SQLiteStatement,
        private val sql: String,
    ) : SQLiteStatement by statement {
        override fun step(): Boolean {
            val plain = sql.replace("`", "").uppercase().trim()
            val ours = "ROOM_" !in plain && "SQLITE_" !in plain
            if (armed && ours && (plain.startsWith("INSERT") || plain.startsWith("UPDATE") || plain.startsWith("DELETE"))) {
                throw SQLiteException("this database takes no writes")
            }
            return statement.step()
        }
    }
}
