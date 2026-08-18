package dev.pnptracker.domain.model

/** How a color relates to a task. */
enum class ColorRelation {
    /** The task must be produced in this color. */
    REQUIRED,

    /** One of several colors the user may pick from. */
    ALTERNATIVE,
}
