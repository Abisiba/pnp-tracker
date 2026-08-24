package dev.pnptracker.domain.model

/**
 * What a recorded movement of production progress was.
 *
 * PLAN 6.3 gives the 3D pool two actions that change how much is still owed —
 * reporting pieces that came out missing or spoiled, and reporting some of them
 * made good — and PLAN 5.12 says both are kept as events rather than as edits to
 * a number. The reason is in PLAN 6.4: the total ever reported is history and is
 * never deleted, so it can only be read back if every report is still there.
 *
 * There is deliberately no third kind for cancelling or correcting an event.
 * Events are append only; a mistake is answered by recording the opposite
 * movement, which leaves both in the history where they belong.
 */
enum class ProgressEventKind {
    /**
     * Pieces came out missing or spoiled and have to be made again.
     *
     * Adds to what is still owed, and sums into the failure total PLAN 6.2 keeps
     * for the life of the task.
     */
    FAILURE_REPORTED,

    /**
     * Some of what was owed has been made good.
     *
     * Takes away from what is still owed and never from the failure total: PLAN
     * 6.3 is explicit that making good does not erase the history of having
     * failed.
     */
    SHORTAGE_RESOLVED,
}
