package dev.pnptracker.data.repository

import androidx.room3.useWriterConnection
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.ExportInvariant
import dev.pnptracker.domain.export.ExportedTask
import dev.pnptracker.domain.export.TaskExportException
import dev.pnptracker.domain.export.TaskExportStatus
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.search.PoolFilter
import dev.pnptracker.domain.search.SearchQuery
import dev.pnptracker.domain.search.TaskStateFilter
import dev.pnptracker.domain.search.filterPoolTasks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a real database hands the export, and what it costs to ask.
 *
 * The reads are the point. A file assembled from many separate reads would
 * describe several different moments and belong to none of them, and a read per
 * task would make a library of a thousand unusable; both are checked here rather
 * than assumed, against real Room and a real SQLite driver.
 */
class TaskExportStoreTest {
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

    private val store get() = TaskExportStore(database.taskExportDao())

    private var madeColors = 0

    /** A colour from the seeded catalogue, or a new one under a name nobody uses. */
    private suspend fun aColor(name: String): EntityId {
        database
            .colorDao()
            .allColors()
            .firstOrNull { it.canonicalName == name }
            ?.let { return it.id }
        val at = madeColors++
        val color = ColorEntity.of(IdGenerator.Random.newId(), name, "#%06X".format(at * 1000 + 1), 100 + at)
        database.colorDao().insert(color)
        return color.id
    }

    private suspend fun aGameWithCells(name: String): Map<CellColumnType, EntityId> {
        val game = aGame(name = name)
        database.gameDao().insert(game)
        return CellColumnType.entries.associateWith { column ->
            val cell = aCell(gameId = game.id, columnType = column)
            database.gameCellDao().insert(cell)
            cell.id
        }
    }

    private suspend fun aTaskIn(
        cellId: EntityId,
        name: String,
        poolType: PoolType = PoolType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        quantity: Int? = 12,
        notes: String? = null,
        isCompleted: Boolean = false,
        needsInfo: Boolean = false,
        needsClassification: Boolean = false,
        colorIds: List<EntityId> = emptyList(),
    ): EntityId {
        val task =
            aTask(poolType = poolType, trackingMode = trackingMode, name = name, requiredQuantity = quantity)
                .copy(
                    notes = notes,
                    isCompleted = isCompleted,
                    // PLAN 6.4: a task is finished exactly when it has a time it
                    // was finished at, and the row will not be written otherwise.
                    completedAt = if (isCompleted) updatedAt else null,
                    needsInfo = needsInfo,
                    needsClassification = needsClassification,
                )
        database.taskDao().addTaskToCell(task, cellId, IdGenerator.Random.newId(), createdAt)
        colorIds.forEach { database.taskColorDao().addColorToTask(task.id, it) }
        return task.id
    }

    private suspend fun exported(): List<ExportedTask> = store.exportedTasks()

    private suspend fun refusal(): TaskExportException = assertFailsWith { store.exportedTasks() }

    // ------------------------------------------------------- everything reads

    @Test
    fun `every column comes back from a real database`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            val red = aColor("Kırmızı")
            aTaskIn(
                cells.getValue(CellColumnType.THREE_D),
                name = "Kırmızı ev",
                quantity = 12,
                notes = "iki\nsatır",
                colorIds = listOf(red),
            )

            val task = exported().single()

            assertEquals("Harmonies", task.gameName)
            assertEquals(CellColumnType.THREE_D, task.columnType)
            assertEquals("Kırmızı ev", task.taskName)
            assertEquals(PoolType.THREE_D, task.poolType)
            assertEquals(listOf("Kırmızı"), task.colorNames)
            assertEquals(12, task.requiredQuantity)
            assertEquals(TaskExportStatus.OPEN, task.status)
            assertEquals("iki\nsatır", task.notes)
        }

    @Test
    fun `open, finished and waiting tasks all come back together`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            val threeD = cells.getValue(CellColumnType.THREE_D)
            aTaskIn(threeD, name = "Açık")
            aTaskIn(threeD, name = "Biten", isCompleted = true)
            aTaskIn(threeD, name = "Eksik bilgi", needsInfo = true, quantity = null)

            val statuses = exported().associate { it.taskName to it.status }

            assertEquals(TaskExportStatus.OPEN, statuses["Açık"])
            assertEquals(TaskExportStatus.COMPLETED, statuses["Biten"])
            assertEquals(TaskExportStatus.NEEDS_INFO, statuses["Eksik bilgi"])
        }

    @Test
    fun `a task the user classified by hand is open like any other`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            aTaskIn(cells.getValue(CellColumnType.THREE_D), name = "Sınıflandırılmış", needsClassification = true)

            assertEquals(TaskExportStatus.OPEN, exported().single().status)
        }

    @Test
    fun `a deleted task is not written out`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            val threeD = cells.getValue(CellColumnType.THREE_D)
            aTaskIn(threeD, name = "Kalan")
            val gone = aTaskIn(threeD, name = "Silinen")
            database.taskDao().softDelete(gone, updatedAt)

            assertEquals(listOf("Kalan"), exported().map { it.taskName })
        }

    @Test
    fun `the colours come back in the slots the user gave them`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Root")
            val red = aColor("Kırmızı")
            val yellow = aColor("Sarı")
            val black = aColor("Siyah")
            aTaskIn(cells.getValue(CellColumnType.THREE_D), name = "Yarasa", quantity = 10, colorIds = listOf(red, yellow, black))

            val task = exported().single()

            assertEquals(listOf("Kırmızı", "Sarı", "Siyah"), task.colorNames)
            assertEquals(10, task.requiredQuantity)
        }

    @Test
    fun `a task in every pool comes back with its own column`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            aTaskIn(cells.getValue(CellColumnType.THREE_D), "Ev", PoolType.THREE_D, TrackingMode.THREE_D_BATCH)
            aTaskIn(cells.getValue(CellColumnType.CARD), "Deste", PoolType.CARD, TrackingMode.PIPELINE)
            aTaskIn(cells.getValue(CellColumnType.BOARD), "Jeton", PoolType.BOARD, TrackingMode.PIPELINE)
            aTaskIn(cells.getValue(CellColumnType.SPECIAL), "Kutu", PoolType.SPECIAL, TrackingMode.CHECKLIST)

            val byName = exported().associateBy { it.taskName }

            assertEquals(CellColumnType.THREE_D, byName.getValue("Ev").columnType)
            assertEquals(CellColumnType.CARD, byName.getValue("Deste").columnType)
            assertEquals(CellColumnType.BOARD, byName.getValue("Jeton").columnType)
            assertEquals(CellColumnType.SPECIAL, byName.getValue("Kutu").columnType)
        }

    // ------------------------------------------------------------ the order

    @Test
    fun `games come in the table's own order, and columns in the table's own order`() =
        runBlocking<Unit> {
            val second = aGameWithCells("Wingspan")
            val first = aGameWithCells("Harmonies")
            aTaskIn(second.getValue(CellColumnType.CARD), "Yuva", PoolType.CARD, TrackingMode.PIPELINE)
            aTaskIn(first.getValue(CellColumnType.CARD), "Deste", PoolType.CARD, TrackingMode.PIPELINE)
            aTaskIn(first.getValue(CellColumnType.THREE_D), "Ev")

            assertEquals(
                listOf("Harmonies" to "Ev", "Harmonies" to "Deste", "Wingspan" to "Yuva"),
                exported().map { it.gameName to it.taskName },
            )
        }

    @Test
    fun `tasks in one cell come in the order they are written in it`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            val threeD = cells.getValue(CellColumnType.THREE_D)
            aTaskIn(threeD, name = "Önce")
            aTaskIn(threeD, name = "Sonra")
            aTaskIn(threeD, name = "En sonra")

            assertEquals(listOf("Önce", "Sonra", "En sonra"), exported().map { it.taskName })
        }

    @Test
    fun `the same database always gives the same order`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            repeat(12) { at -> aTaskIn(cells.getValue(CellColumnType.THREE_D), name = "Parça $at") }

            assertEquals(exported().map { it.taskName }, exported().map { it.taskName })
        }

    // ------------------------------------------------------------ what refuses

    @Test
    fun `a task with no segment at all stops the export`() =
        runBlocking<Unit> {
            // Written straight into the table: the application has no way to make
            // one, which is exactly why the export has to cope with finding one.
            database.taskDao().insert(aTask(name = "Hücresiz"))

            val refused = refusal()

            assertEquals(ExportFailure.BROKEN_DATA, refused.failure)
            assertEquals(ExportInvariant.TASK_WITHOUT_SEGMENT, refused.invariant)
        }

    @Test
    fun `the database itself will not let a task be written in two cells`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            val taskId = aTaskIn(cells.getValue(CellColumnType.THREE_D), name = "İki yerde")
            val elsewhere = aGameWithCells("Wingspan").getValue(CellColumnType.THREE_D)

            // The export copes with finding one anyway — `TaskExportSnapshotTest`
            // covers that — but the shape cannot be made through SQL either, and
            // that is worth knowing: `cell_segments.task_id` is unique.
            val refused =
                assertFailsWith<Exception> {
                    database.useWriterConnection { transactor ->
                        transactor.usePrepared(
                            "INSERT INTO cell_segments " +
                                "(id, cell_id, order_index, kind, text, task_id, created_at, updated_at) " +
                                "VALUES (?, ?, 0, 'TASK', NULL, ?, 0, 0)",
                        ) { statement ->
                            statement.bindText(1, IdGenerator.Random.newId().toString())
                            statement.bindText(2, elsewhere.toString())
                            statement.bindText(3, taskId.toString())
                            statement.step()
                        }
                    }
                }

            assertTrue("UNIQUE" in refused.message.orEmpty(), "a task could be written in two cells: ${refused.message}")
            assertEquals(1, exported().size)
        }

    @Test
    fun `nothing to write is said plainly`() =
        runBlocking<Unit> {
            aGameWithCells("Boş oyun")

            val refused = refusal()

            assertEquals(ExportFailure.NOTHING_TO_EXPORT, refused.failure)
        }

    // ------------------------------------------------------- what it costs

    private fun ran(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .map { it.trimStart().uppercase().replace(Regex("\\s+"), " ") }
            .filterNot { it.startsWith("BEGIN") || it.startsWith("COMMIT") || it.startsWith("END") }
            .filterNot { it.startsWith("ROLLBACK") || it.startsWith("SAVEPOINT") || it.startsWith("RELEASE") }
            .filterNot { it.startsWith("PRAGMA") }
            .groupingBy { statement ->
                when {
                    "CHANGES()" in statement || "LAST_INSERT_ROWID()" in statement -> "write result"
                    statement.startsWith("SELECT") && "FROM TASK_COLORS" in statement -> "SELECT task_colors"
                    statement.startsWith("SELECT") && "FROM TASKS" in statement -> "SELECT tasks"
                    statement.startsWith("SELECT") -> "SELECT other"
                    statement.startsWith("INSERT") -> "INSERT"
                    statement.startsWith("UPDATE") -> "UPDATE"
                    statement.startsWith("DELETE") -> "DELETE"
                    else -> "other"
                }
            }.eachCount()

    private suspend fun costOfExporting(expected: Int): Map<String, Int> {
        // Opening the connection is the database waking up, not a question the
        // export asked; it happens before the counting.
        database.taskExportDao().exportRows()
        driver.start()
        val tasks = exported()
        val counted = ran(driver.stop())
        assertEquals(expected, tasks.size)
        return counted
    }

    private suspend fun aLibrary(
        games: Int,
        tasksPerGame: Int,
        colorsPerTask: Int = 0,
    ) {
        val palette = (0 until colorsPerTask).map { aColor("Ton $it") }
        repeat(games) { game ->
            val cells = aGameWithCells("Oyun %03d".format(game))
            repeat(tasksPerGame) { at ->
                aTaskIn(
                    cells.getValue(CellColumnType.THREE_D),
                    name = "Parça $game-$at",
                    colorIds = palette,
                )
            }
        }
    }

    @Test
    fun `one task and forty-two ask exactly the same questions`() =
        runBlocking<Unit> {
            aLibrary(games = 1, tasksPerGame = 1)
            val one = costOfExporting(expected = 1)

            aLibrary(games = 41, tasksPerGame = 1)
            val fortyTwo = costOfExporting(expected = 42)

            assertEquals(one, fortyTwo, "the cost of exporting grew with the number of tasks")
            assertEquals(mapOf("SELECT tasks" to 1, "SELECT task_colors" to 1), fortyTwo)
        }

    @Test
    fun `a thousand tasks ask the same questions as forty-two`() =
        runBlocking<Unit> {
            aLibrary(games = 42, tasksPerGame = 1)
            val fortyTwo = costOfExporting(expected = 42)

            aLibrary(games = 8, tasksPerGame = 130)
            val manyMore = costOfExporting(expected = 42 + 1040)

            assertEquals(fortyTwo, manyMore)
        }

    @Test
    fun `colours do not add questions`() =
        runBlocking<Unit> {
            aLibrary(games = 5, tasksPerGame = 2)
            val plain = costOfExporting(expected = 10)

            aLibrary(games = 5, tasksPerGame = 2, colorsPerTask = 3)
            val coloured = costOfExporting(expected = 20)

            assertEquals(plain, coloured)
        }

    @Test
    fun `exporting writes nothing at all`() =
        runBlocking<Unit> {
            aLibrary(games = 3, tasksPerGame = 3, colorsPerTask = 2)

            val counted = costOfExporting(expected = 9)

            assertTrue(
                counted.keys.none { it.startsWith("INSERT") || it.startsWith("UPDATE") || it.startsWith("DELETE") },
                "exporting changed the database: $counted",
            )
        }

    @Test
    fun `both reads happen inside one transaction`() =
        runBlocking<Unit> {
            aLibrary(games = 2, tasksPerGame = 2)
            database.taskExportDao().exportRows()

            driver.start()
            exported()
            val recorded =
                driver
                    .stop()
                    .map { it.trimStart().uppercase().replace(Regex("\\s+"), " ") }
                    // Room keeps its own invalidation log in step in a transaction
                    // of its own; that is the library's bookkeeping, not a read
                    // this export asked for.
                    .filterNot { "ROOM_TABLE_MODIFICATION" in it }

            val begin = recorded.indexOfFirst { it.startsWith("BEGIN") }
            val end = recorded.indexOfFirst { it.startsWith("END") || it.startsWith("COMMIT") }
            val inside = recorded.subList(begin + 1, end)
            assertEquals(2, inside.size, "the two reads were not the only thing in the transaction: $inside")
            assertTrue(inside.all { it.startsWith("SELECT") }, "something other than a read happened: $inside")
            assertTrue(inside.any { "FROM TASKS" in it } && inside.any { "FROM TASK_COLORS" in it })
        }

    @Test
    fun `the game table still costs its four fixed statements`() =
        runBlocking<Unit> {
            aLibrary(games = 20, tasksPerGame = 3, colorsPerTask = 2)
            val table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao())
            table.observeTable().first()

            driver.start()
            table.observeTable().first()
            val counted = ran(driver.stop())

            assertEquals(4, counted.values.sum(), "the table stopped costing four fixed statements: $counted")
        }

    @Test
    fun `narrowing a pool still costs nothing, with the exporter in the build`() =
        runBlocking<Unit> {
            aLibrary(games = 6, tasksPerGame = 7, colorsPerTask = 2)
            val pools = PoolStore(database.poolDao())
            val snapshot = pools.observePool(PoolType.THREE_D).first()

            driver.start()
            // Every narrowing PLAN 13 offers, over the tasks already in hand.
            listOf(
                PoolFilter.NONE,
                PoolFilter.NONE.copy(query = SearchQuery("parça")),
                PoolFilter.NONE.copy(state = TaskStateFilter.COMPLETED),
                PoolFilter.NONE.copy(awaitingColor = true),
            ).forEach { filter -> filterPoolTasks(snapshot.tasks, filter) }
            val counted = ran(driver.stop())

            assertEquals(emptyMap(), counted, "narrowing a pool ran statements: $counted")
        }

    @Test
    fun `the file describes the moment it was read, and not two moments`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            val threeD = cells.getValue(CellColumnType.THREE_D)
            aTaskIn(threeD, name = "Vardı")

            val snapshot = exported()
            // Whatever happens now happens after the reading, and the file being
            // built from that reading cannot half-notice it.
            aTaskIn(threeD, name = "Sonradan geldi")

            assertEquals(listOf("Vardı"), snapshot.map { it.taskName })
            assertEquals(listOf("Vardı", "Sonradan geldi"), exported().map { it.taskName })
        }
}
