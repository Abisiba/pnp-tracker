package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.countedQuantityOf

/**
 * A stretch of one cell's text the user has pointed at.
 *
 * Its own type rather than two loose integers, because "there is no selection"
 * and "there is one, of these characters, in this cell" are different states and
 * the screen offers different things in each. The offsets are UTF-16 indexes,
 * which is what the text field reports and what the database stores; nothing
 * converts between them anywhere.
 *
 * Nothing about it is stored. A selection lives as long as the cell stays in
 * focus, and the database only ever hears about one that was acted on.
 */
data class RawSelection(
    val blockId: EntityId,
    val startIndex: Int,
    val endIndex: Int,
) {
    val isEmpty: Boolean get() = endIndex <= startIndex
}

/**
 * Everything one draft says while it is being edited, before any of it is saved.
 *
 * Kept apart from the workspace, which mirrors the database: this is what
 * somebody is typing. A new workspace arriving must not replace it, and closing
 * the panel must leave no trace of it.
 *
 * The total is held as text rather than as a number because that is what a field
 * holds while it is being typed, and [quantityUnknown] is a separate answer
 * rather than an empty box: PLAN 11.7 has tasks whose amount is genuinely not
 * known, and "not known" is a different thing from "not typed yet".
 */
data class DraftForm(
    val draftId: EntityId,
    val blockId: EntityId,
    val name: String,
    val targetCellId: EntityId?,
    val poolType: PoolType?,
    val trackingMode: TrackingMode?,
    val quantityText: String,
    val quantityUnknown: Boolean,
    val notes: String,
    val isMissing: Boolean,
    val isBorrowed: Boolean,
    val needsInfo: Boolean,
    val needsClassification: Boolean,
    val completionHint: HintDecision,
    val colorIds: List<EntityId>,
) {
    /** The total to store: null when the user says the amount is not known. */
    val requiredQuantity: Int? get() = if (quantityUnknown) null else countedQuantityOf(quantityText)

    val isQuantityUsable: Boolean
        get() = quantityUnknown || (requiredQuantity?.let { it > 0 } == true)

    val isNameUsable: Boolean
        get() = name.isNotBlank() && name.none { it == '\n' || it == '\r' }

    /** A cell is in the missing column or the borrowed one, never in both (PLAN 10). */
    val flagsConflict: Boolean get() = isMissing && isBorrowed

    /**
     * The two places holding the same colour, one based, or null when there are
     * none.
     *
     * Both ends, because a list saying "this colour is here twice" without saying
     * where leaves the user to find the pair themselves.
     */
    val duplicateColorSlots: Pair<Int, Int>?
        get() {
            val first = colorIds.indexOfFirst { id -> colorIds.indexOf(id) != colorIds.lastIndexOf(id) }
            if (first < 0) return null
            return first to colorIds.indexOfLast { it == colorIds[first] }
        }

    val canSave: Boolean
        get() = isNameUsable && isQuantityUsable && !flagsConflict && duplicateColorSlots == null

    /** The note as it is stored: the user's own words, or nothing at all. */
    val storedNotes: String? get() = notes.takeIf { it.isNotEmpty() }
}

/**
 * Which panel of the review screen is open over the two panes.
 *
 * Typed cases rather than a pile of booleans, because the combinations a pile
 * allows — a colour list open over no draft, two forms holding two different
 * names — are states the screen has no answer for. Exactly one of these is true
 * at a time, and Escape steps out of the innermost one rather than closing
 * everything at once.
 */
sealed interface ImportReviewSurface {
    /** Nothing is open; the panes are being read. */
    data object None : ImportReviewSurface

    /**
     * A task being typed by hand rather than cut out of the text.
     *
     * Its own case, and never the same one as a selection: PLAN 11.4 offers the
     * two as separate actions, and a draft typed from nothing carries no offsets
     * into anybody's cell.
     */
    data class ManualDraft(
        val blockId: EntityId,
        val name: String,
    ) : ImportReviewSurface {
        val canSave: Boolean get() = name.isNotBlank()
    }

    /** One draft's whole form, open over the pane it is listed in. */
    data class DraftEditor(
        val form: DraftForm,
    ) : ImportReviewSurface

    /** The catalogue, open over the form, so a colour can be added to the list. */
    data class ColorChoice(
        val form: DraftForm,
        val query: String,
    ) : ImportReviewSurface

    /** Choosing which game an accepted green cell was about. */
    data class GameTarget(
        val blockId: EntityId,
    ) : ImportReviewSurface

    /** The form underneath, when there is one: what Escape steps back to. */
    val openForm: DraftForm?
        get() =
            when (this) {
                is DraftEditor -> form
                is ColorChoice -> form
                else -> null
            }
}

/**
 * Where the keyboard should go next, after something the user did moved things
 * around.
 *
 * A request rather than an action: the controller knows which thing deserves the
 * keyboard, the screen knows where that thing is drawn, and the token is cleared
 * once it has been honoured so it cannot fire twice.
 */
sealed interface ReviewFocus {
    data class Draft(
        val draftId: EntityId,
    ) : ReviewFocus

    data class ColorSlot(
        val slot: Int,
    ) : ReviewFocus

    data class Block(
        val blockId: EntityId,
    ) : ReviewFocus

    /** The list of things stopping the confirmation. */
    data object Problems : ReviewFocus

    /** The line summarising what was decided about a green cell. */
    data object GameDecision : ReviewFocus
}
