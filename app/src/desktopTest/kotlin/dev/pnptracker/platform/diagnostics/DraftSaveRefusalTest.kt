package dev.pnptracker.platform.diagnostics

import androidx.room3.useReaderConnection
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * The first of the five escapes Dilim 2 measured: a draft import that storage
 * would not take (PLAN 14.7.6).
 *
 * It used to leave `ImportDraftStore` as the driver's own exception. The screen
 * catches only `ImportPreparationException`, so it travelled out of the
 * controller's coroutine and the screen sat on "kaydediliyor" for good. What is
 * asked here is everything that changed and everything that must not have: the
 * answer is typed, the preview survives, the button comes back, the transaction
 * left nothing behind, and one line is written by the one boundary that named it.
 */
class DraftSaveRefusalTest {
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

    /** A two-line CSV, written where this test works and nowhere near anyone's own. */
    private fun aFile(): Path {
        val file = directory.root.resolve("liste.csv")
        Files.write(
            file,
            """
            game,source_type,raw_text
            Harmonies,3D,12 KIRMIZI
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
        return file
    }

    private fun aController(
        file: Path,
        log: Diagnostics,
    ) = ImportController(
        gateway = DesktopImportFileGateway(FixedImportPicker(file)),
        store = ImportDraftStore(database.importDao(), diagnostics = log),
        diagnostics = log,
    )

    /** The application's own write of the batch row, never Room's bookkeeping. */
    private fun refuseTheBatchRow() =
        failing.failOn { sql ->
            val statement = sql.replace("`", "").replace(Regex("\\s+"), " ").uppercase()
            statement.startsWith("INSERT") && "IMPORT_BATCHES" in statement
        }

    /** How many rows a table holds, asked of the file rather than of a DAO. */
    private suspend fun rowsIn(table: String): Int =
        database.useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
                statement.step()
                statement.getLong(0).toInt()
            }
        }

    @Test
    fun `a draft storage refuses keeps the preview, comes out of saving and can be tried again`() =
        runBlocking {
            val controller = aController(aFile(), diagnostics)
            controller.chooseFile()
            val preview = assertIs<ImportScreenState.PreviewReady>(controller.state).session
            refuseTheBatchRow()

            controller.saveDraft()

            val notSaved = assertIs<ImportScreenState.NotSaved>(controller.state)
            // Everything read is still in hand, down to the file name and the
            // draft the preview was made of.
            assertEquals(preview, notSaved.session)
            assertFalse(controller.isBusy, "the screen is still saying it is busy")

            // And the same button really does try the same draft again.
            failing.disarm()
            controller.saveDraft()
            val saved = assertIs<ImportScreenState.Saved>(controller.state)
            assertEquals(preview.fileName, saved.summary.fileName)
            assertEquals(1, rowsIn("import_batches"))
        }

    @Test
    fun `a refused save leaves no batch and no raw block behind`() =
        runBlocking {
            val controller = aController(aFile(), diagnostics)
            controller.chooseFile()
            refuseTheBatchRow()

            controller.saveDraft()

            assertIs<ImportScreenState.NotSaved>(controller.state)
            assertEquals(0, rowsIn("import_batches"), "half an import was left behind")
            assertEquals(0, rowsIn("raw_import_blocks"), "half an import was left behind")
        }

    @Test
    fun `the store records it once, and the controller above it does not record it again`() =
        runBlocking {
            val controller = aController(aFile(), diagnostics)
            controller.chooseFile()
            refuseTheBatchRow()

            controller.saveDraft()

            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_WRITE_FAILED,
                    area = DiagnosticArea.IMPORT_DRAFT,
                    reason = ImportFailure.COULD_NOT_SAVE,
                    exception = "androidx.sqlite.SQLiteException",
                    cause = "androidx.sqlite.SQLiteException",
                ),
                diagnostics.only(),
            )
            // The trap puts the whole statement into the message it throws, so
            // this would see the SQL, the table and the temporary folder if any
            // of it were read.
            assertLinesCarryNothingOfTheUsers(
                diagnostics,
                "Harmonies",
                "KIRMIZI",
                "liste.csv",
                directory.root.toString(),
                System.getProperty("user.name"),
            )
        }

    @Test
    fun `a log that throws changes neither the answer nor what was written`() =
        runBlocking {
            val controller = aController(aFile(), throwing)
            controller.chooseFile()
            refuseTheBatchRow()

            controller.saveDraft()

            assertIs<ImportScreenState.NotSaved>(controller.state)
            assertEquals(1, throwing.calls, "the boundary did not even try to record")
            assertEquals(0, rowsIn("import_batches"))
        }

    @Test
    fun `a save that works records nothing`() =
        runBlocking {
            val controller = aController(aFile(), diagnostics)
            controller.chooseFile()

            controller.saveDraft()

            assertIs<ImportScreenState.Saved>(controller.state)
            assertEquals(emptyList(), diagnostics.records.map { it.event.code })
        }
}
