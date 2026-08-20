package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskSetupFailure
import dev.pnptracker.ui.Strings
import org.jetbrains.compose.resources.StringResource

/** The pool names, under the words PLAN 3.4 uses for them. */
fun labelOf(poolType: PoolType): StringResource =
    when (poolType) {
        PoolType.THREE_D -> Strings.Pools.threeD
        PoolType.CARD -> Strings.Pools.card
        PoolType.BOARD -> Strings.Pools.board
        PoolType.SPECIAL -> Strings.Pools.special
    }

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
        TaskSetupFailure.ITEM_NOT_AVAILABLE -> Strings.Tasks.errorItemUnavailable
    }
