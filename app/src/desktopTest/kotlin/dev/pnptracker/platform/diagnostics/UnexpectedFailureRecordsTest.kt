package dev.pnptracker.platform.diagnostics

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.HistorySource
import dev.pnptracker.data.repository.PoolSource
import dev.pnptracker.data.repository.TaskEditStore
import dev.pnptracker.data.repository.TaskProgressStore
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.history.HistoryLog
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.pools.PoolNavigationSummary
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.ui.feature.history.HistoryContentState
import dev.pnptracker.ui.feature.history.HistoryController
import dev.pnptracker.ui.feature.pools.PoolContentState
import dev.pnptracker.ui.feature.pools.PoolController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The two readings a screen shows as failed, and the error nothing caught at all.
 *
 * A screen that says "this could not be read" says the same thing whether
 * storage refused or something in this application is wrong. PLAN 14.4.5 keeps
 * those apart, so the record does too: storage becomes `storage.read_failed` and
 * anything else becomes `app.unexpected_failure`, and the screen's own answer is
 * untouched either way.
 *
 * The last two are about the error that reaches the window. What Compose 1.11.1
 * does with one was measured (PLAN 14.7.2) and is not changed: the recording
 * handler records and then hands the very same error to the handler Compose
 * would have used.
 */
class UnexpectedFailureRecordsTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private val diagnostics = RecordingDiagnostics()
    private var realDatabaseExistedBefore = false
    private val opened = mutableListOf<AppDatabase>()

    @BeforeTest
    fun createDirectory() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun deleteDirectory() {
        opened.forEach { it.close() }
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    @Test
    fun `a history storage will not read`() =
        runBlocking {
            val controller =
                HistoryController(RefusingHistory { SQLiteException("the database would not answer") }, diagnostics = diagnostics)

            controller.observeHistory()

            assertIs<HistoryContentState.Failed>(controller.state.content)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_READ_FAILED,
                    area = DiagnosticArea.HISTORY,
                    exception = "androidx.sqlite.SQLiteException",
                ),
                diagnostics.only(),
            )
            assertLinesCarryNothingOfTheUsers(diagnostics, "the database would not answer")
        }

    @Test
    fun `a history reading that broke for a reason of this application's own`() =
        runBlocking {
            val controller = HistoryController(RefusingHistory { IllegalStateException("bir görev iki yerde") }, diagnostics = diagnostics)

            controller.observeHistory()

            assertIs<HistoryContentState.Failed>(controller.state.content)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.UNEXPECTED_FAILURE,
                    area = DiagnosticArea.HISTORY,
                    exception = "java.lang.IllegalStateException",
                ),
                diagnostics.only(),
            )
            assertLinesCarryNothingOfTheUsers(diagnostics, "bir görev iki yerde")
        }

    @Test
    fun `a pool storage will not read, and a pool reading that broke`() =
        runBlocking {
            val refused = poolController(RefusingPools { SQLiteException("the database would not answer") })

            refused.observePool()

            assertIs<PoolContentState.Failed>(refused.state.content)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.STORAGE_READ_FAILED,
                    area = DiagnosticArea.POOLS,
                    exception = "androidx.sqlite.SQLiteException",
                ),
                diagnostics.only(),
            )
            diagnostics.forget()

            val broken = poolController(RefusingPools { IllegalStateException("bir havuz iki kez") })

            broken.observePool()

            assertIs<PoolContentState.Failed>(broken.state.content)
            assertRecordedAsPlanned(
                ExpectedRecord(
                    DiagnosticEvent.UNEXPECTED_FAILURE,
                    area = DiagnosticArea.POOLS,
                    exception = "java.lang.IllegalStateException",
                ),
                diagnostics.only(),
            )
            assertLinesCarryNothingOfTheUsers(diagnostics, "bir havuz iki kez")
        }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `an error that reached the window is recorded and then handled as it was before`() {
        val seen = mutableListOf<Throwable>()
        val failure = IllegalStateException("/home/birisi/pnp.db okunamadı")
        val handler =
            recordingExceptionHandler(
                diagnostics,
                WindowExceptionHandler { seen.add(it) },
            )

        handler.onException(failure)

        assertEquals(1, seen.size, "the handler Compose would have used was not asked")
        assertSame(failure, seen.single(), "a different error was handed on")
        assertRecordedAsPlanned(
            ExpectedRecord(
                DiagnosticEvent.UNEXPECTED_FAILURE,
                area = DiagnosticArea.APPLICATION,
                exception = "java.lang.IllegalStateException",
            ),
            diagnostics.only(),
        )
        assertLinesCarryNothingOfTheUsers(diagnostics, "birisi", "okunamadı")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a log that throws leaves the window's own handling exactly as it was`() {
        val throwing = ThrowingDiagnostics()
        val seen = mutableListOf<Throwable>()
        val failure = IllegalArgumentException("bir kusur")
        val rethrowing =
            recordingExceptionHandler(
                throwing,
                WindowExceptionHandler {
                    seen.add(it)
                    throw it
                },
            )

        val thrownOn = assertFailsWith<IllegalArgumentException> { rethrowing.onException(failure) }

        assertSame(failure, thrownOn, "the error Compose throws again was replaced")
        assertEquals(listOf<Throwable>(failure), seen)
        assertEquals(1, throwing.calls)
    }

    private fun poolController(pools: PoolSource): PoolController {
        val database = DatabaseFactory().open(directory.databaseFile).also { opened += it }
        return PoolController(
            poolType = PoolType.THREE_D,
            pools = pools,
            colors = ColorCatalogueStore(database.colorDao()),
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = TaskProgressStore(database.taskProgressDao()),
            diagnostics = diagnostics,
        )
    }
}

/** A history that cannot be read, in whichever way a test needs. */
private class RefusingHistory(
    private val raise: () -> Throwable,
) : HistorySource {
    override fun observeHistory(): Flow<HistoryLog> = flow { throw raise() }
}

/** A pool that cannot be read, in whichever way a test needs. */
private class RefusingPools(
    private val raise: () -> Throwable,
) : PoolSource {
    override fun observePool(poolType: PoolType): Flow<PoolSnapshot> = flow { throw raise() }

    override fun observeNavigationSummary(): Flow<PoolNavigationSummary> = flow { throw raise() }
}
