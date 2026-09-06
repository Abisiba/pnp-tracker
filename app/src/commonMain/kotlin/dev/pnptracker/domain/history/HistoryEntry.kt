package dev.pnptracker.domain.history

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ProductionStage
import kotlin.time.Instant

/**
 * What one line of the history says happened.
 *
 * A closed set rather than a code and a bag of nullable numbers, so every line
 * carries exactly the detail its kind has and no line can be built holding
 * detail that means nothing for it. It is also what makes the Turkish wording
 * exhaustive: a case added here will not compile until somebody has written the
 * sentence a user reads for it.
 *
 * The set is deliberately larger than either enum behind it. PLAN 12.15 asks for
 * one screen showing six kinds of thing, and two of them — a shortage reported
 * and a shortage made good — have been kept as [dev.pnptracker.domain.model.ProgressEventKind]
 * since the 3D model was built, while the rest are
 * [dev.pnptracker.domain.model.HistoryEventKind]. Where a line was recorded is
 * the database's business; what it says is this.
 */
sealed interface HistoryChange {
    /** A card or board step moved from one count to another (PLAN 1121). */
    data class StageMoved(
        val stage: ProductionStage,
        val previousQuantity: Int,
        val newQuantity: Int,
    ) : HistoryChange

    /** A task went from open to finished (PLAN 1118). */
    data object TaskCompleted : HistoryChange

    /** A finished task was made active again (PLAN 437). */
    data object TaskReopened : HistoryChange

    /** A task was removed from view, its record left behind (PLAN 1123). */
    data object TaskDeleted : HistoryChange

    /** A removed task was brought back (PLAN 1123). */
    data object TaskRestored : HistoryChange

    /** A task was turned back into the words it was made from (PLAN 1123, 12.8). */
    data object TaskConvertedToText : HistoryChange

    /** A game was removed from view, its record left behind (PLAN 1123). */
    data object GameDeleted : HistoryChange

    /** A removed game was brought back (PLAN 1123). */
    data object GameRestored : HistoryChange

    /**
     * Pieces came out missing or spoiled (PLAN 1119).
     *
     * The optional detail is what the user wrote at the time and is never made
     * up: a shortage recorded as a bare number stays a bare number.
     */
    data class ShortageReported(
        val quantity: Int,
        val note: String? = null,
        val cardReference: String? = null,
        val stage: ProductionStage? = null,
    ) : HistoryChange

    /** Some of what was owed was made good (PLAN 1120). */
    data class ShortageResolved(
        val quantity: Int,
        val note: String? = null,
        val cardReference: String? = null,
        val stage: ProductionStage? = null,
    ) : HistoryChange
}

/**
 * One line of the history screen.
 *
 * [occurredAt] is the moment the thing happened and [id] is what settles two
 * lines that share it — and lines do share it, because everything one
 * transaction writes carries the one moment the transaction ran at. The pair is
 * a total order, which is the whole reason the screen can be scrolled without
 * rows swapping places under the reader.
 *
 * [gameName] and [taskName] are what those records are called now rather than
 * what they were called then: PLAN records no name snapshots, and a line showing
 * an old name would send the user looking for a game the table no longer has.
 * Either may be absent — a game event names no task, and a shortage on a task
 * nothing can place any more names no game — and an absent one is shown as
 * unknown rather than dropping the line.
 */
data class HistoryEntry(
    val id: EntityId,
    val occurredAt: Instant,
    val change: HistoryChange,
    val gameId: EntityId? = null,
    val gameName: String? = null,
    val taskId: EntityId? = null,
    val taskName: String? = null,
)
