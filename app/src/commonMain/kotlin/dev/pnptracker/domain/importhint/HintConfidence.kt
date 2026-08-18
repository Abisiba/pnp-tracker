package dev.pnptracker.domain.importhint

/**
 * How sure a detector is, on a scale a person can act on.
 *
 * Deliberately coarse: the thresholds behind it belong to the detector that
 * applied them and are explained there. Carrying a number here would invite
 * comparisons that no detector ever promised to support.
 */
enum class HintConfidence {
    /** The evidence matches what the reference file does. */
    HIGH,

    /** Plausible, but near the edge of what the detector recognises. */
    MEDIUM,

    /** Worth showing, but the user should not be nudged towards a yes. */
    LOW,
}
