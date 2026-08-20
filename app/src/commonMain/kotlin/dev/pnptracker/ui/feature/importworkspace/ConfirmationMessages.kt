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
 * because each one has a different next step: create an item, choose a target,
 * tick the warning, or nothing at all.
 */
fun messageOf(failure: ImportConfirmationFailure): StringResource =
    when (failure) {
        ImportConfirmationFailure.BATCH_NOT_FOUND -> Strings.Confirm.errorBatchNotFound
        ImportConfirmationFailure.ALREADY_CONFIRMED -> Strings.Confirm.errorAlreadyConfirmed
        ImportConfirmationFailure.BATCH_NOT_A_DRAFT -> Strings.Confirm.errorNotADraft
        ImportConfirmationFailure.NO_DRAFTS_TO_CONFIRM -> Strings.Confirm.errorNoDrafts
        ImportConfirmationFailure.NO_ITEMS_AVAILABLE -> Strings.Confirm.errorNoItems
        ImportConfirmationFailure.UNPROCESSED_BLOCKS_NOT_ACKNOWLEDGED -> Strings.Confirm.errorUnprocessed
        ImportConfirmationFailure.TARGET_ITEM_MISSING -> Strings.Confirm.errorTargetMissing
        ImportConfirmationFailure.TARGET_ITEM_NOT_AVAILABLE -> Strings.Confirm.errorTargetUnavailable
        ImportConfirmationFailure.POOL_TYPE_MISSING -> Strings.Confirm.errorPoolMissing
        ImportConfirmationFailure.TRACKING_MODE_MISSING -> Strings.Confirm.errorTrackingMissing
        ImportConfirmationFailure.COULD_NOT_SAVE -> Strings.Confirm.errorCouldNotSave
    }

/** The pool names, taken from the one place that decides them. */
fun labelOf(poolType: PoolType): StringResource = poolNameOf(poolType)

fun labelOf(trackingMode: TrackingMode): String =
    when (trackingMode) {
        TrackingMode.THREE_D_BATCH -> "Parti"
        TrackingMode.PIPELINE -> "Aşamalı"
        TrackingMode.CHECKLIST -> "Kontrol listesi"
        TrackingMode.COUNTED -> "Sayılı"
    }
