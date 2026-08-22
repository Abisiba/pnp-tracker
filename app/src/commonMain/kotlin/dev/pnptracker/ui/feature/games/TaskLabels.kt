package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskSetupFailure
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.poolNameOf
import org.jetbrains.compose.resources.StringResource

/** The pool names, taken from the one place that decides them. */
fun labelOf(poolType: PoolType): StringResource = poolNameOf(poolType)

fun labelOf(trackingMode: TrackingMode): StringResource =
    when (trackingMode) {
        TrackingMode.THREE_D_BATCH -> Strings.Tracking.threeDBatch
        TrackingMode.PIPELINE -> Strings.Tracking.pipeline
        TrackingMode.CHECKLIST -> Strings.Tracking.checklist
        TrackingMode.COUNTED -> Strings.Tracking.counted
    }

/**
 * What to tell the user about a task that did not save.
 *
 * Both cases are spelled out rather than falling back to one general apology,
 * because they have different next steps: try again, or go and look at the item.
 */
fun messageOf(failure: TaskSetupFailure): StringResource =
    when (failure) {
        TaskSetupFailure.COULD_NOT_SAVE -> Strings.Tasks.errorCouldNotSave
        TaskSetupFailure.CELL_NOT_AVAILABLE -> Strings.Tasks.errorItemUnavailable
    }
