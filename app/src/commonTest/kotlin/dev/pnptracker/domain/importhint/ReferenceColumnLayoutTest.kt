package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceColumnLayoutTest {
    @Test
    fun `each of the seven reference columns names its own source type`() {
        val types = (0..6).map { assertNotNull(ReferenceColumnLayout.suggestionFor(it)).sourceColumnType }

        assertEquals(
            listOf(
                SourceColumnType.GAME,
                SourceColumnType.THREE_D,
                SourceColumnType.CARD,
                SourceColumnType.BOARD,
                SourceColumnType.SPECIAL,
                SourceColumnType.MISSING,
                SourceColumnType.BORROWED,
            ),
            types,
        )
        assertEquals((0..6).toList(), (0..6).map { assertNotNull(ReferenceColumnLayout.suggestionFor(it)).columnIndex })
    }

    @Test
    fun `the four production columns suggest their own pool`() {
        assertEquals(PoolType.THREE_D, assertNotNull(ReferenceColumnLayout.suggestionFor(1)).suggestedPoolType)
        assertEquals(PoolType.CARD, assertNotNull(ReferenceColumnLayout.suggestionFor(2)).suggestedPoolType)
        assertEquals(PoolType.BOARD, assertNotNull(ReferenceColumnLayout.suggestionFor(3)).suggestedPoolType)
        assertEquals(PoolType.SPECIAL, assertNotNull(ReferenceColumnLayout.suggestionFor(4)).suggestedPoolType)
    }

    @Test
    fun `the game column suggests no pool because a game name is not work`() {
        val game = assertNotNull(ReferenceColumnLayout.suggestionFor(0))

        assertNull(game.suggestedPoolType)
        assertFalse(game.needsClassification)
        assertFalse(game.isMissing)
        assertFalse(game.isBorrowed)
    }

    @Test
    fun `missing is not a pool and is marked for the user to classify`() {
        val missing = assertNotNull(ReferenceColumnLayout.suggestionFor(5))

        assertNull(missing.suggestedPoolType, "missing parts must not be swept into the special pool")
        assertTrue(missing.isMissing)
        assertFalse(missing.isBorrowed)
        assertTrue(missing.needsClassification)
    }

    @Test
    fun `borrowed is not a pool and is marked for the user to classify`() {
        val borrowed = assertNotNull(ReferenceColumnLayout.suggestionFor(6))

        assertNull(borrowed.suggestedPoolType)
        assertTrue(borrowed.isBorrowed)
        assertFalse(borrowed.isMissing)
        assertTrue(borrowed.needsClassification)
    }

    @Test
    fun `a column outside the reference layout is not guessed at`() {
        listOf(7, 8, 42).forEach { assertNull(ReferenceColumnLayout.suggestionFor(it)) }
    }

    @Test
    fun `a column suggestion is a suggestion and stays pending`() {
        (0..6).forEach { columnIndex ->
            val hint = ImportHint.ColumnSuggestion(assertNotNull(ReferenceColumnLayout.suggestionFor(columnIndex)))

            assertEquals(HintDecision.PENDING, hint.decision)
        }
    }

    @Test
    fun `a suggestion carries no chosen pool and no tracking mode`() {
        // The type has room for a suggested pool only. There is nowhere to record
        // a selected pool or a tracking mode, so nothing can quietly pick one.
        val names =
            ReferenceColumnSuggestion(0, SourceColumnType.GAME, null)
                .toString()
                .substringAfter('(')
        listOf("selected", "trackingMode").forEach { forbidden ->
            assertFalse(names.contains(forbidden, ignoreCase = true), "a suggestion must not carry $forbidden")
        }
    }
}
