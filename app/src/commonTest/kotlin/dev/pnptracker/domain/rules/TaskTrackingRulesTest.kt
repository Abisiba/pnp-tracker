package dev.pnptracker.domain.rules

import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskTrackingRulesTest {
    @Test
    fun `each pool allows exactly the modes the plan describes`() {
        assertEquals(setOf(TrackingMode.THREE_D_BATCH), allowedTrackingModes.getValue(PoolType.THREE_D))
        assertEquals(setOf(TrackingMode.PIPELINE), allowedTrackingModes.getValue(PoolType.CARD))
        assertEquals(setOf(TrackingMode.PIPELINE), allowedTrackingModes.getValue(PoolType.BOARD))
        assertEquals(
            setOf(TrackingMode.CHECKLIST, TrackingMode.COUNTED),
            allowedTrackingModes.getValue(PoolType.SPECIAL),
        )
    }

    @Test
    fun `every pool has a rule`() {
        assertEquals(PoolType.entries.toSet(), allowedTrackingModes.keys)
    }

    @Test
    fun `the allowed combinations are accepted`() {
        assertTrue(isTrackingModeAllowed(PoolType.THREE_D, TrackingMode.THREE_D_BATCH))
        assertTrue(isTrackingModeAllowed(PoolType.CARD, TrackingMode.PIPELINE))
        assertTrue(isTrackingModeAllowed(PoolType.BOARD, TrackingMode.PIPELINE))
        assertTrue(isTrackingModeAllowed(PoolType.SPECIAL, TrackingMode.CHECKLIST))
        assertTrue(isTrackingModeAllowed(PoolType.SPECIAL, TrackingMode.COUNTED))
    }

    @Test
    fun `every other combination is refused`() {
        val allowed =
            PoolType.entries.flatMap { pool ->
                allowedTrackingModes.getValue(pool).map { mode -> pool to mode }
            }
        val everyCombination = PoolType.entries.flatMap { pool -> TrackingMode.entries.map { mode -> pool to mode } }

        (everyCombination - allowed.toSet()).forEach { (pool, mode) ->
            assertFalse(isTrackingModeAllowed(pool, mode), "$pool must not allow $mode")
            assertFailsWith<IllegalArgumentException>("$pool with $mode should have been refused") {
                requireAllowedTrackingMode(pool, mode)
            }
        }
    }

    @Test
    fun `the refusal names the pool and the modes it does allow`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                requireAllowedTrackingMode(PoolType.CARD, TrackingMode.THREE_D_BATCH)
            }

        assertTrue(failure.message.orEmpty().contains("CARD"))
        assertTrue(failure.message.orEmpty().contains("PIPELINE"))
    }
}
