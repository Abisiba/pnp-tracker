package dev.pnptracker.domain.model

/** How progress on a task is counted. */
enum class TrackingMode {
    /** One main print run, then only missing or failed pieces are reported. */
    THREE_D_BATCH,

    /** Fixed stages, each with its own completed count. */
    PIPELINE,

    /** Done or not done. */
    CHECKLIST,

    /** A plain completed-out-of-total count. */
    COUNTED,
}
