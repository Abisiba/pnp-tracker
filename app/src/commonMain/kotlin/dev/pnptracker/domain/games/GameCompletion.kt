package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ProductionStage

/**
 * One step of one task's pipeline, as the question about the game found it.
 *
 * The stage names the row: `task_stages` is keyed by the task and the stage
 * together, so a stage that has gone, one that has appeared, and one whose count
 * has moved are all differences in this list. [orderIndex] is here because a
 * pipeline is an order as well as a set — PLAN 7.2 checks each step against the
 * one before it — so a pipeline reordered under an open question is not the
 * pipeline the answer was given about.
 */
data class GameStageSnapshot(
    val stage: ProductionStage,
    val orderIndex: Int,
    val completedQuantity: Int,
)

/**
 * One task of a game, as far as finishing the whole game is concerned.
 *
 * Everything the bulk completion's outcome turns on and nothing else: whether
 * there is anything to finish, whether a debt has to be settled and recorded,
 * what number its pipeline is to be counted up to, and the pipeline itself.
 *
 * The stages are here because they are what the transaction writes. Leaving them
 * out was a real gap rather than a tidy simplification: a card standing at
 * `15/10/5` and the same card at `9/0/0` are identical in every other field, so
 * an answer given about the one was quietly applied to the other.
 */
data class GameTaskSnapshot(
    val taskId: EntityId,
    val isCompleted: Boolean,
    val currentMissingQuantity: Int,
    val requiredQuantity: Int?,
    val stages: List<GameStageSnapshot> = emptyList(),
) {
    /** The same task with its pipeline in a fixed order. */
    internal fun ordered(): GameTaskSnapshot = copy(stages = stages.sortedBy { it.stage.ordinal })
}

/**
 * A game and its tasks as they stood when the user was asked about them.
 *
 * PLAN 12.9 asks the question `Tüm görevler tamamlandı mı?` against a count of
 * unfinished work, and then finishes that work. Those are two moments, and
 * between them the tasks can move: one finished from its own tick, one reported
 * short, a whole task added or deleted. Answering `Evet` to a question about
 * three unfinished tasks must not silently finish five, so the answer carries
 * the picture it was given and the transaction refuses to write against a
 * different one.
 *
 * The comparison is by content and not by order. The screen reads its tasks
 * column by column and the transaction reads them by identity, so two honest
 * readings of one unchanged game arrive in different orders; ordering them here
 * is what lets the two be compared at all.
 */
data class GameCompletionSnapshot(
    val isGameCompleted: Boolean,
    val tasks: List<GameTaskSnapshot>,
) {
    /** How many tasks the user is being asked to declare finished. */
    val unfinishedCount: Int get() = tasks.count { !it.isCompleted }

    /**
     * Whether finishing the game means finishing work as well as marking a row.
     *
     * PLAN 12.9:1028 finishes a game with nothing unfinished in it without
     * asking, and a game with no tasks at all is that same case: there is no
     * unfinished work to declare finished on the user's behalf.
     */
    val needsConfirmation: Boolean get() = unfinishedCount > 0

    /** True when [other] is the same game in the same state, however it was read. */
    fun matches(other: GameCompletionSnapshot): Boolean = isGameCompleted == other.isGameCompleted && ordered() == other.ordered()

    /**
     * The same tasks and the same pipelines in a fixed order.
     *
     * Both levels, because both come back in whatever order they were read in:
     * SQLite is free to hand rows over however it likes, and comparing two
     * honest readings of one unchanged game must not depend on that.
     */
    private fun ordered(): List<GameTaskSnapshot> = tasks.map { it.ordered() }.sortedBy { it.taskId.toString() }
}
