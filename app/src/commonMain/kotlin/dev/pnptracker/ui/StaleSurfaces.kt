package dev.pnptracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Something with a surface open over rows that a restore has just replaced.
 *
 * Every screen in this application reads through a database flow, so a restore
 * refreshes what they *show* without anybody asking. What it cannot refresh is
 * what somebody had open over them: a task panel, a colour being edited, an "are
 * you sure" about an import. Those are anchored to identifiers that were in the
 * database a moment ago and are not now, and the next press would write to
 * something that no longer exists.
 *
 * So each of them is asked to let go. Nothing is saved on the user's behalf and
 * nothing is reopened somewhere else: PLAN 14.4.3 says a restore leaves stale
 * surfaces closed or refreshed, and closing is the only one of those two that
 * cannot silently reinterpret a half-finished form as being about different data.
 *
 * The application does not navigate anywhere afterwards. The user pressed a
 * button in the settings and that is where they still are.
 */
fun interface StaleSurfaces {
    /** Closes whatever is open, keeping nothing. */
    fun abandonOpenWork()
}

/**
 * Watches for a restore and asks every [surfaces] to let go when one lands.
 *
 * A composable rather than a call, because the thing being watched is a piece of
 * state and the reaction has to happen once per restore rather than once per
 * frame. Nothing happens before the first restore, so a freshly started
 * application does not close anything the user has opened.
 */
@Composable
fun CloseStaleSurfacesAfterRestore(
    restoredTick: Int,
    surfaces: List<StaleSurfaces>,
) {
    LaunchedEffect(restoredTick) {
        if (restoredTick > 0) surfaces.forEach { it.abandonOpenWork() }
    }
}
