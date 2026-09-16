package dev.pnptracker.platform.diagnostics

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftImport
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameSetupStore
import dev.pnptracker.data.repository.ImportDraftRemovalStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.TaskEditStore
import dev.pnptracker.data.repository.TaskExportStore
import dev.pnptracker.data.repository.TaskProgressOutcome
import dev.pnptracker.data.repository.TaskProgressStore
import dev.pnptracker.data.repository.UnfinishedImportsStore
import dev.pnptracker.data.repository.UnfinishedImportsUnreadable
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.TaskExportException
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.TaskProgressFailure
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Storage refusing, at every boundary that turns one into a typed answer.
 *
 * PLAN 14.7.2 puts the record where the raw cause becomes the answer, once, and
 * this drives each of those boundaries against a real SQLite file that refuses a
 * real statement. Every case is asked three things: the user's answer is exactly
 * what it was before any of this, the one record says the event, level, area and
 * reason the plan gives it, and a log that throws changes neither.
 */
class StorageFailureRecordsTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val failing = FailingSqliteDriver()
    private val diagnostics = RecordingDiagnostics()
    private val throwing = ThrowingDiagnostics()
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = failing).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        failing.disarm()
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /** Room's SQL, uppercased and with its quoting and spacing taken out. */
    private fun plain(sql: String) =
        sql
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()

    /** The application's own writes, never Room's bookkeeping. */
    private fun failNextWrite() =
        failing.failOn { sql ->
            val statement = plain(sql)
            "ROOM_TABLE_MODIFICATION_LOG" !in statement &&
                (statement.startsWith("INSERT") || statement.startsWith("UPDATE") || statement.startsWith("DELETE"))
        }

    private fun failNextReadOf(table: String) =
        failing.failOn { sql ->
            val statement = plain(sql)
            statement.startsWith("SELECT") && "ROOM_" !in statement && table.uppercase() in statement
        }

    /**
     * What a refused statement is, and what it is caused by.
     *
     * Room hands the driver's refusal on wrapped in one of its own, so the class
     * and the root cause's class are the same name — measured rather than assumed.
     */
    private val storageRefusal = "androidx.sqlite.SQLiteException"

    /** The names this test puts into the application, none of which may be recorded. */
    private fun assertNothingLeaked() =
        assertLinesCarryNothingOfTheUsers(
            diagnostics,
            "Harmonies",
            "Kırmızı",
            "Gri token",
            directory.root.toString(),
            System.getProperty("user.name"),
        )

    @Test
    fun `writing a cell's text`() =
        runBlocking {
            val task = insertGameCellAndTask(database)
            val gameId =
                database
                    .gameDao()
                    .activeGames()
                    .first()
                    .id
            val store = { log: Diagnostics -> CellTextStore(database.cellSegmentDao(), diagnostics = log) }
            failNextWrite()
            val refused =
                assertFailsWith<CellTextException> {
                    store(diagnostics).saveDocumentText(gameId, CellColumnType.THREE_D, task.name, "${task.name} ve daha fazlası")
                }
            failing.disarm()

            assertEquals(CellTextFailure.COULD_NOT_SAVE, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_WRITE_FAILED,
                    area = DiagnosticArea.CELL_TEXT,
                    reason = CellTextFailure.COULD_NOT_SAVE,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()

            failNextWrite()
            val withABrokenLog =
                assertFailsWith<CellTextException> {
                    store(throwing).saveDocumentText(gameId, CellColumnType.THREE_D, task.name, "${task.name} ve daha fazlası")
                }
            failing.disarm()
            assertEquals(refused.failure, withABrokenLog.failure, "a log that throws changed the answer")
            assertEquals(1, throwing.calls)
        }

    @Test
    fun `turning a task into text`() =
        runBlocking {
            val task = insertGameCellAndTask(database)
            failNextWrite()
            val refused =
                assertFailsWith<TaskEditException> {
                    TaskEditStore(database.taskEditDao(), diagnostics = diagnostics).convertTaskToText(task.id)
                }
            failing.disarm()

            assertEquals(TaskEditFailure.COULD_NOT_SAVE, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_WRITE_FAILED,
                    area = DiagnosticArea.TASK_EDIT,
                    reason = TaskEditFailure.COULD_NOT_SAVE,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `making a game`() =
        runBlocking {
            failNextWrite()
            val refused =
                assertFailsWith<GameSetupException> {
                    GameSetupStore(database.gameDao(), database.gameCellDao(), diagnostics = diagnostics).createGame("Harmonies")
                }
            failing.disarm()

            assertEquals(GameSetupFailure.COULD_NOT_SAVE, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_WRITE_FAILED,
                    area = DiagnosticArea.GAME_SETUP,
                    reason = GameSetupFailure.COULD_NOT_SAVE,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `adding a colour`() =
        runBlocking {
            failNextWrite()
            val refused =
                assertFailsWith<ColorSetupException> {
                    ColorCatalogueStore(database.colorDao(), diagnostics = diagnostics).createColor("Kırmızı ev", "#FF0000")
                }
            failing.disarm()

            assertEquals(ColorSetupFailure.COULD_NOT_SAVE, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_WRITE_FAILED,
                    area = DiagnosticArea.COLORS,
                    reason = ColorSetupFailure.COULD_NOT_SAVE,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `finishing a task, and reading whether a game is finished`() =
        runBlocking {
            val task = insertGameCellAndTask(database)
            val gameId =
                database
                    .gameDao()
                    .activeGames()
                    .first()
                    .id
            val store = TaskProgressStore(database.taskProgressDao(), diagnostics = diagnostics)

            failNextWrite()
            val refused = store.completeTask(task.id, IdGenerator.Random.newId())
            failing.disarm()
            assertEquals(TaskProgressOutcome.Refused(TaskProgressFailure.TASK_NOT_AVAILABLE), refused)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_WRITE_FAILED,
                    area = DiagnosticArea.TASK_PROGRESS,
                    reason = TaskProgressFailure.TASK_NOT_AVAILABLE,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            diagnostics.forget()

            failNextReadOf("games")
            assertNull(store.gameCompletion(gameId))
            failing.disarm()
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_READ_FAILED,
                    area = DiagnosticArea.TASK_PROGRESS,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `marking a raw block read`() =
        runBlocking {
            val draft = aDraftImport(database, blocks = 1, fingerprint = "%064x".format(3))
            failNextWrite()
            val refused =
                assertFailsWith<ImportReviewException> {
                    ImportReviewStore(
                        database.importDao(),
                        database.gameDao(),
                        database.colorDao(),
                        diagnostics = diagnostics,
                    ).setProcessed(
                        database
                            .importDao()
                            .rawBlocksOfBatch(draft.batchId)
                            .first()
                            .id,
                        true,
                    )
                }
            failing.disarm()

            assertEquals(ImportReviewFailure.COULD_NOT_SAVE, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_WRITE_FAILED,
                    area = DiagnosticArea.IMPORT_REVIEW,
                    reason = ImportReviewFailure.COULD_NOT_SAVE,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `removing an unconfirmed import`() =
        runBlocking {
            val draft = aDraftImport(database, blocks = 1, fingerprint = "%064x".format(4))
            failNextWrite()
            val outcome = ImportDraftRemovalStore(database.importDao(), diagnostics).remove(draft.batchId)
            failing.disarm()

            assertEquals(DraftRemovalOutcome.Refused(draft.batchId, DraftRemovalRefusal.COULD_NOT_SAVE), outcome)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_WRITE_FAILED,
                    area = DiagnosticArea.DRAFT_REMOVAL,
                    reason = DraftRemovalRefusal.COULD_NOT_SAVE,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `asking whether an unfinished import still holds together`() =
        runBlocking {
            val draft = aDraftImport(database, blocks = 1, fingerprint = "%064x".format(5))
            failNextReadOf("import_batches")
            assertFailsWith<UnfinishedImportsUnreadable> {
                UnfinishedImportsStore(database, database.importDao(), diagnostics).healthOf(draft.batchId)
            }
            failing.disarm()

            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_READ_FAILED,
                    area = DiagnosticArea.UNFINISHED_IMPORTS,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `reading the tasks an export would write`() =
        runBlocking {
            insertGameCellAndTask(database)
            failNextReadOf("tasks")
            val refused =
                assertFailsWith<TaskExportException> {
                    TaskExportStore(database.taskExportDao(), diagnostics).exportedTasks()
                }
            failing.disarm()

            assertEquals(ExportFailure.COULD_NOT_READ, refused.failure)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_READ_FAILED,
                    area = DiagnosticArea.EXPORT,
                    reason = ExportFailure.COULD_NOT_READ,
                    exception = storageRefusal,
                    cause = storageRefusal,
                ),
                diagnostics.only(),
            )
            assertNothingLeaked()
        }

    @Test
    fun `a broken log leaves every one of these answers as it was`() =
        runBlocking {
            insertGameCellAndTask(database)
            failNextReadOf("tasks")
            val refused =
                assertFailsWith<TaskExportException> {
                    TaskExportStore(database.taskExportDao(), throwing).exportedTasks()
                }
            failing.disarm()

            assertEquals(ExportFailure.COULD_NOT_READ, refused.failure)
            assertEquals(1, throwing.calls, "the boundary did not even try to record")
            val readable = TaskExportStore(database.taskExportDao(), throwing).exportedTasks()
            assertEquals(1, readable.size, "a log that throws stopped the export from working afterwards")
        }
}
