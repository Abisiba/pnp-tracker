package dev.pnptracker.domain.model

/**
 * What happened to a task or a game, in the words the history screen will use.
 *
 * PLAN 12.15 asks a history screen for six kinds of line, and only two of them
 * can be read off what the database already keeps: a shortage reported and a
 * shortage made good are [ProgressEventKind], recorded since the 3D model was
 * built. The rest — a task finished, a pipeline moved, a record deleted, a task
 * turned back into words — leave nothing behind today, because each of them only
 * writes over a field. `completed_at` says when a task was last finished and
 * nothing about the time before that; a stage's count says where the work stands
 * and nothing about how it got there.
 *
 * So these are the movements that had no record. They are kept exactly as
 * [ProgressEventKind] is kept — appended, never edited, never deleted — because
 * PLAN 5.12 makes history something derived from what was recorded rather than a
 * number somebody keeps up to date, and a line that can be rewritten is not a
 * history of anything.
 *
 * They are deliberately *not* new [ProgressEventKind] values. That enum answers
 * a different question — how much is still owed, and how much was ever spoiled —
 * and every one of its rows is about a number of pieces. Finishing a task is
 * about no pieces at all, and putting it in the same table would either force a
 * meaningless quantity onto it or loosen an invariant the 3D arithmetic rests on.
 */
enum class HistoryEventKind {
    /**
     * One step of a card or board pipeline was moved to a new count.
     *
     * PLAN 12.15. The only kind that carries numbers: which step, what it stood
     * at, and what it stands at now. Both counts are kept because the movement
     * is the point — a step going from three to nine and one going from eight to
     * nine leave the same task behind and are not the same day's work.
     */
    TASK_STAGE_QUANTITY_CHANGED,

    /**
     * A task went from open to finished.
     *
     * PLAN 12.15, and PLAN 6.3 keeps the task in the game and in the history when
     * it does. Written whichever way the task got there — ticked by hand,
     * counted up its pipeline, settled its last shortage, or finished along with
     * its game — because from the history's side those are the same event.
     */
    TASK_COMPLETED,

    /**
     * A finished task was made active again.
     *
     * PLAN 6.3: a new shortage on a finished task brings it back, and the user
     * can also simply take the tick off. Without this the previous
     * [TASK_COMPLETED] would be the last word on a task that is plainly not done.
     */
    TASK_REOPENED,

    /** A task was removed from view, leaving its record behind (PLAN 5.2, 12.15). */
    TASK_DELETED,

    /** A removed task was brought back into view (PLAN 12.15). */
    TASK_RESTORED,

    /**
     * A task was turned back into the words it was made from (PLAN 12.15, 12.8).
     *
     * Its own kind rather than a [TASK_DELETED], because the two are different
     * things to have done: one takes a piece of work out of sight, the other
     * says the words were never a piece of work. The task is soft deleted as
     * well — that is how it leaves the pools and the table — but the deletion is
     * the mechanism and this is the act.
     */
    TASK_CONVERTED_TO_TEXT,

    /** A game was removed from view, leaving its record behind (PLAN 5.2, 12.15). */
    GAME_DELETED,

    /** A removed game was brought back into view (PLAN 12.15). */
    GAME_RESTORED,

    /**
     * An import was confirmed and wrote into this game.
     *
     * PLAN 12.15 asks for one line per game an import touched, not one per task:
     * a file that fills six cells of one game is one thing the user did, and six
     * identical lines would bury the day it happened in.
     *
     * It carries no count of the tasks it made. The three quantity columns
     * belong to [TASK_STAGE_QUANTITY_CHANGED] alone, and a column that means
     * "how far a step got" on one kind and "how many tasks arrived" on another
     * is exactly the payload column `HistoryEventEntity` refuses to become. What
     * the import created is already recorded, on the import.
     */
    IMPORT_CONFIRMED,

    /**
     * An import was taken back, and this game was one it had written into.
     *
     * The counterpart of [IMPORT_CONFIRMED] and, like it, one line per game
     * (PLAN 11.4.4). Written only by a rollback that really happened: a blocked
     * one changes nothing and records nothing, because nothing occurred.
     */
    IMPORT_ROLLED_BACK,

    /**
     * One task an import created was taken back out of view.
     *
     * Its own kind rather than a [TASK_DELETED], for the same reason
     * [TASK_CONVERTED_TO_TEXT] is: the tombstone is the mechanism and this is
     * the act. A user reading their history should be able to tell a task they
     * decided to delete from one that went because they took a whole import
     * back.
     */
    TASK_ROLLED_BACK,

    ;

    /**
     * Whether this kind is recorded against a game with no task named.
     *
     * Renamed from "about the game itself" when the import kinds arrived, because
     * that is no longer what these have in common: a game being deleted is about
     * the game, while an import being confirmed is about something that happened
     * *in* the game. What they share is the only thing the entity needs to know —
     * there is no one task it happened to.
     *
     * Every other kind must name a task: a stage that moved, a task that
     * finished, came back or was taken back belongs to exactly one task or it is
     * not that event at all.
     */
    val namesNoTask: Boolean
        get() = this == GAME_DELETED || this == GAME_RESTORED || this == IMPORT_CONFIRMED || this == IMPORT_ROLLED_BACK

    /** Whether this kind carries the counts a pipeline step moved between. */
    val carriesStageQuantities: Boolean get() = this == TASK_STAGE_QUANTITY_CHANGED
}
