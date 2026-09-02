package dev.pnptracker.domain.importreview

import dev.pnptracker.domain.importhint.ReferenceColumnLayout
import dev.pnptracker.domain.importhint.detectLeadingQuantity
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType

/**
 * What a new draft starts out saying, before the user has changed anything.
 *
 * Every field here is a **suggestion that was written down**, not a decision the
 * application made on the user's behalf. The difference is what
 * [completionHint] shows: a `**` in the selected words becomes
 * [HintDecision.PENDING] and never [HintDecision.ACCEPTED], so the task it
 * eventually produces is open unless somebody said otherwise. The same holds for
 * the pool: [suggestedPoolType] is what the column implies and is kept apart
 * from the pool the user selects, which starts out unset.
 *
 * Nothing about colour is here. PLAN 11.6 refuses to read `Mavi/Açık Mavi` as
 * two colours, and a colour word the file happens to contain is not the user
 * choosing it either — so a draft is born colourless and the user picks.
 */
data class DraftInitialValues(
    val name: String,
    val selectionStartIndex: Int?,
    val selectionEndIndex: Int?,
    val suggestedPoolType: PoolType?,
    val requiredQuantity: Int?,
    val completionHint: HintDecision,
    val isMissing: Boolean,
    val isBorrowed: Boolean,
    val needsInfo: Boolean,
    val needsClassification: Boolean,
) {
    init {
        require(name.isNotBlank()) { "A draft task needs a name." }
        require((selectionStartIndex == null) == (selectionEndIndex == null)) {
            "A selection has both ends or neither."
        }
        require(!(isMissing && isBorrowed)) { "A cell is either missing or borrowed, never both." }
        require(completionHint != HintDecision.ACCEPTED) {
            "A `**` is a question the user answers; no hint arrives already agreed to."
        }
    }
}

/**
 * The starting point for a draft cut out of a cell.
 *
 * Three things are read, and all three are things the detectors really find. The
 * column says what kind of work the cell describes and whether it came from the
 * missing or borrowed list (PLAN 10); the leading number is the amount the
 * reference file writes first; the `**` is PLAN 11.5's completion marker, kept
 * as a question.
 *
 * An amount that was never written leaves the total unknown and asks for
 * `NEEDS_INFO`, which is what PLAN 11.7 does with `Sayısına bakılacak` and the
 * rest of the unfinished sentences in the file. The flag is a suggestion like
 * every other one here: the user can take it off, and taking it off is their
 * own action rather than a side effect of typing a number.
 */
fun initialDraftFromSelection(
    columnIndex: Int,
    selection: RawTextSelection,
): DraftInitialValues {
    val column = ReferenceColumnLayout.suggestionFor(columnIndex)
    val quantity = detectLeadingQuantity(selection.selectedText)?.value
    return DraftInitialValues(
        name = selection.name,
        selectionStartIndex = selection.startIndex,
        selectionEndIndex = selection.endIndex,
        suggestedPoolType = column?.suggestedPoolType,
        requiredQuantity = quantity,
        completionHint = if (selection.hasCompletionMarker) HintDecision.PENDING else HintDecision.NONE,
        isMissing = column?.isMissing == true,
        isBorrowed = column?.isBorrowed == true,
        needsInfo = quantity == null,
        needsClassification = column?.needsClassification == true,
    )
}

/**
 * The starting point for a draft the user typed rather than cut out.
 *
 * The column's own flags still apply — the cell is in the missing list whether
 * the words came out of it or not — but nothing is read out of what was typed.
 * There is no source sentence to have left an amount out of, so the total is
 * simply unset and no flag claims that anything is missing from it.
 */
fun initialDraftByHand(
    columnIndex: Int,
    name: String,
): DraftInitialValues {
    val cleanName = name.trim()
    if (cleanName.isEmpty()) throw ImportReviewException(ImportReviewFailure.TASK_NAME_EMPTY)
    val column = ReferenceColumnLayout.suggestionFor(columnIndex)
    return DraftInitialValues(
        name = cleanName,
        selectionStartIndex = null,
        selectionEndIndex = null,
        suggestedPoolType = column?.suggestedPoolType,
        requiredQuantity = null,
        completionHint = HintDecision.NONE,
        isMissing = column?.isMissing == true,
        isBorrowed = column?.isBorrowed == true,
        needsInfo = false,
        needsClassification = column?.needsClassification == true,
    )
}
