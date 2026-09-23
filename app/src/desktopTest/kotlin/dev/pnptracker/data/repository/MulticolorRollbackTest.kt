package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.ProgressEventEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.entity.TaskStageEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
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
 * What a half-written transaction leaves behind when a write really fails.
 *
 * Every other refusal is a guard the transaction makes on purpose, and those are
 * checked where they are made. These two are not refusals at all: the guards all
 * passed, the writing began, and then the database said no in the middle of it.
 * That is the case a rollback exists for, and the only honest way to reach it is
 * to make a real statement fail against a real SQLite file — a repository handed
 * a pretend exception would prove that the test can throw and nothing else.
 *
 * The trap is set on the driver the database is opened with, which needs no
 * production wiring: `DatabaseFactory` already takes the driver it opens with.
 */
class MulticolorRollbackTest {
    /** Everything about a cell and its tasks, as one comparable snapshot. */
    private data class Snapshot(
        val segments: List<CellSegmentEntity>,
        val documentText: String,
        val tasks: List<TaskEntity>,
        val colors: List<TaskColorEntity>,
        val stages: List<TaskStageEntity>,
        val events: List<ProgressEventEntity>,
    )

    /** One database whose driver can be told to refuse a chosen write. */
    private class Trapped(
        val database: AppDatabase,
        val creation: TaskFromTextStore,
        val editing: TaskEditStore,
        val driver: FailingSqliteDriver,
    )

    private fun withRefusedWrite(work: suspend Trapped.() -> Unit) {
        val existedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        val directory = TemporaryDatabaseDirectory()
        val driver = FailingSqliteDriver()
        val database = DatabaseFactory(driver = driver).open(directory.databaseFile)
        try {
            runBlocking {
                Trapped(
                    database = database,
                    creation = TaskFromTextStore(database.taskFromTextDao(), IdGenerator.Random, StoppedClock(updatedAt)),
                    editing = TaskEditStore(database.taskEditDao(), IdGenerator.Random, StoppedClock(updatedAt)),
                    driver = driver,
                ).work()
            }
        } finally {
            driver.disarm()
            database.close()
            directory.assertRealApplicationDatabaseUntouched(existedBefore)
            directory.delete()
        }
    }

    private fun writesTo(table: String): (String) -> Boolean =
        { sql -> table in sql && sql.trimStart().startsWith("INSERT", ignoreCase = true) }

    private suspend fun AppDatabase.documentTextOf(cellId: EntityId): String {
        val words =
            cellSegmentDao().segmentsOfCell(cellId).map { piece ->
                piece.text ?: assertNotNull(taskDao().taskByIdIncludingDeleted(piece.taskId!!)).name
            }
        return words.joinToString(separator = "")
    }

    private suspend fun AppDatabase.snapshotOf(cellId: EntityId): Snapshot {
        val segments = cellSegmentDao().segmentsOfCell(cellId)
        val taskIds = segments.mapNotNull { it.taskId }
        return Snapshot(
            segments = segments,
            documentText = documentTextOf(cellId),
            tasks = taskDao().allTasksIncludingDeleted(),
            colors = taskIds.flatMap { taskColorDao().colorsOfTask(it) },
            stages = taskIds.flatMap { taskProgressDao().stagesOfTask(it) },
            events = taskIds.flatMap { taskProgressDao().progressEventsOfTask(it) },
        )
    }

    private fun assertNothingChanged(
        before: Snapshot,
        after: Snapshot,
    ) {
        assertEquals(before.tasks, after.tasks, "a task was left behind or changed")
        assertEquals(before.colors, after.colors, "a colour relation was left behind or changed")
        assertEquals(before.stages, after.stages, "a stage was left behind or changed")
        assertEquals(before.events, after.events, "an event was left behind or changed")
        assertEquals(before.segments, after.segments, "the pieces of the cell changed")
        assertEquals(before.documentText, after.documentText, "the cell no longer says what it said")
        assertEquals(
            before.segments.map { it.orderIndex },
            after.segments.map { it.orderIndex },
            "the reading order changed",
        )
    }

    /** A cell with one stretch of plain text, and the selection of one word in it. */
    private suspend fun AppDatabase.wordInACell(
        columnType: CellColumnType,
        text: String,
        word: String,
    ): CellTextSelection {
        val game = aGame(name = "Harmonies").also { gameDao().insert(it) }
        val cell = aCell(gameId = game.id, columnType = columnType).also { gameCellDao().insert(it) }
        val segment =
            CellSegmentEntity
                .plainText(IdGenerator.Random.newId(), cell.id, 0, text, createdAt)
                .also { insertSegmentDirectly(this, it) }
        return CellTextSelection(
            gameId = game.id,
            cellId = cell.id,
            segmentId = segment.id,
            expectedText = text,
            startOffset = text.indexOf(word),
            endOffset = text.indexOf(word) + word.length,
        )
    }

    @Test
    fun `a second colour that will not go in leaves nothing of the task behind`() =
        withRefusedWrite {
            val text = "Basılacak: Yarasa, kutu ayrı."
            val selection = database.wordInACell(CellColumnType.THREE_D, text, "Yarasa")
            val colors =
                database
                    .colorDao()
                    .allColors()
                    .take(3)
                    .map { it.id }
            val before = database.snapshotOf(selection.cellId)

            driver.failOn(occurrence = 2, matches = writesTo("task_colors"))
            val refusal =
                assertFailsWith<TaskFromTextException> {
                    creation.createTasks(selection, listOf(TaskDraft(colors, 10, TrackingMode.THREE_D_BATCH, null)))
                }
            driver.disarm()

            // The storage refused it, not one of the transaction's own guards:
            // this is the write failing half way, which is the case being made.
            assertEquals(TaskFromTextFailure.COULD_NOT_SAVE, refusal.failure)

            assertNothingChanged(before, database.snapshotOf(selection.cellId))
            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task survived the refused colour")
            assertEquals(text, database.documentTextOf(selection.cellId))
        }

    @Test
    fun `a piece of the cell that will not go in leaves the task unwritten too`() =
        withRefusedWrite {
            val text = "Kesilecek: Yarasa, kutu ayrı."
            // Printing, because that is where a task in several colours lives
            // (PLAN 5.10).
            val selection = database.wordInACell(CellColumnType.THREE_D, text, "Yarasa")
            val colors =
                database
                    .colorDao()
                    .allColors()
                    .take(3)
                    .map { it.id }
            val before = database.snapshotOf(selection.cellId)

            // The task and its three colours are written before this: the
            // refusal lands after all of them.
            driver.failOn(occurrence = 1, matches = writesTo("cell_segments"))
            val refusal =
                assertFailsWith<TaskFromTextException> {
                    creation.createTasks(selection, listOf(TaskDraft(colors, 10, TrackingMode.THREE_D_BATCH, null)))
                }
            driver.disarm()

            assertEquals(TaskFromTextFailure.COULD_NOT_SAVE, refusal.failure)

            assertNothingChanged(before, database.snapshotOf(selection.cellId))
            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task survived the refused piece")
            assertTrue(database.taskProgressDao().stagesOfTask(IdGenerator.Random.newId()).isEmpty())
            assertEquals(text, database.documentTextOf(selection.cellId))
        }

    @Test
    fun `an edit whose colours are half rewritten comes back whole`() =
        withRefusedWrite {
            val text = "Kesilecek: Yarasa, kutu ayrı."
            val selection = database.wordInACell(CellColumnType.THREE_D, text, "Yarasa")
            val catalogue = database.colorDao().allColors()
            val taskId =
                creation
                    .createTasks(
                        selection,
                        listOf(TaskDraft(catalogue.take(3).map { it.id }, 10, TrackingMode.THREE_D_BATCH, "ilk not")),
                    ).single()
            database.taskProgressDao().reportFailure(
                IdGenerator.Random.newId(),
                taskId,
                quantity = 4,
                clock = StoppedClock(updatedAt),
            )
            val before = database.snapshotOf(selection.cellId)

            // After the old relations are deleted and the first new one is in:
            // the task is momentarily carrying a colour list that is neither the
            // old one nor the new one, which is exactly the state that must not
            // survive the transaction.
            driver.failOn(occurrence = 2, matches = writesTo("task_colors"))
            val refusal =
                assertFailsWith<TaskEditException> {
                    editing.editTask(
                        taskId,
                        "Yarasa Kanadı",
                        catalogue.drop(3).take(3).map { it.id },
                        20,
                        "başka not",
                        TrackingMode.THREE_D_BATCH,
                    )
                }
            driver.disarm()

            assertEquals(TaskEditFailure.COULD_NOT_SAVE, refusal.failure)

            assertNothingChanged(before, database.snapshotOf(selection.cellId))
            val task = assertNotNull(database.taskDao().activeTaskById(taskId))
            assertEquals("Yarasa", task.name, "the name was left changed")
            assertEquals(10, task.requiredQuantity, "the total was left changed")
            assertEquals("ilk not", task.notes, "the note was left changed")
            assertEquals(TrackingMode.THREE_D_BATCH, task.trackingMode)
            val colors = database.taskColorDao().colorsOfTask(taskId)
            assertEquals(catalogue.take(3).map { it.id }, colors.map { it.colorId }, "the colour list did not come back")
            assertEquals(listOf(0, 1, 2), colors.map { it.slotIndex }, "the numbering did not come back")
            assertEquals(
                taskId,
                assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(taskId)).taskId,
                "the task lost its piece of the cell",
            )
        }
}
