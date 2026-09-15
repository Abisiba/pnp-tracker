package dev.pnptracker.performance

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.deletedAt
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.HistoryStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.data.repository.UnfinishedImportsStore
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.search.GameTableFilter
import dev.pnptracker.domain.search.PoolFilter
import dev.pnptracker.domain.search.SearchQuery
import dev.pnptracker.domain.search.TaskFlagFilter
import dev.pnptracker.domain.search.TaskStateFilter
import dev.pnptracker.domain.search.filterPoolTasks
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.recovery.gateFor
import dev.pnptracker.ui.NoCells
import dev.pnptracker.ui.NoColors
import dev.pnptracker.ui.NoEditing
import dev.pnptracker.ui.NoProgress
import dev.pnptracker.ui.NoSetup
import dev.pnptracker.ui.NoTaskCreation
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.games.GameTableRowsState
import dev.pnptracker.ui.feature.pools.PoolController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

/** How many tasks the large library holds — the scale the backup measurement already uses. */
private const val LARGE = 1_203

/** How many the small one holds, which every query-count test in this repository compares against. */
private const val SMALL = 42

private const val TASKS_PER_GAME = 10

/**
 * PLAN 18 (Faz 3 testleri): "1.000+ görevle açılış, arama ve havuz filtreleme
 * performansı" — measured, not given a time limit.
 *
 * PLAN names no threshold, so no wall-clock or memory figure here passes or
 * fails anything: they are printed as `PERF` lines for the record (master
 * context §29) and depend on the machine. What does pass or fail is what can be
 * proved on any machine:
 *
 * - opening every screen at 1,203 tasks runs the very statements, the very
 *   number of times, it runs at 42 — no read per task, game, cell or colour;
 * - reading again, a first time or a forty-second, runs the same statements and
 *   gets the same answer;
 * - search and every pool filter keep exactly the tasks the data says they
 *   should, worked out here from how the library was built rather than by
 *   calling the code under test a second time, and run no statement at all.
 *
 * The library is built through the application's own write paths — tasks added
 * to cells, colours attached, failures reported, stages moved, tasks converted
 * to text, a game deleted — and opened through the real `StartupGate` in a
 * temporary XDG home, as `Main` opens it.
 */
class LargeLibraryPerformanceTest {
    private lateinit var home: TemporaryDatabaseDirectory
    private lateinit var paths: XdgAppPaths
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openThroughTheGate() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = TemporaryDatabaseDirectory()
        val data = home.root.resolve("data/pnp-tracker")
        val config = home.root.resolve("config/pnp-tracker")
        paths =
            XdgAppPaths(
                data,
                data.resolve("pnp.db"),
                data.resolve("backups"),
                config,
                config.resolve("settings.json"),
                home.root.resolve("state/pnp-tracker"),
                home.root.resolve("state/pnp-tracker/logs"),
            )
        Files.createDirectories(paths.backupsDirectory)
        Files.createDirectories(paths.configDirectory)
        database = gateFor(paths, DatabaseFactory(driver = driver)).open().database
    }

    @AfterTest
    fun closeAndClean() {
        database.close()
        home.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        home.delete()
    }

    // ------------------------------------------------------------ the library

    /** A word a task is named with, and what each search below should make of it. */
    private data class Word(
        val text: String,
        val light: Boolean = false,
        val istanbul: Boolean = false,
        val cafe: Boolean = false,
    )

    private val words =
        listOf(
            Word("Işıklı kule", light = true),
            Word("IŞIK kalkanı", light = true),
            // Shares four letters with `ışık` and is not it.
            Word("ışıltı jetonu"),
            Word("İstanbul kartı", istanbul = true),
            Word("istanbul zarı", istanbul = true),
            // The dotless capital folds to `ı`, so this is not `istanbul`.
            Word("Istanbul haritası"),
            // The accent as a combining mark, and precomposed: one word to a reader.
            Word("Cafe\u0301 masası", cafe = true),
            Word("Café sandalyesi", cafe = true),
            Word("Şövalye 🎲"),
            Word("Ağaç, \"büyük\""),
            Word("Öküz arabası"),
            Word("Üzüm salkımı 👨‍👩‍👧"),
        )

    private val gameWords = listOf("Çiftlik", "Gölge Şatosu", "Ördek, \"küçük\"", "Üçgen Ağ", "Zümrüt 🧩")

    /** One task as it was written, which is what every expectation is worked out from. */
    private data class Planned(
        val id: EntityId,
        val index: Int,
        val game: Int,
        val gameIsLit: Boolean,
        val word: Word,
        val pool: PoolType,
        val colors: List<EntityId>,
        val completed: Boolean,
        val needsInfo: Boolean,
        val missing: Boolean,
        val borrowed: Boolean,
        val converted: Boolean,
    )

    private val planned = mutableListOf<Planned>()
    private val gameIds = mutableMapOf<Int, Pair<EntityId, Map<CellColumnType, EntityId>>>()
    private var deletedGame: Int? = null
    private lateinit var palette: List<EntityId>

    private val pools = listOf(PoolType.THREE_D, PoolType.CARD, PoolType.BOARD, PoolType.SPECIAL)

    private fun trackingOf(pool: PoolType) =
        when (pool) {
            PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
            PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
            PoolType.SPECIAL -> TrackingMode.CHECKLIST
        }

    private suspend fun ensurePalette() {
        if (::palette.isInitialized) return
        val seeded =
            database
                .colorDao()
                .allColors()
                .take(4)
                .map { it.id }
        val custom = ColorEntity.of(IdGenerator.Random.newId(), "Açık Şeftali", "#FFD8B1", 900)
        database.colorDao().insert(custom)
        palette = seeded + custom.id
    }

    private suspend fun gameOf(game: Int): Pair<EntityId, Map<CellColumnType, EntityId>> =
        gameIds.getOrPut(game) {
            // Game names of their own, none of which any search below names — but
            // every ninth game is lit, and a pool's search reads the game's name too.
            val name = if (game % 9 == 0) "Işık Adası $game" else "${gameWords[game % gameWords.size]} $game"
            val row = aGame(name = name)
            database.gameDao().insert(row)
            row.id to
                CellColumnType.entries.associateWith { column ->
                    val cell = aCell(gameId = row.id, columnType = column)
                    database.gameCellDao().insert(cell)
                    cell.id
                }
        }

    /** Builds the library up to [total] tasks, continuing from wherever it stands. */
    private suspend fun libraryOf(total: Int) {
        ensurePalette()
        val clock = StoppedClock(updatedAt)
        for (index in planned.size until total) {
            val game = index / TASKS_PER_GAME
            val (_, cells) = gameOf(game)
            val pool = pools[index % pools.size]
            val word = words[index % words.size]
            val completed = index % 3 == 0
            val needsInfo = index % 13 == 0
            val quantity = if (needsInfo) null else index % 40 + 2
            val colors =
                if (pool != PoolType.THREE_D) {
                    emptyList()
                } else {
                    when (index % 5) {
                        0 -> emptyList()
                        1 -> listOf(palette[0])
                        2 -> listOf(palette[1])
                        3 -> listOf(palette[0], palette[2])
                        else -> listOf(palette[3], palette[1], palette[4])
                    }
                }
            val task =
                aTask(poolType = pool, trackingMode = trackingOf(pool), name = "${word.text} #$index", requiredQuantity = quantity)
                    .copy(
                        notes = if (index % 6 == 0) "not $index, \"tırnaklı\"\nikinci satır" else null,
                        isCompleted = completed,
                        completedAt = if (completed) updatedAt else null,
                        needsInfo = needsInfo,
                        isMissing = index % 10 == 1,
                        isBorrowed = index % 10 == 2,
                    )
            database.taskDao().addTaskToCell(task, cells.getValue(CellColumnType.of(pool)), IdGenerator.Random.newId(), createdAt)
            colors.forEach { database.taskColorDao().addColorToTask(task.id, it) }

            val workable = !completed && quantity != null
            if (pool == PoolType.THREE_D && workable && index % 20 == 8) {
                database.taskProgressDao().reportFailure(IdGenerator.Random.newId(), task.id, 1, clock)
            }
            if ((pool == PoolType.CARD || pool == PoolType.BOARD) && workable && (index % 16 == 1 || index % 16 == 2)) {
                database.taskProgressDao().setStageQuantity(task.id, ProductionStage.PRINT, 1, clock)
            }
            val converted = !completed && index % 17 == 7
            if (converted) database.taskEditDao().convertTaskToText(task.id, clock, IdGenerator.Random)

            planned +=
                Planned(
                    id = task.id,
                    index = index,
                    game = game,
                    gameIsLit = game % 9 == 0,
                    word = word,
                    pool = pool,
                    colors = colors,
                    completed = completed,
                    needsInfo = needsInfo,
                    missing = index % 10 == 1,
                    borrowed = index % 10 == 2,
                    converted = converted,
                )
        }
        // One game the user deleted, once the library is large enough to have it.
        if (total >= LARGE && deletedGame == null) {
            deletedGame = 7
            database.gameDao().softDelete(gameIds.getValue(7).first, deletedAt)
        }
    }

    /** The tasks a pool or the table is expected to hold: not converted, not in a deleted game. */
    private val living: List<Planned> get() = planned.filter { !it.converted && it.game != deletedGame }

    // ------------------------------------------------------------ measuring

    private fun normalised(recorded: List<String>): Map<String, Int> =
        recorded
            .map { it.trim().replace(Regex("\\s+"), " ").uppercase() }
            .filterNot { "ROOM_TABLE_MODIFICATION" in it }
            .filterNot { statement ->
                listOf("PRAGMA", "BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE").any { statement.startsWith(it) }
            }.groupingBy { it }
            .eachCount()

    private class Measured<T>(
        val value: T,
        val statements: Map<String, Int>,
        val nanos: Long,
    )

    private suspend fun <T> measure(read: suspend () -> T): Measured<T> {
        driver.start()
        val started = System.nanoTime()
        val value = read()
        val nanos = System.nanoTime() - started
        return Measured(value, normalised(driver.stop()), nanos)
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        System.gc()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun millis(nanos: Long) = "%.1f ms".format(nanos / 1_000_000.0)

    private fun report(line: String) = println("PERF $line")

    private fun environment() =
        "java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} ${System.getProperty("os.arch")} " +
            "cpus=${Runtime.getRuntime().availableProcessors()} maxHeap=${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB"

    /** Every read a screen starts when it is opened, by the screen that starts it. */
    private fun screens(): List<Pair<String, () -> Flow<*>>> {
        val poolStore = PoolStore(database.poolDao())
        return listOf(
            "sidebar summary" to { poolStore.observeNavigationSummary() },
            "game table" to { GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()).observeTable() },
            "colour catalogue" to { ColorCatalogueStore(database.colorDao()).observeColors() },
        ) +
            pools.map { pool -> "pool $pool" to { poolStore.observePool(pool) } } +
            listOf(
                "history" to { HistoryStore(database.historyDao()).observeHistory() },
                "unfinished imports" to { UnfinishedImportsStore(database, database.importDao()).observeUnfinishedImports() },
                "settled imports" to { ImportRollbackStore(database.importDao()).observeSettledImports() },
            )
    }

    private suspend fun openEveryScreen(): Map<String, Measured<Any?>> =
        screens().associate { (name, flow) -> name to measure { flow().first() } }

    private suspend fun reopenThroughTheGate(): Measured<AppDatabase> {
        database.close()
        return measure { gateFor(paths, DatabaseFactory(driver = driver)).open().database }.also { database = it.value }
    }

    private fun sizeOf(value: Any?): Int =
        when (value) {
            is List<*> -> value.size
            is PoolSnapshot -> value.tasks.size
            is dev.pnptracker.domain.history.HistoryLog -> value.entries.size
            is dev.pnptracker.domain.pools.PoolNavigationSummary -> value.counts.values.sumOf { it.taskCount }
            else -> -1
        }

    // ------------------------------------------------------------- opening

    @Test
    fun `opening every screen at a thousand tasks runs exactly what it runs at forty-two`() =
        runBlocking<Unit> {
            report(environment())

            libraryOf(SMALL)
            val smallGate = reopenThroughTheGate()
            val small = openEveryScreen()

            libraryOf(LARGE)
            val heapBefore = usedHeap()
            val largeGate = reopenThroughTheGate()
            val large = openEveryScreen()
            val heapHolding = usedHeap()

            assertEquals(smallGate.statements, largeGate.statements, "opening the database asked more of a larger one")
            // Room's two checks of its own table, and the one count the gate runs
            // so that a migration would happen behind the gate and not behind the
            // first screen. Nothing reads a task.
            assertEquals(
                mapOf(
                    "SELECT 1 FROM SQLITE_MASTER WHERE TYPE = 'TABLE' AND NAME = 'ROOM_MASTER_TABLE'" to 1,
                    "SELECT IDENTITY_HASH FROM ROOM_MASTER_TABLE WHERE ID = 42 LIMIT 1" to 1,
                    "SELECT COUNT(*) FROM GAMES WHERE DELETED_AT IS NULL" to 1,
                ),
                largeGate.statements,
            )
            small.keys.forEach { screen ->
                assertEquals(
                    small.getValue(screen).statements,
                    large.getValue(screen).statements,
                    "opening $screen asked more of a larger library",
                )
                assertTrue(
                    large
                        .getValue(screen)
                        .statements.values
                        .all { it == 1 },
                    "opening $screen ran one statement more than once: ${large.getValue(screen).statements}",
                )
            }
            val total = large.values.sumOf { it.statements.values.sum() }
            assertEquals(small.values.sumOf { it.statements.values.sum() }, total)

            // What came back is the library, not a sample of it.
            val summary = large.getValue("sidebar summary").value as dev.pnptracker.domain.pools.PoolNavigationSummary
            pools.forEach { pool ->
                val held = living.filter { it.pool == pool }
                val snapshot = large.getValue("pool $pool").value as PoolSnapshot
                assertEquals(held.map { it.id }.toSet(), snapshot.tasks.map { it.taskId }.toSet(), "the $pool pool is not its tasks")
                assertEquals(held.size, summary.counts.getValue(pool).taskCount)
                assertEquals(held.count { !it.completed && !it.needsInfo }, summary.counts.getValue(pool).activeCount)
            }
            @Suppress("UNCHECKED_CAST")
            val table = large.getValue("game table").value as List<GameTableRow>
            assertEquals(gameIds.size - 1, table.size, "the table is not every game but the deleted one")
            assertEquals(living.size, table.sumOf { row -> row.cells.sumOf { cell -> cell.segments.count { it.isTask } } })
            assertTrue(living.size > 1_000, "the library is not past a thousand tasks: ${living.size}")

            report("library: ${planned.size} tasks written, ${living.size} living, ${gameIds.size} games (1 deleted)")
            report(
                "gate reopen: 42 → ${millis(
                    smallGate.nanos,
                )}, 1203 → ${millis(largeGate.nanos)}, statements ${largeGate.statements.values.sum()}",
            )
            large.forEach { (screen, measured) ->
                report(
                    "open $screen: statements ${measured.statements.values.sum()}, rows ${sizeOf(measured.value)}, " +
                        "42 → ${millis(small.getValue(screen).nanos)}, 1203 → ${millis(measured.nanos)}",
                )
            }
            report(
                "opening statements in total: $total; heap held by all readings ≈ ${(heapHolding - heapBefore) / 1024} KiB (approximate)",
            )
        }

    @Test
    fun `reading again, the first time or the forty-second, asks the same and answers the same`() =
        runBlocking<Unit> {
            libraryOf(LARGE)
            val first = openEveryScreen()
            val durations = mutableMapOf<String, MutableList<Long>>()

            repeat(41) {
                val again = openEveryScreen()
                again.forEach { (screen, measured) ->
                    assertEquals(first.getValue(screen).statements, measured.statements, "reading $screen again asked something new")
                    assertEquals(first.getValue(screen).value, measured.value, "reading $screen again answered differently")
                    durations.getOrPut(screen) { mutableListOf() } += measured.nanos
                }
            }

            durations.forEach { (screen, runs) ->
                val sorted = runs.sorted()
                report(
                    "reread $screen ×41 at 1203: first ${millis(first.getValue(screen).nanos)}, " +
                        "median ${millis(sorted[sorted.size / 2])}, max ${millis(sorted.last())}",
                )
            }
        }

    // -------------------------------------------------------------- search

    @Test
    fun `search at a thousand tasks keeps exactly the tasks its words name, and asks nothing`() =
        runBlocking<Unit> {
            libraryOf(LARGE)
            val poolStore = PoolStore(database.poolDao())
            val snapshots = pools.associateWith { poolStore.observePool(it).first() }
            val table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()).observeTable().first()
            val gameNames = table.associate { it.gameId to it.gameName }

            // The words searched, which tasks they name, and whether they name the lit games.
            val searches: List<Triple<String, (Planned) -> Boolean, Boolean>> =
                listOf(
                    Triple("ışık", { it.word.light }, true),
                    Triple("IŞIK", { it.word.light }, true),
                    Triple("istanbul", { it.word.istanbul }, false),
                    Triple("İSTANBUL", { it.word.istanbul }, false),
                    Triple("café", { it.word.cafe }, false),
                    Triple("cafe\u0301", { it.word.cafe }, false),
                    Triple("yok böyle bir şey", { false }, false),
                )

            driver.start()
            val started = System.nanoTime()
            searches.forEach { (text, named, namesLitGames) ->
                val query = SearchQuery(text)
                pools.forEach { pool ->
                    val kept =
                        filterPoolTasks(snapshots.getValue(pool).tasks, PoolFilter.NONE.copy(query = query, state = TaskStateFilter.ACTIVE))
                    // A pool searches the task's name and its game's name.
                    val expected =
                        living
                            .filter { it.pool == pool && !it.completed && !it.needsInfo && (named(it) || (namesLitGames && it.gameIsLit)) }
                            .map { it.id }
                            .toSet()
                    assertEquals(expected, kept.map { it.taskId }.toSet(), "searching `$text` in $pool")
                }
                val keptGames = table.filter(GameTableFilter(query = query)::matches).map { it.gameId }.toSet()
                val expectedGames =
                    gameIds
                        .filterKeys { it != deletedGame }
                        .filter { (game, _) ->
                            (namesLitGames && game % 9 == 0) || living.any { it.game == game && named(it) }
                        }.map { it.value.first }
                        .toSet()
                assertEquals(expectedGames, keptGames, "searching the table for `$text`: ${keptGames.map { gameNames[it] }}")
            }
            val nanos = System.nanoTime() - started
            assertEquals(emptyMap(), normalised(driver.stop()), "a search went back to the database")

            val controller = PoolController(PoolType.THREE_D, poolStore, NoColors(), NoEditing(), NoProgress())
            controller.show(snapshots.getValue(PoolType.THREE_D))
            val tableController =
                GameTableController(
                    GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()),
                    NoSetup(),
                    NoCells(),
                    NoColors(),
                    NoTaskCreation(),
                    NoEditing(),
                    NoProgress(),
                )
            val collecting = launch { tableController.observeTable() }
            try {
                withTimeout(30_000) { while (tableController.state.rows !is GameTableRowsState.Content) delay(2) }
                driver.start()
                val typed = System.nanoTime()
                "Işıklı kule".forEach { character ->
                    controller.search(controller.state.searchText + character)
                    tableController.search(tableController.state.searchText + character)
                }
                val typingNanos = System.nanoTime() - typed
                assertEquals(emptyMap(), normalised(driver.stop()), "typing a search went back to the database")
                report(
                    "search: 7 queries × 4 pools + table at 1203 → ${millis(
                        nanos,
                    )}; typing 11 keys into a pool and the table → ${millis(typingNanos)}",
                )
            } finally {
                collecting.cancel()
            }
        }

    // ---------------------------------------------------------- pool filters

    @Test
    fun `every pool filter at a thousand tasks keeps exactly the tasks it describes, and asks nothing`() =
        runBlocking<Unit> {
            libraryOf(LARGE)
            val poolStore = PoolStore(database.poolDao())
            val snapshots = pools.associateWith { poolStore.observePool(it).first() }

            fun stateOf(task: Planned) =
                when {
                    task.needsInfo -> TaskStateFilter.NEEDS_INFO
                    task.completed -> TaskStateFilter.COMPLETED
                    else -> TaskStateFilter.ACTIVE
                }

            val filters: List<Triple<PoolType, PoolFilter, (Planned) -> Boolean>> =
                pools.flatMap { pool ->
                    TaskStateFilter.entries.map { state ->
                        Triple(pool, PoolFilter(state = state), { task: Planned -> stateOf(task) == state })
                    }
                } +
                    palette.map { color ->
                        Triple(PoolType.THREE_D, PoolFilter(colorIds = setOf(color)), { task: Planned ->
                            color in task.colors &&
                                stateOf(task) == TaskStateFilter.ACTIVE
                        })
                    } +
                    listOf(
                        Triple(PoolType.THREE_D, PoolFilter(awaitingColor = true), { t: Planned ->
                            t.colors.isEmpty() &&
                                stateOf(t) == TaskStateFilter.ACTIVE
                        }),
                        Triple(
                            PoolType.THREE_D,
                            PoolFilter(colorIds = setOf(palette[4]), awaitingColor = true),
                            { t: Planned -> (t.colors.isEmpty() || palette[4] in t.colors) && stateOf(t) == TaskStateFilter.ACTIVE },
                        ),
                        Triple(PoolType.THREE_D, PoolFilter(flags = setOf(TaskFlagFilter.MISSING)), { t: Planned ->
                            t.missing &&
                                stateOf(t) == TaskStateFilter.ACTIVE
                        }),
                        Triple(
                            PoolType.CARD,
                            PoolFilter(flags = setOf(TaskFlagFilter.MISSING, TaskFlagFilter.BORROWED), state = TaskStateFilter.COMPLETED),
                            { t: Planned -> (t.missing || t.borrowed) && stateOf(t) == TaskStateFilter.COMPLETED },
                        ),
                        // No step has been finished on any card, so every card is still at its first.
                        Triple(PoolType.CARD, PoolFilter(stages = setOf(ProductionStage.PRINT)), { t: Planned ->
                            stateOf(t) ==
                                TaskStateFilter.ACTIVE
                        }),
                        Triple(PoolType.CARD, PoolFilter(stages = setOf(ProductionStage.LAMINATE)), { _: Planned -> false }),
                        Triple(
                            PoolType.THREE_D,
                            PoolFilter(query = SearchQuery("ışık"), colorIds = setOf(palette[0]), state = TaskStateFilter.COMPLETED),
                            { t: Planned ->
                                (t.word.light || t.gameIsLit) &&
                                    palette[0] in t.colors &&
                                    stateOf(t) == TaskStateFilter.COMPLETED
                            },
                        ),
                    )

            driver.start()
            val started = System.nanoTime()
            var kept = 0
            filters.forEach { (pool, filter, describes) ->
                val result = filterPoolTasks(snapshots.getValue(pool).tasks, filter).map { it.taskId }.toSet()
                val expected = living.filter { it.pool == pool && describes(it) }.map { it.id }.toSet()
                assertEquals(expected, result, "$pool under $filter")
                kept += result.size
            }
            val nanos = System.nanoTime() - started
            assertEquals(emptyMap(), normalised(driver.stop()), "a pool filter went back to the database")
            assertTrue(kept > 0 && filters.size > 20)

            val controller = PoolController(PoolType.CARD, poolStore, NoColors(), NoEditing(), NoProgress())
            controller.show(snapshots.getValue(PoolType.CARD))
            driver.start()
            controller.showState(TaskStateFilter.COMPLETED)
            controller.toggleFlag(TaskFlagFilter.MISSING)
            controller.toggleStage(ProductionStage.PRINT)
            controller.toggleShortagesFirst()
            controller.clearFilters()
            assertEquals(emptyMap(), normalised(driver.stop()), "changing a pool filter on screen went back to the database")

            report("pool filters: ${filters.size} filters over ${living.size} living tasks → ${millis(nanos)}, $kept tasks kept in total")
        }
}
