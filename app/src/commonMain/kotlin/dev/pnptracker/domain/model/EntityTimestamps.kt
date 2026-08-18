package dev.pnptracker.domain.model

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * When a domain entity was created, last changed, and — for a soft deleted
 * record — when the user removed it.
 *
 * Deletion is a timestamp rather than a flag: `deletedAt` is the single source of
 * truth, so an entity can never disagree with itself about being deleted. A soft
 * deleted record still exists; physical removal is a separate maintenance action.
 */
data class EntityTimestamps(
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        // The system clock is not monotonic, so nothing here assumes that time
        // moves forward between two reads; these only reject states that cannot
        // describe a real record.
        require(updatedAt >= createdAt) {
            "updatedAt ($updatedAt) cannot be before createdAt ($createdAt)."
        }
        if (deletedAt != null) {
            require(deletedAt >= createdAt) {
                "deletedAt ($deletedAt) cannot be before createdAt ($createdAt)."
            }
            require(deletedAt == updatedAt) {
                "A deleted record must carry the deletion as its last change, " +
                    "but deletedAt ($deletedAt) differs from updatedAt ($updatedAt)."
            }
        }
    }

    val isDeleted: Boolean get() = deletedAt != null

    val isActive: Boolean get() = deletedAt == null

    /**
     * Records a change, returning a new value; [createdAt] is never rewritten.
     *
     * @throws IllegalStateException if the record is soft deleted, because that
     *   would separate `updatedAt` from `deletedAt`. Undeleting is not supported yet.
     */
    fun touch(clock: Clock): EntityTimestamps {
        check(isActive) { "A soft deleted record cannot be changed (deleted at $deletedAt)." }
        return copy(updatedAt = clock.now())
    }

    /**
     * Marks the record as deleted without removing it. Calling this on an already
     * deleted record changes nothing and does not read [clock].
     */
    fun softDelete(clock: Clock): EntityTimestamps {
        if (isDeleted) return this
        val deletedNow = clock.now()
        return copy(updatedAt = deletedNow, deletedAt = deletedNow)
    }

    companion object {
        /** Timestamps for a brand new record; [clock] is read exactly once. */
        fun create(clock: Clock): EntityTimestamps {
            val createdNow = clock.now()
            return EntityTimestamps(createdAt = createdNow, updatedAt = createdNow)
        }
    }
}
