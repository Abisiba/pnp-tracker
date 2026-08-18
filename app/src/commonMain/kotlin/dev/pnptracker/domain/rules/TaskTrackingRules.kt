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
