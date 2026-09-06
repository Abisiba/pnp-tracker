package dev.pnptracker.domain.time

import java.time.ZoneId
import kotlin.time.Instant

/**
 * Reads a moment in the machine's own zone, through `java.time`.
 *
 * The zone is asked for on every call rather than kept, so a machine whose zone
 * changes while the window is open — a laptop carried across one, or a system
 * setting corrected — starts reading moments the new way without a restart.
 *
 * Milliseconds are the whole of what a stored moment has (the converters keep
 * epoch milliseconds), so nothing is lost on the way through.
 */
actual fun localMomentOf(instant: Instant): LocalMoment {
    val local =
        java.time.Instant
            .ofEpochMilli(instant.toEpochMilliseconds())
            .atZone(ZoneId.systemDefault())
    return LocalMoment(
        year = local.year,
        month = local.monthValue,
        dayOfMonth = local.dayOfMonth,
        hour = local.hour,
        minute = local.minute,
    )
}
