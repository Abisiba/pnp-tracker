package dev.pnptracker.data.repository

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode

/**
 * Everything one draft says, as one answer to be saved at once.
 *
 * A single shape rather than thirteen arguments, because these fields are saved
 * together or not at all: the panel shows them together, and a half applied
 * change is a state the user never asked for.
 *
 * [requiredQuantity] is null for an amount the user left unknown. There is no
 * stand-in number: PLAN 11.7 has tasks whose amount is genuinely not known yet,
 * and writing a `1` would be inventing an answer that later reads as a decision.
 *
 * [colorIds] is the whole ordered list the draft is to carry, empty included:
 * PLAN 11.6 leaves a task colourless when the source never said which colour it
 * meant, and PLAN 5.10 makes the order part of what a multi-colour task is.
 */
data class DraftEdit(
    val draftTaskId: EntityId,
    val name: String,
    val targetCellId: EntityId?,
    val poolType: PoolType?,
    val trackingMode: TrackingMode?,
    val requiredQuantity: Int?,
    val notes: String?,
    val isMissing: Boolean,
    val isBorrowed: Boolean,
    val needsInfo: Boolean,
    val needsClassification: Boolean,
    val completionHint: HintDecision,
    val colorIds: List<EntityId>,
)

/**
 * One game the review screen can point an accepted green cell at.
 *
 * [sharedNameOrdinal] is set only when another game carries the same name, and
 * is that game's place among the ones that share it. PLAN 17 does not let a
 * choice be ambiguous, and two rows reading `Wingspan` with nothing to tell them
 * apart is exactly that; the number comes from the game list's own fixed reading
 * order, so it does not move between reads.
 */
data class GameChoice(
    val id: EntityId,
    val name: String,
    val isCompleted: Boolean,
    val sharedNameOrdinal: Int? = null,
)
