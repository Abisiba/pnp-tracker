package dev.pnptracker.domain.importhealth

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportBatchStatus

/**
 * One way an unconfirmed import's own records disagree with each other.
 *
 * PLAN 11.4.5 defines a damaged draft by nine exact predicates and by nothing
 * else, and each of them is one value here. Every one was carried into a live
 * database of the current schema through the real restore — the backup reader,
 * its throwaway database and the live replace — so none of them is a guess
 * about what could happen (`DraftContradictionReachTest`).
 *
 * These names are for code and tests. The user is told only that the records do
 * not agree; which of them it was is not something a person can act on, and a
 * table or a predicate code never reaches the screen.
 */
enum class DraftContradiction {
    /** D1: the batch says tasks were created, and a draft has created none. */
    TASKS_COUNTED_BEFORE_CONFIRMATION,

    /** D2: the batch says games were created, and a draft has created none. */
    GAMES_COUNTED_BEFORE_CONFIRMATION,

    /** D3: the batch's raw cell count is not the number of raw cells it has. */
    RAW_CELL_COUNT_DISAGREES,

    /** D4: a draft names the task it became, and only a confirmation makes one. */
    DRAFT_ALREADY_MATERIALIZED,

    /** D5: a cell was recorded as it read before a confirmation that never happened. */
    CELL_RECORDED_BEFORE_CONFIRMATION,

    /** D6: a task, deleted or not, names one of the draft's raw cells as its source. */
    TASK_SOURCED_FROM_DRAFT,

    /** D7: a game, deleted or not, names the draft as its source. */
    GAME_SOURCED_FROM_DRAFT,

    /** D8: a selection ends past the last UTF-16 unit of its raw cell's text. */
    SELECTION_BEYOND_ITS_TEXT,

    /** D9: a raw cell outside the game column carries a game completion hint. */
    COMPLETION_HINT_OUTSIDE_GAME_COLUMN,
}

/**
 * What one import's records say about each other, read and nothing more.
 *
 * Only a draft is classified. A confirmed or rolled back import is not the
 * subject of PLAN 11.4.5 and is reported as [NotADraft] without being looked at.
 */
sealed interface DraftHealth {
    val batchId: EntityId

    /** A draft whose records agree: it can be continued, confirmed or removed. */
    data class Sound(
        override val batchId: EntityId,
    ) : DraftHealth

    /** A draft whose records contradict each other, with every way they do. */
    data class Contradicting(
        override val batchId: EntityId,
        val contradictions: Set<DraftContradiction>,
    ) : DraftHealth {
        init {
            require(contradictions.isNotEmpty()) { "A contradicting draft names at least one contradiction." }
        }
    }

    /** No import with this identity exists. */
    data class NotFound(
        override val batchId: EntityId,
    ) : DraftHealth

    /** The import exists and is not a draft. */
    data class NotADraft(
        override val batchId: EntityId,
        val status: ImportBatchStatus,
    ) : DraftHealth {
        init {
            require(status != ImportBatchStatus.DRAFT) { "A draft is never reported as not a draft." }
        }
    }
}
