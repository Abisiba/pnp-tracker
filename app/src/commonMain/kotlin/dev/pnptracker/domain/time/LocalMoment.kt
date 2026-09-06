package dev.pnptracker.domain.time

import kotlin.time.Instant

/**
 * A moment as the user's own calendar and clock read it.
 *
 * An [Instant] is a point on the timeline and says nothing about what a person
 * standing somewhere would call it. Every moment shown on screen has to be
 * turned into one of these first, and this is the only shape that crossing is
 * allowed to take: plain numbers, already in the reader's zone, with no
 * formatting decided yet. What the numbers are arranged into is the text
 * catalogue's business (PLAN 17), which is why nothing here produces a string.
 *
 * Seconds are left out on purpose. Nothing in PLAN 12.15 is a stopwatch, and a
 * history that showed them would be showing precision the user has no use for.
 */
data class LocalMoment(
    val year: Int,
    /** 1 to 12. */
    val month: Int,
    /** 1 to 31. */
    val dayOfMonth: Int,
    /** 0 to 23; this application never writes a twelve hour clock. */
    val hour: Int,
    val minute: Int,
)

/**
 * Reads [instant] in the zone the machine is set to.
 *
 * Platform work, exactly like [dev.pnptracker.domain.text.composedForm]: the
 * calendar rules, the offset in force on that date and the daylight saving
 * history behind it are a library nobody is going to write again here, and PLAN
 * 14.1 does not have a date library on its list.
 */
expect fun localMomentOf(instant: Instant): LocalMoment
