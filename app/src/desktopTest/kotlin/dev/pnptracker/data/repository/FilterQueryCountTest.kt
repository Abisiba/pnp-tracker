package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.search.TaskFlagFilter
import dev.pnptracker.domain.search.TaskStateFilter
import dev.pnptracker.ui.NoCells
import dev.pnptracker.ui.NoColors
import dev.pnptracker.ui.NoEditing
import dev.pnptracker.ui.NoProgress
import dev.pnptracker.ui.NoSetup
import dev.pnptracker.ui.NoTaskCreation
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.games.GameTableRowsState
import dev.pnptracker.ui.feature.pools.PoolController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What searching and filtering cost at the driver.
 *
 * The whole design rests on one claim: narrowing what is on screen is
 * arithmetic over rows that have already been read, so it costs nothing. PLAN 16
 * rules out reads that grow with the data, and PLAN 18 (Faz 3 testleri) will
 * ask for search and pool filtering to hold up at a thousand tasks — neither
 * survives a query per keystroke or a stream per choice.
 *
 * So this measures rather than argues. Every count is a frequency map: a set
 * would collapse four hundred runs of one statement into one and report a cost
 * nobody paid.
 */
class FilterQueryCountTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private fun ran(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .map { it.trimStart().uppercase() }
            .filterNot { it.startsWith("BEGIN") || it.startsWith("COMMIT") || it.startsWith("END") }
            .filterNot { it.startsWith("ROLLBACK") || it.startsWith("SAVEPOINT") || it.startsWith("RELEASE") }
            .filterNot { it.startsWith("PRAGMA") }
            .groupingBy { statement ->
                when {
                    "CHANGES()" in statement || "LAST_INSERT_ROWID()" in statement -> "write result"
                    statement.startsWith("SELECT") && "FROM TASK_COLORS" in statement -> "SELECT task_colors"
                    statement.startsWith("SELECT") && "FROM TASK_STAGES" in statement -> "SELECT task_stages"
                    statement.startsWith("SELECT") && "FROM PROGRESS_EVENTS" in statement -> "SELECT progress_events"
                    statement.startsWith("SELECT") && "FROM CELL_SEGMENTS" in statement -> "SELECT cell_segments"
                    statement.startsWith("SELECT") && "FROM GAME_CELLS" in statement -> "SELECT game_cells"
                    statement.startsWith("SELECT") && "FROM GAMES" in statement -> "SELECT games"
                    statement.startsWith("SELECT") && "FROM TASKS" in statement -> "SELECT tasks"
                    statement.startsWith("SELECT") && "FROM COLORS" in statement -> "SELECT colors"
                    statement.startsWith("SELECT") -> "SELECT other"
                    else -> "other"
                }
            }.eachCount()

    private fun selects(counted: Map<String, Int>): Map<String, Int> = counted.filterKeys { it.startsWith("SELECT") }

    /** [size] games, each with one task of [poolType], some marked and some not. */
    private suspend fun fill(
        poolType: PoolType,
        size: Int,
    ): List<EntityId> {
        val tracking =
            when (poolType) {
                PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
                PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
                PoolType.SPECIAL -> TrackingMode.COUNTED
            }
        val palette = database.colorDao().allColors().map { it.id }
        return (0..<size).map { index ->
            val created =
                insertGameCellAndTask(database, gameName = "Oyun $index", columnType = CellColumnType.of(poolType)) {
                    aTask(poolType = poolType, trackingMode = tracking, name = "Görev $index")
                        // A task is either missing or borrowed, never both, so the
                        // two marks are spread across different rows.
                        .copy(isMissing = index % 5 == 0, isBorrowed = index % 5 != 0 && index % 7 == 0)
                }
            // Some tasks in one colour and some in two, so the multi-colour path
            // is measured as well as the plain one.
            val slots = if (index % 3 == 0) 2 else 1
            (0..<slots).forEach { slot ->
                database.taskColorDao().insert(TaskColorEntity(created.id, palette[(index + slot) % palette.size], slot))
            }
            created.id
        }
    }

    private fun controllerFor(poolType: PoolType): PoolController =
        PoolController(
            poolType = poolType,
            pools = PoolStore(database.poolDao()),
            colors = NoColors(),
            taskEditing = NoEditing(),
            taskProgress = NoProgress(),
        )

    /** One whole reading of a pool, laid out, with the statements it ran. */
    private suspend fun openPool(poolType: PoolType): Pair<PoolController, Map<String, Int>> {
        val controller = controllerFor(poolType)
        driver.start()
        controller.show(PoolStore(database.poolDao()).observePool(poolType).first())
        return controller to ran(driver.stop())
    }

    // --------------------------------------------------- opening does not grow

    @Test
    fun `opening a pool of four hundred and twenty asks exactly what a pool of forty-two asks`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 42)
            val (_, small) = openPool(PoolType.THREE_D)

            fill(PoolType.THREE_D, 378)
            val (_, large) = openPool(PoolType.THREE_D)

            assertEquals(selects(small), selects(large), "a bigger pool asked more questions")
            // The three reads a 3D pool is made of, and no fourth.
            assertEquals(
                mapOf("SELECT tasks" to 1, "SELECT task_colors" to 1, "SELECT progress_events" to 1),
                selects(large),
            )
        }

    @Test
    fun `opening a pipeline pool of four hundred and twenty asks the same four questions`() =
        runBlocking<Unit> {
            fill(PoolType.CARD, 42)
            val (_, small) = openPool(PoolType.CARD)

            fill(PoolType.CARD, 378)
            val (_, large) = openPool(PoolType.CARD)

            assertEquals(selects(small), selects(large))
            // A card pool reads its tasks, its pipeline and what has gone wrong.
            // It never reads colours: PLAN 12.11 gives cards no colours at all,
            // so the store leaves that question out rather than asking it emptily.
            assertEquals(
                mapOf("SELECT tasks" to 1, "SELECT task_stages" to 1, "SELECT progress_events" to 1),
                selects(large),
            )
        }

    @Test
    fun `opening the table of four hundred and twenty asks exactly what forty-two asks`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 42)
            val table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao())
            driver.start()
            table.observeTable().first()
            val small = ran(driver.stop())

            fill(PoolType.THREE_D, 378)
            driver.start()
            table.observeTable().first()
            val large = ran(driver.stop())

            assertEquals(selects(small), selects(large), "a bigger table asked more questions")
            assertEquals(4, selects(large).values.sum(), "the table grew past its four fixed reads")
        }

    // ------------------------------------------- changing a filter asks nothing

    @Test
    fun `every filter change on a full pool runs no statement at all`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 420)
            val (controller, _) = openPool(PoolType.THREE_D)
            val colorId =
                database
                    .colorDao()
                    .allColors()
                    .first()
                    .id

            driver.start()
            // A whole session of narrowing, one change at a time.
            "Görev 1".forEach { character -> controller.search(controller.state.searchText + character) }
            controller.toggleColor(colorId)
            controller.toggleAwaitingColor()
            controller.showState(TaskStateFilter.COMPLETED)
            controller.showState(TaskStateFilter.NEEDS_INFO)
            controller.showState(TaskStateFilter.ACTIVE)
            controller.toggleFlag(TaskFlagFilter.MISSING)
            controller.toggleFlag(TaskFlagFilter.BORROWED)
            controller.toggleShortagesFirst()
            controller.clearSearch()
            controller.clearFilters()
            val counted = ran(driver.stop())

            assertEquals(emptyMap(), counted, "narrowing the pool went back to the database")
        }

    @Test
    fun `choosing a pipeline step runs no statement either`() =
        runBlocking<Unit> {
            fill(PoolType.CARD, 420)
            val (controller, _) = openPool(PoolType.CARD)

            driver.start()
            controller.toggleStage(ProductionStage.PRINT)
            controller.toggleStage(ProductionStage.LAMINATE)
            controller.toggleStage(ProductionStage.PRINT)
            val counted = ran(driver.stop())

            assertEquals(emptyMap(), counted, "choosing a step went back to the database")
        }

    @Test
    fun `the same narrowing costs the same at forty-two and at four hundred and twenty`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 42)
            val (small, _) = openPool(PoolType.THREE_D)
            driver.start()
            small.search("Görev")
            small.showState(TaskStateFilter.COMPLETED)
            val smallCost = ran(driver.stop())

            fill(PoolType.THREE_D, 378)
            val (large, _) = openPool(PoolType.THREE_D)
            driver.start()
            large.search("Görev")
            large.showState(TaskStateFilter.COMPLETED)
            val largeCost = ran(driver.stop())

            assertEquals(smallCost, largeCost)
            assertEquals(emptyMap(), largeCost)
        }

    @Test
    fun `no statement is run per colour of the catalogue`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 42)
            val (controller, _) = openPool(PoolType.THREE_D)
            val palette = database.colorDao().allColors()
            assertTrue(palette.size >= 12, "the seed catalogue is smaller than expected")

            driver.start()
            palette.forEach { controller.toggleColor(it.id) }
            val counted = ran(driver.stop())

            assertEquals(emptyMap(), counted, "a colour cost a query")
        }

    @Test
    fun `narrowing the table runs no statement either`() =
        runBlocking<Unit> {
            fill(PoolType.THREE_D, 420)
            // Read before the measurement starts: the catalogue is the screen's
            // own stream, and asking for it here would be counted as the cost of
            // choosing a colour.
            val palette = database.colorDao().allColors().map { it.id }

            withTable(this) { controller ->
                driver.start()
                "Oyun 1".forEach { character -> controller.search(controller.state.searchText + character) }
                controller.togglePool(PoolType.THREE_D)
                controller.togglePool(PoolType.CARD)
                controller.toggleAwaitingColor()
                palette.forEach { controller.toggleColor(it) }
                controller.clearFilters()
                val counted = ran(driver.stop())

                assertEquals(emptyMap(), counted, "narrowing the table went back to the database")
            }
        }

    /**
     * Runs [block] over a table controller that has collected one whole reading.
     *
     * Collected through the real stream and left running while the block runs,
     * exactly as the screen does it, so what the filter narrows is the same list
     * the screen holds.
     */
    private suspend fun withTable(
        scope: CoroutineScope,
        block: suspend (GameTableController) -> Unit,
    ) {
        val controller =
            GameTableController(
                table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()),
                setup = NoSetup(),
                cells = NoCells(),
                colors = NoColors(),
                taskCreation = NoTaskCreation(),
                taskEditing = NoEditing(),
                taskProgress = NoProgress(),
            )
        val collecting = scope.launch { controller.observeTable() }
        try {
            // Waited for by suspending rather than by spinning: `runBlocking`
            // holds one thread, and a busy loop on it never lets a stream
            // deliver.
            withTimeout(30_000) {
                while (controller.state.rows !is GameTableRowsState.Content) delay(2)
            }
            block(controller)
        } finally {
            // The collection never ends on its own, and a `runBlocking` that
            // waited for it would never return.
            collecting.cancel()
        }
    }
}
