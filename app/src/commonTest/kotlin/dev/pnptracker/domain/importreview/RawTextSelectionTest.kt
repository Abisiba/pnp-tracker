package dev.pnptracker.domain.importreview

import dev.pnptracker.domain.text.graphemeBoundariesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The contract for cutting a task name out of a source cell.
 *
 * Every boundary here is checked against the user's own characters rather than
 * against code units, so the cases are written as real text: a letter and its
 * accent, a surrogate pair, a skin tone, a family, a flag, a `\r\n`. Each of
 * them is several code units and exactly one character, and a cut inside one
 * would store half of something.
 */
class RawTextSelectionTest {
    private fun refusalOf(
        text: String,
        start: Int,
        end: Int,
    ): ImportReviewFailure = assertFailsWith<ImportReviewException> { selectTaskNameIn(text, start, end) }.failure

    // ------------------------------------------------------- the plain rules

    @Test
    fun `a selection keeps exactly the characters it covers`() {
        val selection = selectTaskNameIn("40 token ×14", 3, 8)

        assertEquals("token", selection.name)
        assertEquals(3, selection.startIndex)
        assertEquals(8, selection.endIndex)
    }

    @Test
    fun `whitespace at the edges moves the boundaries inward and stays in the cell`() {
        val text = "40 token ×14"
        val selection = selectTaskNameIn(text, 2, 9)

        assertEquals("token", selection.name)
        assertEquals(3, selection.startIndex, "the space before the word is not part of the name")
        assertEquals(8, selection.endIndex)
        assertEquals("40 token ×14", text, "the cell text is never rewritten")
    }

    @Test
    fun `punctuation left outside the selection stays outside it`() {
        val selection = selectTaskNameIn("(kırmızı token), 14 adet", 1, 14)

        assertEquals("kırmızı token", selection.name)
    }

    @Test
    fun `punctuation taken inside the selection is part of the name`() {
        val selection = selectTaskNameIn("kırmızı token, 14 adet", 0, 14)

        assertEquals("kırmızı token,", selection.name)
    }

    @Test
    fun `a selection of nothing but whitespace is refused`() {
        assertEquals(ImportReviewFailure.SELECTION_IS_EMPTY, refusalOf("40   token", 2, 5))
    }

    @Test
    fun `a selection of nothing but the marker is refused`() {
        // `**` is cleared from the shown name (PLAN 11.5), so on its own it
        // leaves nothing for a task to be called.
        assertEquals(ImportReviewFailure.SELECTION_IS_EMPTY, refusalOf("token ** burada", 6, 8))
    }

    @Test
    fun `an empty selection is refused`() {
        assertEquals(ImportReviewFailure.INVALID_SELECTION, refusalOf("token", 2, 2))
    }

    @Test
    fun `a backwards selection is refused`() {
        assertEquals(ImportReviewFailure.INVALID_SELECTION, refusalOf("token", 4, 1))
    }

    @Test
    fun `a selection reaching past the end of the cell is refused`() {
        assertEquals(ImportReviewFailure.INVALID_SELECTION, refusalOf("token", 0, 9))
    }

    @Test
    fun `a selection starting before the cell is refused`() {
        assertEquals(ImportReviewFailure.INVALID_SELECTION, refusalOf("token", -1, 3))
    }

    @Test
    fun `a name running across a line ending is refused rather than straightened`() {
        assertEquals(
            ImportReviewFailure.SELECTION_CONTAINS_LINE_BREAK,
            refusalOf("gri token\n26 ağaç", 4, 12),
        )
    }

    @Test
    fun `a line ending at the very edge of the selection is only whitespace`() {
        val selection = selectTaskNameIn("gri token\n26 ağaç", 0, 10)

        assertEquals("gri token", selection.name)
        assertEquals(9, selection.endIndex)
    }

    // ----------------------------------------------------------- the marker

    @Test
    fun `the marker is cleared from the name and left in the cell`() {
        val text = "15 KIRMIZI** kalan"
        val selection = selectTaskNameIn(text, 0, 12)

        assertEquals("15 KIRMIZI", selection.name)
        assertEquals("15 KIRMIZI**", selection.selectedText, "what was selected is kept as it was")
        assertTrue(selection.hasCompletionMarker)
        assertEquals("15 KIRMIZI** kalan", text)
    }

    @Test
    fun `a selection with no marker says so`() {
        assertTrue(!selectTaskNameIn("15 KIRMIZI", 0, 10).hasCompletionMarker)
    }

    @Test
    fun `a single star is ordinary text and stays in the name`() {
        val selection = selectTaskNameIn("token* burada", 0, 6)

        assertEquals("token*", selection.name)
        assertTrue(!selection.hasCompletionMarker)
    }

    // --------------------------------------------------- the user's characters

    private fun assertUnsplittable(
        text: String,
        inside: Int,
    ) {
        val boundaries = graphemeBoundariesOf(text)
        assertTrue(inside !in boundaries, "the fixture must really point inside one character")
        assertEquals(ImportReviewFailure.SELECTION_SPLITS_A_CHARACTER, refusalOf(text, 0, inside))
        assertEquals(ImportReviewFailure.SELECTION_SPLITS_A_CHARACTER, refusalOf(text, inside, text.length))
    }

    @Test
    fun `a letter is not parted from its combining accent`() {
        assertUnsplittable("á token", 1)
    }

    @Test
    fun `a Turkish letter written with a combining cedilla is not split`() {
        assertUnsplittable("şeker", 1)
    }

    @Test
    fun `a surrogate pair is not split`() {
        assertUnsplittable("😀 token", 1)
    }

    @Test
    fun `a skin tone emoji is not split`() {
        assertUnsplittable("👍🏽 token", 2)
    }

    @Test
    fun `a family made of joined emoji is not split`() {
        assertUnsplittable("👨‍👩‍👧‍👦 aile", 5)
    }

    @Test
    fun `the Turkish flag is not split`() {
        assertUnsplittable("🇹🇷 bayrak", 2)
    }

    @Test
    fun `a CRLF line ending is one character and is not split`() {
        assertUnsplittable("ab\r\ncd", 3)
    }

    @Test
    fun `Turkish letters survive a selection unchanged`() {
        val selection = selectTaskNameIn("Ağaçtaki ışığı gördüm", 9, 14)

        assertEquals("ışığı", selection.name)
    }

    @Test
    fun `a whole emoji may be part of a name`() {
        val text = "👨‍👩‍👧‍👦 aile kartı"
        val selection = selectTaskNameIn(text, 0, text.length)

        assertEquals(text, selection.name)
        assertEquals(0, selection.startIndex)
        assertEquals(text.length, selection.endIndex)
    }
}
