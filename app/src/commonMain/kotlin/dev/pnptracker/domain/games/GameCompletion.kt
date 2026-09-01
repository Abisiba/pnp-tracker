package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.EntityId

/**
 * One task of a game, as far as finishing the whole game is concerned.
 *
 * Three facts and no more, because these are exactly the three that decide what
 * a bulk completion will do to this task (PLAN 12.9): whether there is anything
 * to finish, whether a debt has to be settled and recorded, and what number its
 * pipeline is to be counted up to. A stage's own count is deliberately not here
 * — every stage ends at the total whatever it stood at — so putting it in would
 * make the question "has anything changed" answer yes to work that changes
 * nothing about the outcome.
 */
data class GameTaskSnapshot(
    val taskId: EntityId,
    val isCompleted: Boolean,
    val currentMissingQuantity: Int,
    val requiredQuantity: Int?,
)

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

    private fun ordered(): List<GameTaskSnapshot> = tasks.sortedBy { it.taskId.toString() }
}
