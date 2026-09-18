package dev.pnptracker.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class EntityTimestampsTest {
    private val created = Instant.fromEpochSeconds(1_700_000_000)
    private val changed = Instant.fromEpochSeconds(1_700_000_600)
    private val removed = Instant.fromEpochSeconds(1_700_001_200)

    @Test
    fun `a new record is created and updated at the same instant`() {
        val timestamps = EntityTimestamps.create(TestClock(created))

        assertEquals(created, timestamps.createdAt)
        assertEquals(created, timestamps.updatedAt)
    }

    @Test
    fun `a new record is active and not deleted`() {
        val timestamps = EntityTimestamps.create(TestClock(created))

        assertNull(timestamps.deletedAt)
        assertTrue(timestamps.isActive)
        assertFalse(timestamps.isDeleted)
    }

    @Test
    fun `creating a record reads the clock exactly once`() {
        val clock = TestClock(created)

        EntityTimestamps.create(clock)

        assertEquals(1, clock.readCount)
    }

    @Test
    fun `touch returns a new value and leaves the original untouched`() {
        val original = EntityTimestamps.create(TestClock(created))
        val clock = TestClock(changed)

        val touched = original.touch(clock)

        assertEquals(changed, touched.updatedAt)
        assertEquals(created, original.updatedAt)
        assertEquals(1, clock.readCount)
    }

    @Test
    fun `touch keeps createdAt`() {
        val original = EntityTimestamps.create(TestClock(created))

        val touched = original.touch(TestClock(changed))

        assertEquals(created, touched.createdAt)
    }

    @Test
    fun `touch moves updatedAt to the new instant and keeps the record active`() {
        val original = EntityTimestamps.create(TestClock(created))

        val touched = original.touch(TestClock(changed))

        assertEquals(changed, touched.updatedAt)
        assertNull(touched.deletedAt)
        assertTrue(touched.isActive)
    }

    @Test
    fun `soft delete stamps updatedAt and deletedAt with the same instant`() {
        val original = EntityTimestamps.create(TestClock(created))

        val deleted = original.softDelete(TestClock(removed))

        assertEquals(removed, deleted.updatedAt)
        assertEquals(removed, deleted.deletedAt)
        assertEquals(created, deleted.createdAt)
    }

    @Test
    fun `a soft deleted record is deleted and no longer active`() {
        val deleted = EntityTimestamps.create(TestClock(created)).softDelete(TestClock(removed))

        assertTrue(deleted.isDeleted)
        assertFalse(deleted.isActive)
    }

    @Test
    fun `soft delete leaves the original value untouched`() {
        val original = EntityTimestamps.create(TestClock(created))

        original.softDelete(TestClock(removed))

        assertNull(original.deletedAt)
        assertEquals(created, original.updatedAt)
        assertTrue(original.isActive)
    }

    @Test
    fun `deleting an already deleted record changes nothing and does not read the clock`() {
        val deleted = EntityTimestamps.create(TestClock(created)).softDelete(TestClock(removed))
        val laterClock = TestClock(Instant.fromEpochSeconds(1_700_009_999))

        val deletedAgain = deleted.softDelete(laterClock)

        assertSame(deleted, deletedAgain)
        assertEquals(removed, deletedAgain.deletedAt)
        assertEquals(removed, deletedAgain.updatedAt)
        assertEquals(0, laterClock.readCount)
    }

    @Test
    fun `a soft deleted record cannot be touched`() {
        val deleted = EntityTimestamps.create(TestClock(created)).softDelete(TestClock(removed))

        val failure = assertFailsWith<IllegalStateException> { deleted.touch(TestClock(changed)) }

        assertTrue(failure.message.orEmpty().contains("deleted"))
    }

    @Test
    fun `a change recorded after the clock went back is a real record, kept as it is`() {
        // PLAN 14.7.3: time order is not an integrity rule.
        val timestamps = EntityTimestamps(createdAt = changed, updatedAt = created)

        assertEquals(changed, timestamps.createdAt)
        assertEquals(created, timestamps.updatedAt)
        assertEquals(created, EntityTimestamps.create(TestClock(changed)).touch(TestClock(created)).updatedAt)
    }

    @Test
    fun `a deletion recorded after the clock went back is a real record, kept as it is`() {
        val deleted = EntityTimestamps(createdAt = changed, updatedAt = created, deletedAt = created)

        assertEquals(created, deleted.deletedAt)
        assertTrue(deleted.isDeleted)
        assertEquals(created, EntityTimestamps.create(TestClock(changed)).softDelete(TestClock(created)).deletedAt)
    }

    @Test
    fun `a deleted record whose deletedAt differs from updatedAt is rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                EntityTimestamps(createdAt = created, updatedAt = removed, deletedAt = changed)
            }

        assertTrue(failure.message.orEmpty().contains("deletedAt"))
    }

    @Test
    fun `equal instants for created and updated are allowed because the clock is not monotonic`() {
        val timestamps = EntityTimestamps(createdAt = created, updatedAt = created)

        val touchedWithTheSameInstant = timestamps.touch(TestClock(created))

        assertEquals(created, touchedWithTheSameInstant.updatedAt)
    }

    @Test
    fun `timestamps use the kotlin time types and not the jvm ones`() {
        val timestamps = EntityTimestamps.create(TestClock(created))

        assertEquals("kotlin.time.Instant", timestamps.createdAt::class.qualifiedName)
        assertEquals("kotlin.time.Instant", timestamps.updatedAt::class.qualifiedName)
    }
}
