package dev.pnptracker.platform.diagnostics

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameSetupStore
import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.PoolSource
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.data.repository.RefusingCatalogue
import dev.pnptracker.data.repository.RefusingPool
import dev.pnptracker.data.repository.RefusingTable
import dev.pnptracker.data.repository.TaskEditStore
import dev.pnptracker.data.repository.TaskFromTextStore
import dev.pnptracker.data.repository.TaskProgressStore
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.answeringStorageRefusal
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.ui.feature.colors.ColorCatalogueController
import dev.pnptracker.ui.feature.colors.ColorCatalogueState
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.games.GameTableRowsState
import dev.pnptracker.ui.feature.pools.PoolContentState
import dev.pnptracker.ui.feature.pools.PoolController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The three observed readings Dilim 2 measured, now answered in words
 * (PLAN 14.7.6).
 *
 * The game table and the colour catalogue had no answer at all for storage
 * refusing: the exception left the collecting coroutine and the screen was left
 * to whatever caught it. The pool had one, and caught every `Throwable` to reach
 * it, which meant a defect and a database that would not answer looked exactly
 * alike on screen and neither could be told from the other afterwards.
 *
 * Each is asked the same five things: the answer says the reading failed rather
 * than that there is nothing there, one safe line is written, a defect and a
 * cancellation still rise, a later reading that works clears it, and a log that
 * throws changes none of it.
 */
class UnreadableReadingsTest {
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

    private fun plain(sql: String) =
        sql
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()

    private fun failNextReadOf(table: String) =
        failing.failOn { sql ->
            val statement = plain(sql)
            statement.startsWith("SELECT") && "ROOM_" !in statement && table.uppercase() in statement
        }

    private fun tableController(log: Diagnostics = diagnostics) =
        GameTableController(
            table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()),
            setup = GameSetupStore(database.gameDao(), database.gameCellDao()),
            cells = CellTextStore(database.cellSegmentDao()),
            colors = ColorCatalogueStore(database.colorDao()),
            taskCreation = TaskFromTextStore(database.taskFromTextDao()),
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = TaskProgressStore(database.taskProgressDao()),
            diagnostics = log,
        )

    private fun catalogueController(log: Diagnostics = diagnostics) =
        ColorCatalogueController(ColorCatalogueStore(database.colorDao()), log)

    private fun poolController(log: Diagnostics = diagnostics) =
        PoolController(
            poolType = PoolType.THREE_D,
            pools = PoolStore(database.poolDao()),
            colors = ColorCatalogueStore(database.colorDao()),
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = TaskProgressStore(database.taskProgressDao()),
            diagnostics = log,
        )

    /**
     * Collects a reading that does not end, until [settled] is true of the screen.
     *
     * A reading storage refused ends its own stream, so the tests above simply
     * call the collector and it returns. A reading that works does not end —
     * that is the whole point of an observed one — so a recovery has to be
     * watched for and then let go of, exactly as leaving the screen lets go of
     * it. The timeout is a test failing rather than a test hanging.
     */
    private suspend fun CoroutineScope.collectUntil(
        collector: suspend () -> Unit,
        settled: () -> Boolean,
    ) {
        val job = launch { collector() }
        withTimeout(10.seconds) { while (!settled()) yield() }
        job.cancelAndJoin()
    }

    private fun assertRefusalRecorded(area: DiagnosticArea) {
        assertRecordedAsPlanned(
            ExpectedRecord(
                DiagnosticEvent.STORAGE_READ_FAILED,
                area = area,
                exception = "androidx.sqlite.SQLiteException",
                cause = "androidx.sqlite.SQLiteException",
            ),
            diagnostics.only(),
        )
        // The trap's own message carries the whole statement, so the SQL, the
        // table and this machine's folder would all be here if a message were
        // ever read into a record.
        assertLinesCarryNothingOfTheUsers(
            diagnostics,
            "Harmonies",
            directory.root.toString(),
            System.getProperty("user.name"),
        )
    }

    // ------------------------------------------------------------ game table

    @Test
    fun `a game table storage refuses says so, and never that the library is empty`() =
        runBlocking {
            insertGameCellAndTask(database)
            val controller = tableController()
            failNextReadOf("games")

            controller.observeTable()

            assertEquals(GameTableRowsState.Failed, controller.state.rows)
            assertRefusalRecorded(DiagnosticArea.GAME_TABLE)
        }

    @Test
    fun `changing the view or the filter over a refused table does not draw an empty one`() =
        runBlocking {
            insertGameCellAndTask(database)
            val controller = tableController()
            failNextReadOf("games")
            controller.observeTable()

            controller.showView(GameTableView.COMPLETED)
            assertEquals(GameTableRowsState.Failed, controller.state.rows)
            controller.clearFilters()

            assertEquals(GameTableRowsState.Failed, controller.state.rows, "a filter drew an empty table over a refused reading")
        }

    @Test
    fun `a table read that works after one that did not puts the rows back`() =
        runBlocking {
            insertGameCellAndTask(database)
            val controller = tableController()
            failNextReadOf("games")
            controller.observeTable()
            assertEquals(GameTableRowsState.Failed, controller.state.rows)
            failing.disarm()

            // What the screen does when the user presses the button: collect again.
            controller.readAgain()
            collectUntil({ controller.observeTable() }) { controller.state.rows is GameTableRowsState.Content }

            assertIs<GameTableRowsState.Content>(controller.state.rows)
            // Still the one line from the one refusal; collecting again wrote none.
            assertEquals(1, diagnostics.records.size)
        }

    // ----------------------------------------------------- colour catalogue

    @Test
    fun `a catalogue storage refuses says so, and never that there are no colours`() =
        runBlocking {
            val controller = catalogueController()
            failNextReadOf("colors")

            controller.observeColors()

            assertEquals(ColorCatalogueState.Failed, controller.state.catalogue)
            assertRefusalRecorded(DiagnosticArea.COLORS)
        }

    @Test
    fun `a catalogue read that works after one that did not puts the colours back`() =
        runBlocking {
            val controller = catalogueController()
            failNextReadOf("colors")
            controller.observeColors()
            assertEquals(ColorCatalogueState.Failed, controller.state.catalogue)
            failing.disarm()

            controller.readAgain()
            collectUntil({ controller.observeColors() }) { controller.state.catalogue is ColorCatalogueState.Content }

            assertTrue(assertIs<ColorCatalogueState.Content>(controller.state.catalogue).colors.isNotEmpty())
            assertEquals(1, diagnostics.records.size)
        }

    @Test
    fun `the table keeps the colours it had when the catalogue behind its editor is refused`() =
        runBlocking {
            val controller = tableController()
            collectUntil({ controller.observeColorCatalogue() }) { controller.state.colors.isNotEmpty() }
            val before = controller.state.colors
            assertTrue(before.isNotEmpty())
            failNextReadOf("colors")

            controller.observeColorCatalogue()

            assertEquals(before, controller.state.colors, "the editor was left offering no colour at all")
            assertRefusalRecorded(DiagnosticArea.COLORS)
        }

    // ------------------------------------------------------------- the pool

    @Test
    fun `a pool storage refuses says so, and never that there is no work in it`() =
        runBlocking {
            insertGameCellAndTask(database)
            val controller = poolController()
            failNextReadOf("tasks")

            controller.observePool()

            assertEquals(PoolContentState.Failed, controller.state.content)
            assertRefusalRecorded(DiagnosticArea.POOLS)
        }

    @Test
    fun `a pool read that works after one that did not puts the work back`() =
        runBlocking {
            insertGameCellAndTask(database)
            val controller = poolController()
            failNextReadOf("tasks")
            controller.observePool()
            assertEquals(PoolContentState.Failed, controller.state.content)
            failing.disarm()

            controller.readAgain()
            collectUntil({ controller.observePool() }) { controller.state.content is PoolContentState.Content }

            assertIs<PoolContentState.Content>(controller.state.content)
            assertEquals(1, diagnostics.records.size)
        }

    // ------------------------------------- defects, cancellation and a broken log

    @Test
    fun `a defect in any of the three rises unmasked and is not recorded`() =
        runBlocking {
            val defect = IllegalStateException("a task with no name reached the reader")

            val fromTable =
                assertFailsWith<IllegalStateException> {
                    tableController(table = RefusingTable(defect)).observeTable()
                }
            val fromCatalogue =
                assertFailsWith<IllegalStateException> {
                    ColorCatalogueController(RefusingCatalogue(defect), diagnostics).observeColors()
                }
            val fromPool =
                assertFailsWith<IllegalStateException> {
                    poolController(pools = RefusingPool(defect)).observePool()
                }

            assertEquals(defect, fromTable)
            assertEquals(defect, fromCatalogue)
            assertEquals(defect, fromPool)
            assertEquals(emptyList(), diagnostics.records.map { it.event.code }, "a defect was filed as a storage problem")
        }

    @Test
    fun `a null nobody expected rises as the defect it is`() =
        runBlocking {
            val defect = NullPointerException("a colour row had no hex")

            assertFailsWith<NullPointerException> { ColorCatalogueController(RefusingCatalogue(defect), diagnostics).observeColors() }

            assertEquals(emptyList(), diagnostics.records.map { it.event.code })
        }

    @Test
    fun `an illegal argument in any of the three rises unmasked, is not called unreadable and is not recorded`() =
        runBlocking {
            val defect = IllegalArgumentException("a quantity of -3 reached the reader")
            val table = tableController(table = RefusingTable(defect))
            val catalogue = ColorCatalogueController(RefusingCatalogue(defect), diagnostics)
            val pool = poolController(pools = RefusingPool(defect))

            assertSame(defect, assertFailsWith<IllegalArgumentException> { table.observeTable() })
            assertSame(defect, assertFailsWith<IllegalArgumentException> { catalogue.observeColors() })
            assertSame(defect, assertFailsWith<IllegalArgumentException> { pool.observePool() })
            var refused = false
            assertSame(
                defect,
                assertFailsWith<IllegalArgumentException> {
                    flow<Unit> { throw defect }.answeringStorageRefusal(DiagnosticArea.POOLS, diagnostics) { refused = true }.collect()
                },
            )

            // A defect is not a database that would not answer: no screen says so,
            // and nothing is filed as one.
            assertEquals(GameTableRowsState.Loading, table.state.rows)
            assertEquals(ColorCatalogueState.Loading, catalogue.state.catalogue)
            assertEquals(PoolContentState.Loading, pool.state.content)
            assertFalse(refused, "the shared helper answered a defect as a refusal")
            assertEquals(emptyList(), diagnostics.records.map { it.event.code }, "a defect was filed as a storage problem")
        }

    @Test
    fun `an Error in any of the three is not caught, not turned into anything and not recorded`() =
        runBlocking {
            val broken = ReaderBroke()
            val table = tableController(table = RefusingTable(broken))
            val catalogue = ColorCatalogueController(RefusingCatalogue(broken), diagnostics)
            val pool = poolController(pools = RefusingPool(broken))

            assertSame(broken, assertFailsWith<ReaderBroke> { table.observeTable() })
            assertSame(broken, assertFailsWith<ReaderBroke> { catalogue.observeColors() })
            assertSame(broken, assertFailsWith<ReaderBroke> { pool.observePool() })
            var refused = false
            assertSame(
                broken,
                assertFailsWith<ReaderBroke> {
                    flow<Unit> { throw broken }.answeringStorageRefusal(DiagnosticArea.COLORS, diagnostics) { refused = true }.collect()
                },
            )

            assertEquals(GameTableRowsState.Loading, table.state.rows)
            assertEquals(ColorCatalogueState.Loading, catalogue.state.catalogue)
            assertEquals(PoolContentState.Loading, pool.state.content)
            assertFalse(refused, "the shared helper answered an Error as a refusal")
            assertEquals(emptyList(), diagnostics.records.map { it.event.code }, "an Error was filed as a storage problem")
        }

    /** An `Error` of the test's own: whatever rises is this very object, or something caught it. */
    private class ReaderBroke : Error("the reader is broken beyond a database refusing")

    @Test
    fun `a cancellation rises and is never recorded`() =
        runBlocking {
            val closing = CancellationException("the screen was left")

            assertFailsWith<CancellationException> { poolController(pools = RefusingPool(closing)).observePool() }
            assertFailsWith<CancellationException> { ColorCatalogueController(RefusingCatalogue(closing), diagnostics).observeColors() }

            assertEquals(emptyList(), diagnostics.records.map { it.event.code }, "leaving a screen was written down as a failure")
        }

    @Test
    fun `a log that throws changes none of the three answers`() =
        runBlocking {
            insertGameCellAndTask(database)
            val table = tableController(throwing)
            failNextReadOf("games")
            table.observeTable()
            assertEquals(GameTableRowsState.Failed, table.state.rows)

            val catalogue = catalogueController(throwing)
            failNextReadOf("colors")
            catalogue.observeColors()
            assertEquals(ColorCatalogueState.Failed, catalogue.state.catalogue)

            val pool = poolController(throwing)
            failNextReadOf("tasks")
            pool.observePool()
            assertEquals(PoolContentState.Failed, pool.state.content)

            assertEquals(3, throwing.calls, "a boundary did not even try to record")
        }

    // ------------------------------------------------------------- overloads

    private fun tableController(table: GameTableSource) =
        GameTableController(
            table = table,
            setup = GameSetupStore(database.gameDao(), database.gameCellDao()),
            cells = CellTextStore(database.cellSegmentDao()),
            colors = ColorCatalogueStore(database.colorDao()),
            taskCreation = TaskFromTextStore(database.taskFromTextDao()),
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = TaskProgressStore(database.taskProgressDao()),
            diagnostics = diagnostics,
        )

    private fun poolController(pools: PoolSource) =
        PoolController(
            poolType = PoolType.THREE_D,
            pools = pools,
            colors = ColorCatalogueStore(database.colorDao()),
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = TaskProgressStore(database.taskProgressDao()),
            diagnostics = diagnostics,
        )
}
