package dev.pnptracker.domain.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the runtime's own segmentation really does with real text.
 *
 * Written because the choice of segmentation cannot be made on the name of a
 * class. A task's name is split across its colours over exactly these
 * boundaries (PLAN 12.7), so a segmentation that thought `👍🏽` was two
 * characters would paint half an emoji in one colour and its skin tone in
 * another, and one that thought `🇹🇷` was two would take a flag apart.
 *
 * Every case here is a shape a user can really type into a cell of their own
 * table.
 */
class GraphemesTest {
    @Test
    fun `a letter and its combining accent are one character`() {
        assertEquals(listOf("á"), graphemesOf("á"))
    }

    @Test
    fun `Turkish letters are one character each, written either way`() {
        assertEquals(listOf("g", "ü", "ç", "l", "ü", "k"), graphemesOf("güçlük"))
        // The dotted capital I as a letter plus a combining dot above, which is
        // how it arrives from some keyboards and from decomposed text.
        assertEquals(listOf("i̇", "s"), graphemesOf("i̇s"))
    }

    @Test
    fun `an emoji written as a surrogate pair is one character`() {
        val smile = "😀"

        assertEquals(2, smile.length, "the fixture is not a surrogate pair")
        assertEquals(listOf(smile), graphemesOf(smile))
    }

    @Test
    fun `an emoji and its skin tone are one character`() {
        val thumb = "👍🏽"

        assertEquals(listOf(thumb), graphemesOf(thumb))
    }

    @Test
    fun `a family joined by zero width joiners is one character`() {
        val family = "👨‍👩‍👧‍👦"

        assertEquals(11, family.length, "the fixture is not the joined sequence")
        assertEquals(listOf(family), graphemesOf(family))
    }

    @Test
    fun `a flag is one character and two flags are two`() {
        val turkey = "🇹🇷"
        val germany = "🇩🇪"

        assertEquals(listOf(turkey), graphemesOf(turkey))
        assertEquals(listOf(turkey, germany), graphemesOf(turkey + germany))
    }

    @Test
    fun `spaces and punctuation are characters of their own`() {
        assertEquals(listOf("a", ",", " ", "b", "!"), graphemesOf("a, b!"))
    }

    @Test
    fun `the boundaries run from nothing to the whole text, in order`() {
        val text = "Yarasa 🇹🇷"
        val boundaries = graphemeBoundariesOf(text)

        assertEquals(0, boundaries.first())
        assertEquals(text.length, boundaries.last())
        assertEquals(boundaries.sorted(), boundaries, "the boundaries are not in order")
        assertEquals(boundaries.distinct(), boundaries, "a boundary is given twice")
        // Every character exactly once, in the order they were written.
        assertEquals(text, graphemesOf(text).joinToString(separator = ""))
    }

    @Test
    fun `empty text has no characters and one boundary`() {
        assertEquals(listOf(0), graphemeBoundariesOf(""))
        assertTrue(graphemesOf("").isEmpty())
    }
}
