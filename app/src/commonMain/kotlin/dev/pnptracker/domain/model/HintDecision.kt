package dev.pnptracker.domain.model

/**
 * What the user has decided about a hint the import found, such as a `**` marker
 * or a green game cell.
 *
 * A hint never starts out accepted: it is either absent ([NONE]) or waiting for
 * the user ([PENDING]). Nothing in the application may read [PENDING] as a yes.
 */
enum class HintDecision {
    /** The import found no hint here. */
    NONE,

    /** A hint was found and is waiting for the user to decide. */
    PENDING,

    /** The user confirmed the hint. */
    ACCEPTED,

    /** The user rejected the hint. */
    REJECTED,
}
