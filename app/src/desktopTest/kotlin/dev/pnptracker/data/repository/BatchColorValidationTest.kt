package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What creating a batch of tasks actually asks the database.
 *
 * Measured at the driver rather than at the DAO: a test that counted its own
 * calls would only prove what it did, and the thing worth pinning is what SQLite
 * is really asked to prepare inside one transaction. The reads that decide
 * whether a batch may be written have to cost the same whether it makes two
 * tasks or twenty-five — PLAN puts no ceiling on how many colours a thing comes
 * in, so a check made once per row is a cost that grows with somebody's real
 * note.
 *
 * The writes are a different matter and are deliberately not pinned: N tasks
 * genuinely are N rows, N colour relations, N pipelines and N pieces of a cell,
 * and folding those into one statement would be folding the tasks together.
 */
class BatchColorValidationTest {
    /** One database, its store and the driver watching it, for one measurement. */
    private class Bench(
        val database: AppDatabase,
        val store: TaskFromTextStore,
        val driver: CountingSqliteDriver,
    )

    /**
     * Runs [work] against a database of its own and hands back what it prepared.
     *
     * A database of its own because Room keeps prepared statements: running the
     * same transaction twice against one database prepares nothing the second
     * time, so two measurements taken in a row would be comparing a cold run
     * against a warm one. Each starts cold, which is the only way two numbers
     * mean the same thing.
     */
    private fun statementsOf(work: suspend Bench.() -> Unit): List<String> {
        val existedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        val directory = TemporaryDatabaseDirectory()
        val driver = CountingSqliteDriver()
        val database = DatabaseFactory(driver = driver).open(directory.databaseFile)
        try {
            val store = TaskFromTextStore(database.taskFromTextDao(), IdGenerator.Random, StoppedClock(updatedAt))
            return runBlocking {
                Bench(database, store, driver).work()
                driver.stop()
            }
        } finally {
            database.close()
            directory.assertRealApplicationDatabaseUntouched(existedBefore)
            directory.delete()
        }
    }

    // ------------------------------------------------------------- fixtures

    private suspend fun AppDatabase.addGame(name: String): GameEntity {
        val game = aGame(name = name)
        gameDao().insert(game)
        return game
    }

    private suspend fun AppDatabase.addCell(gameId: EntityId): GameCellEntity {
        val cell = aCell(gameId = gameId, columnType = CellColumnType.THREE_D)
        gameCellDao().insert(cell)
        return cell
    }

    private suspend fun AppDatabase.addText(
        cellId: EntityId,
        text: String,
    ): CellSegmentEntity {
        val segment =
            CellSegmentEntity.plainText(
                id = IdGenerator.Random.newId(),
                cellId = cellId,
                orderIndex = 0,
                text = text,
                moment = createdAt,
            )
        insertSegmentDirectly(this, segment)
        return segment
    }

    /**
     * [count] colours the catalogue really holds.
     *
     * The twelve it is seeded with, and as many more as a case needs, written
     * the way any other colour is: PLAN 5.7 makes a colour a named record, so
     * none of these is invented in the test's head.
     */
    private suspend fun AppDatabase.colors(count: Int): List<ColorEntity> {
        val seeded = colorDao().allColors()
        val extra =
            (seeded.size until count).map { index ->
                ColorEntity(
                    id = IdGenerator.Random.newId(),
                    canonicalName = "Deneme $index",
                    normalizedName = "deneme $index",
                    hex = "#%06X".format(index * 977 % 0xFFFFFF),
                    sortOrder = 100 + index,
                )
            }
        extra.forEach { colorDao().insert(it) }
        return (seeded + extra).take(count)
    }

    private fun selectionOf(
        game: GameEntity,
        cell: GameCellEntity,
        segment: CellSegmentEntity,
        word: String,
    ): CellTextSelection {
        val text = segment.text.orEmpty()
        return CellTextSelection(
            gameId = game.id,
            cellId = cell.id,
            segmentId = segment.id,
            expectedText = text,
            startOffset = text.indexOf(word),
            endOffset = text.indexOf(word) + word.length,
        )
    }

    private fun draftsOf(colors: List<ColorEntity>) =
        colors.mapIndexed { index, color ->
            TaskDraft(
                colorId = color.id,
                requiredQuantity = index + 1,
                trackingMode = TrackingMode.THREE_D_BATCH,
                notes = null,
            )
        }

    /** The statements one batch of [size] tasks prepared, from one selection. */
    private fun statementsOfBatch(size: Int): List<String> =
        statementsOf {
            val game = database.addGame("Oyun $size")
            val cell = database.addCell(game.id)
            val segment = database.addText(cell.id, "Basılacak: Token, kutu ayrı.")
            val drafts = draftsOf(database.colors(size))

            driver.start()
            store.createTasks(selectionOf(game, cell, segment, "Token"), drafts)
        }

    /** The statements that read the colour catalogue, however they are written. */
    private fun List<String>.colorReads() = reads().filter { "colors" in it && "task_colors" !in it }

    /**
     * The reads the transaction makes to decide whether it may write.
     *
     * Room's own invalidation bookkeeping is left out: it runs on its own
     * schedule rather than as part of this transaction, so whether it lands
     * inside a measurement is a matter of timing and says nothing about the
     * batch. So is `changes()`, which is how a write reports the rows it
     * touched — part of writing, and writing is allowed to grow.
     */
    private fun List<String>.reads() =
        filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
            .filterNot { "room_" in it || "changes()" in it }

    // ------------------------------------------- what a batch costs to check

    @Test
    fun `a batch of two asks the colours once`() {
        val reads = statementsOfBatch(size = 2).colorReads()

        assertEquals(1, reads.size, "the colours were read more than once: $reads")
    }

    @Test
    fun `a batch of twenty five asks the colours once`() {
        val reads = statementsOfBatch(size = 25).colorReads()

        assertEquals(1, reads.size, "the colours were read ${reads.size} times for 25 tasks")
    }

    @Test
    fun `the colour check is one statement whatever the batch is`() {
        assertEquals(
            statementsOfBatch(size = 2).colorReads(),
            statementsOfBatch(size = 25).colorReads(),
            "a bigger batch checked its colours differently",
        )
    }

    @Test
    fun `checking a batch costs the same however many tasks it makes`() {
        // The whole point. Every read the transaction makes to decide whether it
        // may write is a fixed cost; only the writing grows, because N tasks
        // really are N rows.
        val two = statementsOfBatch(size = 2).reads()
        val twentyFive = statementsOfBatch(size = 25).reads()

        assertEquals(two, twentyFive, "a bigger batch read more than a smaller one")
    }

    @Test
    fun `making one task reads the colours the same single way`() {
        // One mode, one transaction, one shape of check: the single colour path
        // is the batch path with one row and must not grow a check of its own.
        val single =
            statementsOf {
                val game = database.addGame("Tek")
                val cell = database.addCell(game.id)
                val segment = database.addText(cell.id, "Basılacak: Token, kutu ayrı.")
                val color = database.colors(1).single()

                driver.start()
                store.createSingleColorTask(
                    selection = selectionOf(game, cell, segment, "Token"),
                    colorId = color.id,
                    requiredQuantity = 14,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    notes = null,
                )
            }

        assertEquals(1, single.colorReads().size)
        assertEquals(statementsOfBatch(size = 1).reads(), single.reads(), "one task took a path of its own")
    }

    // -------------------------------------------- and what it still refuses

    @Test
    fun `a colour deleted from the last row leaves nothing behind at all`() {
        statementsOf {
            val game = database.addGame("Silinen")
            val cell = database.addCell(game.id)
            val segment = database.addText(cell.id, "Basılacak: Token, kutu ayrı.")
            val chosen = database.colors(3)
            val before = database.cellSegmentDao().segmentsOfCell(cell.id)
            // Physically, because PLAN 5.2 makes colour the one exception to the
            // tombstone rule: there is nothing left to find.
            database.colorDao().deleteColorRow(chosen.last().id)

            val refusal =
                assertFailsWith<TaskFromTextException> {
                    store.createTasks(selectionOf(game, cell, segment, "Token"), draftsOf(chosen))
                }

            assertEquals(TaskFromTextFailure.COLOR_NOT_AVAILABLE, refusal.failure)
            assertEquals(2, refusal.row, "the refusal does not say which row the colour was in")
            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task was left behind")
            assertTrue(
                database.colorDao().colorsOfTask(IdGenerator.Random.newId()).isEmpty(),
                "a colour relation was left behind",
            )
            assertEquals(0, database.taskProgressDao().stagesOfTask(IdGenerator.Random.newId()).size)
            assertEquals(before, database.cellSegmentDao().segmentsOfCell(cell.id), "the cell did not come back")
            assertEquals(
                "Basılacak: Token, kutu ayrı.",
                assertNotNull(
                    database
                        .cellSegmentDao()
                        .segmentsOfCell(cell.id)
                        .single()
                        .text,
                ),
            )
        }
    }

    @Test
    fun `the same colour twice is refused without the database being written`() {
        val statements =
            statementsOf {
                val game = database.addGame("Aynı")
                val cell = database.addCell(game.id)
                val segment = database.addText(cell.id, "Basılacak: Token, kutu ayrı.")
                val color = database.colors(1).single()
                val draft = TaskDraft(color.id, 14, TrackingMode.THREE_D_BATCH, null)
                val before = database.cellSegmentDao().segmentsOfCell(cell.id)

                driver.start()
                val refusal =
                    assertFailsWith<TaskFromTextException> {
                        store.createTasks(selectionOf(game, cell, segment, "Token"), listOf(draft, draft.copy()))
                    }

                assertEquals(TaskFromTextFailure.DUPLICATE_COLOR, refusal.failure)
                assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty())
                assertEquals(before, database.cellSegmentDao().segmentsOfCell(cell.id))
            }

        assertTrue(
            statements.none { it.trimStart().startsWith("INSERT", ignoreCase = true) },
            "a refused batch wrote something: $statements",
        )
    }
}
