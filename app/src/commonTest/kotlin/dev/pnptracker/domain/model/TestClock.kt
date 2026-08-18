package dev.pnptracker.domain.model

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A clock that only moves when a test moves it, and remembers how often it was
 * read so tests can prove that an operation reads the time exactly once.
 */
class TestClock(
    private var current: Instant,
) : Clock {
    var readCount: Int = 0
        private set

    override fun now(): Instant {
        readCount++
        return current
    }

    fun moveTo(instant: Instant) {
        current = instant
    }
}
