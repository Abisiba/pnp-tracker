package dev.pnptracker.domain.rules

import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode

/**
 * Which tracking modes each production pool allows.
 *
 * Three dimensional prints are counted as one batch plus the pieces that failed;
 * cards and board pieces move through fixed stages; only the special pool is free
 * to be either a checklist or a plain count.
 */
val allowedTrackingModes: Map<PoolType, Set<TrackingMode>> =
    mapOf(
        PoolType.THREE_D to setOf(TrackingMode.THREE_D_BATCH),
        PoolType.CARD to setOf(TrackingMode.PIPELINE),
        PoolType.BOARD to setOf(TrackingMode.PIPELINE),
        PoolType.SPECIAL to setOf(TrackingMode.CHECKLIST, TrackingMode.COUNTED),
    )

/**
 * The tracking mode each pool gives a task made from now on.
 *
 * One per pool, so no screen asks the question: a special task is an ordinary
 * task with a quantity, made and finished the way the other pools' tasks are.
 * The checklist mode is still [allowed][allowedTrackingModes] so the special
 * tasks already written that way, and the backups that carry them, stay valid;
 * it is only no longer offered.
 */
val offeredTrackingModes: Map<PoolType, TrackingMode> =
    mapOf(
        PoolType.THREE_D to TrackingMode.THREE_D_BATCH,
        PoolType.CARD to TrackingMode.PIPELINE,
        PoolType.BOARD to TrackingMode.PIPELINE,
        PoolType.SPECIAL to TrackingMode.COUNTED,
    )

fun isTrackingModeAllowed(
    poolType: PoolType,
    trackingMode: TrackingMode,
): Boolean = trackingMode in allowedTrackingModes.getValue(poolType)

/**
 * @throws IllegalArgumentException if [trackingMode] cannot be used with [poolType].
 */
fun requireAllowedTrackingMode(
    poolType: PoolType,
    trackingMode: TrackingMode,
) {
    require(isTrackingModeAllowed(poolType, trackingMode)) {
        "$poolType tasks cannot be tracked as $trackingMode; allowed: ${allowedTrackingModes.getValue(poolType)}"
    }
}
