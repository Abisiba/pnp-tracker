package dev.pnptracker.data.database.entity

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ProductionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What a history line has to say to be one.
 *
 * The table is written from inside four different transactions, so the shape of
 * a row cannot be looked after by whichever of them happens to be writing. It is
 * looked after here, once, and a row that could not describe something that
 * really happened is refused before it reaches the database.
 */
class HistoryEventEntityTest {
    private val moment = Instant.fromEpochMilliseconds(1_781_000_000_000)
    private val gameId = IdGenerator.Random.newId()
    private val taskId = IdGenerator.Random.newId()

    private fun event(
        kind: HistoryEventKind,
        taskId: EntityId? = this.taskId,
        stage: ProductionStage? = null,
        previousQuantity: Int? = null,
        newQuantity: Int? = null,
    ) = HistoryEventEntity(
        id = IdGenerator.Random.newId(),
        kind = kind,
        occurredAt = moment,
        gameId = gameId,
        taskId = taskId,
        stage = stage,
        previousQuantity = previousQuantity,
        newQuantity = newQuantity,
    )

    @Test
    fun `a task event names the task it happened to`() {
        val finished = event(HistoryEventKind.TASK_COMPLETED)

        assertEquals(taskId, finished.taskId)
        assertEquals(gameId, finished.gameId)
    }

    @Test
    fun `a task event without a task is refused`() {
        HistoryEventKind.entries.filterNot { it.namesNoTask }.forEach { kind ->
            val refusal =
                assertFailsWith<IllegalArgumentException>("$kind was allowed to happen to no task") {
                    event(kind, taskId = null)
                }
            assertTrue(kind.name in refusal.message.orEmpty())
        }
    }

    @Test
    fun `a game-level event cannot also name a task`() {
        HistoryEventKind.entries.filter { it.namesNoTask }.forEach { kind ->
            assertFailsWith<IllegalArgumentException>("$kind was allowed to name a task") { event(kind) }
        }
    }

    @Test
    fun `an import taken back is recorded against its game and its tasks separately`() {
        val forTheGame = event(HistoryEventKind.IMPORT_ROLLED_BACK, taskId = null)
        val forOneTask = event(HistoryEventKind.TASK_ROLLED_BACK)

        // The two halves of one rollback: PLAN 11.4.4 writes a line per game and
        // a line per task, and the shapes are what keep them from being confused.
        assertEquals(gameId, forTheGame.gameId)
        assertEquals(null, forTheGame.taskId)
        assertEquals(taskId, forOneTask.taskId)
        assertEquals(gameId, forOneTask.gameId, "a task's rollback line lost the game it happened in")
    }

    @Test
    fun `a game's own event stands on the game alone`() {
        val removed = event(HistoryEventKind.GAME_DELETED, taskId = null)

        assertEquals(gameId, removed.gameId)
        assertNull(removed.taskId)
    }

    @Test
    fun `a stage movement carries the step and both counts`() {
        val moved =
            event(
                HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED,
                stage = ProductionStage.LAMINATE,
                previousQuantity = 3,
                newQuantity = 9,
            )

        assertEquals(ProductionStage.LAMINATE, moved.stage)
        assertEquals(3, moved.previousQuantity)
        assertEquals(9, moved.newQuantity)
    }

    @Test
    fun `a stage movement missing any of the three is refused`() {
        listOf(
            Triple(null, 3, 9),
            Triple(ProductionStage.PRINT, null, 9),
            Triple(ProductionStage.PRINT, 3, null),
        ).forEach { (stage, previous, next) ->
            assertFailsWith<IllegalArgumentException>("a half described movement was allowed: $stage $previous $next") {
                event(
                    HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED,
                    stage = stage,
                    previousQuantity = previous,
                    newQuantity = next,
                )
            }
        }
    }

    @Test
    fun `a step that did not move is not an event`() {
        assertFailsWith<IllegalArgumentException> {
            event(
                HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED,
                stage = ProductionStage.PRINT,
                previousQuantity = 7,
                newQuantity = 7,
            )
        }
    }

    @Test
    fun `a step cannot stand below nothing`() {
        listOf(-1 to 4, 4 to -1).forEach { (previous, next) ->
            assertFailsWith<IllegalArgumentException>("a negative count was allowed: $previous -> $next") {
                event(
                    HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED,
                    stage = ProductionStage.PRINT,
                    previousQuantity = previous,
                    newQuantity = next,
                )
            }
        }
    }

    @Test
    fun `any other kind is refused pipeline detail it has no use for`() {
        HistoryEventKind.entries.filterNot { it.carriesStageQuantities }.forEach { kind ->
            assertFailsWith<IllegalArgumentException>("$kind was allowed to carry a stage") {
                event(
                    kind,
                    taskId = if (kind.namesNoTask) null else taskId,
                    stage = ProductionStage.PRINT,
                    previousQuantity = 0,
                    newQuantity = 1,
                )
            }
        }
    }

    @Test
    fun `exactly the game-level kinds name no task`() {
        assertEquals(
            setOf(
                HistoryEventKind.GAME_DELETED,
                HistoryEventKind.GAME_RESTORED,
                // An import is recorded against the game it wrote into rather
                // than against any one of the tasks it made (PLAN 12.15).
                HistoryEventKind.IMPORT_CONFIRMED,
                HistoryEventKind.IMPORT_ROLLED_BACK,
            ),
            HistoryEventKind.entries.filter { it.namesNoTask }.toSet(),
        )
    }

    @Test
    fun `only a stage movement carries counts`() {
        assertEquals(
            setOf(HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED),
            HistoryEventKind.entries.filter { it.carriesStageQuantities }.toSet(),
        )
    }

    @Test
    fun `converting a task to text is not the same kind as deleting one`() {
        // They are written by different acts and PLAN 12.15 lists them separately,
        // so a screen that shows one must be able to tell it from the other.
        assertTrue(HistoryEventKind.TASK_CONVERTED_TO_TEXT != HistoryEventKind.TASK_DELETED)
    }
}
