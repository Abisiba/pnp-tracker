package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.poolNameOf
import org.jetbrains.compose.resources.StringResource

/**
 * What to tell the user about a refused confirmation.
 *
 * Every case is spelled out rather than falling back to one general apology,
 * because each one has a different next step: open a cell, choose a target,
 * tick the warning, or nothing at all.
 */
fun messageOf(failure: ImportConfirmationFailure): StringResource =
    when (failure) {
        ImportConfirmationFailure.BATCH_NOT_FOUND -> Strings.Confirm.errorBatchNotFound
        ImportConfirmationFailure.ALREADY_CONFIRMED -> Strings.Confirm.errorAlreadyConfirmed
        ImportConfirmationFailure.BATCH_NOT_A_DRAFT -> Strings.Confirm.errorNotADraft
        // One sentence for all nine ways: which rows disagree is nothing a
        // person can act on, and no table or code is ever put in front of them.
        ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER -> Strings.Confirm.errorRecordsContradict
        ImportConfirmationFailure.NO_DRAFTS_TO_CONFIRM -> Strings.Confirm.errorNoDrafts
        ImportConfirmationFailure.NO_CELLS_AVAILABLE -> Strings.Confirm.errorNoCells
        ImportConfirmationFailure.UNPROCESSED_BLOCKS_NOT_ACKNOWLEDGED -> Strings.Confirm.errorUnprocessed
        ImportConfirmationFailure.TARGET_CELL_MISSING -> Strings.Confirm.errorTargetMissing
        ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE -> Strings.Confirm.errorTargetUnavailable
        ImportConfirmationFailure.TARGET_CELL_NOT_TASK_CAPABLE -> Strings.Confirm.errorTargetNotTaskCapable
        ImportConfirmationFailure.TARGET_CELL_WRONG_COLUMN -> Strings.Confirm.errorTargetWrongColumn
        ImportConfirmationFailure.POOL_TYPE_MISSING -> Strings.Confirm.errorPoolMissing
        ImportConfirmationFailure.TRACKING_MODE_MISSING -> Strings.Confirm.errorTrackingMissing
        ImportConfirmationFailure.COLOR_NO_LONGER_AVAILABLE -> Strings.Confirm.errorColorUnavailable
        ImportConfirmationFailure.COMPLETION_HINT_UNDECIDED -> Strings.Confirm.errorCompletionHintUndecided
        ImportConfirmationFailure.GAME_COMPLETION_HINT_UNDECIDED -> Strings.Confirm.errorGameHintUndecided
        ImportConfirmationFailure.SELECTION_NO_LONGER_FITS -> Strings.Confirm.errorSelectionNoLongerFits
        ImportConfirmationFailure.COMPLETION_TARGET_GAME_REQUIRED -> Strings.Confirm.errorCompletionTargetRequired
        ImportConfirmationFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE ->
            Strings.Confirm.errorCompletionTargetUnavailable
        ImportConfirmationFailure.COULD_NOT_SAVE -> Strings.Confirm.errorCouldNotSave
        // The four the automatic backup adds. Each says the same two things —
        // nothing was written, the import is still a draft — and then the one
        // thing that differs, which is what the person can do about it
        // (PLAN 14.4.13).
        ImportConfirmationFailure.SNAPSHOT_NOT_MADE -> Strings.Confirm.errorSnapshotNotMade
        ImportConfirmationFailure.SNAPSHOT_NOT_WRITTEN -> Strings.Confirm.errorSnapshotNotWritten
        ImportConfirmationFailure.SNAPSHOT_NOT_VERIFIED -> Strings.Confirm.errorSnapshotNotVerified
        ImportConfirmationFailure.DATA_CHANGED_MEANWHILE -> Strings.Confirm.errorDataChanged
    }

/** The pool names, taken from the one place that decides them. */
fun labelOf(poolType: PoolType): StringResource = poolNameOf(poolType)

/**
 * How a task is tracked, in the user's words.
 *
 * From the catalogue like every other name. These four were written into the
 * screen itself once, which put four Turkish words somewhere no language file
 * knew about; PLAN 17 keeps the wording where it can be found and changed.
 */
fun labelOf(trackingMode: TrackingMode): StringResource =
    when (trackingMode) {
        TrackingMode.THREE_D_BATCH -> Strings.Tracking.threeDBatch
        TrackingMode.PIPELINE -> Strings.Tracking.pipeline
        TrackingMode.CHECKLIST -> Strings.Tracking.checklist
        TrackingMode.COUNTED -> Strings.Tracking.counted
    }

/**
 * What to tell the user about a review change that did not happen.
 *
 * Every case has its own sentence, because each has a different next step: widen
 * the selection, choose a colour again, pick a game, or reopen the import. A raw
 * enum name, a stack trace or a piece of SQL never reaches the screen.
 */
fun reviewMessageOf(failure: ImportReviewFailure): StringResource =
    when (failure) {
        ImportReviewFailure.COULD_NOT_SAVE -> Strings.Review.couldNotSave
        ImportReviewFailure.DRAFT_TASK_NOT_FOUND -> Strings.Review.errorDraftGone
        ImportReviewFailure.RAW_BLOCK_NOT_FOUND -> Strings.Review.errorBlockGone
        ImportReviewFailure.BATCH_NOT_A_DRAFT -> Strings.Review.errorBatchNotDraft
        ImportReviewFailure.DUPLICATE_COLOR -> Strings.Review.errorDuplicateColor
        ImportReviewFailure.COLOR_NOT_AVAILABLE -> Strings.Review.errorColorUnavailable
        ImportReviewFailure.BLOCK_CANNOT_CARRY_GAME_COMPLETION -> Strings.Review.errorBlockCannotCarryHint
        ImportReviewFailure.COMPLETION_TARGET_REQUIRED -> Strings.Review.errorCompletionTargetRequired
        ImportReviewFailure.COMPLETION_TARGET_NOT_ALLOWED -> Strings.Review.errorCompletionTargetNotAllowed
        ImportReviewFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE -> Strings.Review.errorCompletionTargetUnavailable
        ImportReviewFailure.INVALID_SELECTION -> Strings.Review.errorInvalidSelection
        ImportReviewFailure.SELECTION_SPLITS_A_CHARACTER -> Strings.Review.errorSelectionSplitsCharacter
        ImportReviewFailure.SELECTION_IS_EMPTY -> Strings.Review.errorSelectionEmpty
        ImportReviewFailure.SELECTION_CONTAINS_LINE_BREAK -> Strings.Review.errorSelectionLineBreak
        ImportReviewFailure.TASK_NAME_EMPTY -> Strings.Review.errorTaskNameEmpty
        ImportReviewFailure.INVALID_REQUIRED_QUANTITY -> Strings.Review.errorQuantity
        ImportReviewFailure.MISSING_AND_BORROWED -> Strings.Review.errorMissingAndBorrowed
        ImportReviewFailure.TRACKING_MODE_NOT_ALLOWED -> Strings.Review.errorTrackingNotAllowed
        ImportReviewFailure.TARGET_CELL_NOT_AVAILABLE -> Strings.Review.errorTargetUnavailable
        ImportReviewFailure.TARGET_CELL_NOT_TASK_CAPABLE -> Strings.Review.errorTargetNotTaskCapable
        ImportReviewFailure.TARGET_CELL_WRONG_COLUMN -> Strings.Review.errorTargetWrongColumn
        ImportReviewFailure.COMPLETION_HINT_NOT_ANSWERABLE -> Strings.Review.errorHintNotAnswerable
    }
