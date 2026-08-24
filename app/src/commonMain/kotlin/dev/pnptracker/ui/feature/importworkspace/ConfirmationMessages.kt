package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
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
        ImportConfirmationFailure.NO_DRAFTS_TO_CONFIRM -> Strings.Confirm.errorNoDrafts
        ImportConfirmationFailure.NO_CELLS_AVAILABLE -> Strings.Confirm.errorNoCells
        ImportConfirmationFailure.UNPROCESSED_BLOCKS_NOT_ACKNOWLEDGED -> Strings.Confirm.errorUnprocessed
        ImportConfirmationFailure.TARGET_CELL_MISSING -> Strings.Confirm.errorTargetMissing
        ImportConfirmationFailure.TARGET_CELL_NOT_AVAILABLE -> Strings.Confirm.errorTargetUnavailable
        ImportConfirmationFailure.TARGET_CELL_NOT_TASK_CAPABLE -> Strings.Confirm.errorTargetNotTaskCapable
        ImportConfirmationFailure.TARGET_CELL_WRONG_COLUMN -> Strings.Confirm.errorTargetWrongColumn
        ImportConfirmationFailure.POOL_TYPE_MISSING -> Strings.Confirm.errorPoolMissing
        ImportConfirmationFailure.TRACKING_MODE_MISSING -> Strings.Confirm.errorTrackingMissing
        ImportConfirmationFailure.COULD_NOT_SAVE -> Strings.Confirm.errorCouldNotSave
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
