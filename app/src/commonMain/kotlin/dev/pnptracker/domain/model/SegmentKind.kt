package dev.pnptracker.domain.model

/** What one piece of a cell document is. */
enum class SegmentKind {
    /** Ordinary text the user typed. */
    PLAIN_TEXT,

    /** Stands for exactly one task, and cannot be edited character by character. */
    TASK,
}
