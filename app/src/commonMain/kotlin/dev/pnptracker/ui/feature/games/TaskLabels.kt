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
 * Every case is spelled out rather than falling back to one general apology,
 * because the next step differs: try again, wait for the cell list to catch up,
 * aim at a column that takes tasks, or settle the disagreement between the pool
 * and the column. The `when` is exhaustive on purpose, so a failure added later
 * has to be given words before the code will build.
 */
fun messageOf(failure: TaskSetupFailure): StringResource =
    when (failure) {
        TaskSetupFailure.COULD_NOT_SAVE -> Strings.Tasks.errorCouldNotSave
        TaskSetupFailure.CELL_NOT_AVAILABLE -> Strings.Tasks.errorCellUnavailable
        TaskSetupFailure.CELL_DOES_NOT_HOLD_TASKS -> Strings.Tasks.errorCellHoldsNoTasks
        TaskSetupFailure.CELL_POOL_MISMATCH -> Strings.Tasks.errorCellPoolMismatch
    }
