package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.text.graphemesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a task's name is shared out among the colours it is made in.
 *
 * PLAN 12.7 gives the rule: over the user's own characters, in slot order, with
 * anything left over going to the first colours. The product decision recorded
 * for a name shorter than its colour list is here too — the colours with no
 * character of their own are kept and drawn as swatches, never dropped, and
 * never a reason to refuse a colour.
 */
class TaskColorLayoutTest {
    /** What each colour is drawn with: its share of the name, or nothing. */
    private fun partsOf(
        name: String,
        colorCount: Int,
    ): List<String?> {
        val layout = taskColorLayoutOf(name, colorCount)
        val parts = arrayOfNulls<String>(colorCount)
        layout.slices.forEach { parts[it.slotIndex] = name.substring(it.start, it.end) }
        return parts.toList()
    }

    @Test
    fun `a name that divides evenly is shared out equally`() {
        assertEquals(listOf("ya", "ra", "sa"), partsOf("yarasa", 3))
    }

    @Test
    fun `what is left over goes to the first colours`() {
        // PLAN 12.7: six characters in four colours is 2, 2, 1, 1.
        assertEquals(listOf("Ya", "ra", "s", "a"), partsOf("Yarasa", 4))
        // Five in two is 3 and 2.
        assertEquals(listOf("Tok", "en"), partsOf("Token", 2))
        // Seven in three is 3, 2, 2.
        assertEquals(listOf("Kır", "mı", "zı"), partsOf("Kırmızı", 3))
    }

    @Test
    fun `the pieces are the name's own characters, unchanged and in order`() {
        val name = "Şövalye Kılıcı"
        val parts = partsOf(name, 3).map { it.orEmpty() }

        assertEquals(name, parts.joinToString(separator = ""))
    }

    @Test
    fun `one colour takes the whole name and none takes nothing`() {
        assertEquals(listOf("Yarasa"), partsOf("Yarasa", 1))
        assertEquals(emptyList(), partsOf("Yarasa", 0))
    }

    @Test
    fun `more colours than characters give the first colours one each`() {
        // The product decision: `Ok` in four colours is O, k, and two colours
        // that get no character and are drawn beside the word instead.
        assertEquals(listOf("O", "k", null, null), partsOf("Ok", 4))
        assertEquals(listOf(2, 3), taskColorLayoutOf("Ok", 4).markerSlots)
    }

    @Test
    fun `a one character name in two colours keeps both`() {
        assertEquals(listOf("A", null), partsOf("A", 2))
        assertEquals(listOf(1), taskColorLayoutOf("A", 2).markerSlots)
    }

    @Test
    fun `a flag is one character and is not taken apart by a third colour`() {
        val flag = "🇹🇷"

        assertEquals(listOf(flag, null, null), partsOf(flag, 3))
        assertEquals(listOf(1, 2), taskColorLayoutOf(flag, 3).markerSlots)
    }

    @Test
    fun `an emoji with a skin tone stays whole beside another character`() {
        val name = "👍🏽A"

        assertEquals(listOf("👍🏽", "A"), partsOf(name, 2))
    }

    @Test
    fun `a family emoji is one character however long it is written`() {
        val family = "👨‍👩‍👧‍👦"

        assertEquals(listOf(family, null), partsOf(family, 2))
    }

    @Test
    fun `a combining accent stays with the letter it belongs to`() {
        assertEquals(listOf("á", "b"), partsOf("áb", 2))
    }

    @Test
    fun `no colour is ever left out, however many there are`() {
        val name = "Yarasa"

        (1..12).forEach { colorCount ->
            val layout = taskColorLayoutOf(name, colorCount)

            assertEquals(
                colorCount,
                layout.colorCount,
                "a colour went missing with $colorCount of them",
            )
            assertEquals(
                (0..<colorCount).toList(),
                (layout.slices.map { it.slotIndex } + layout.markerSlots).sorted(),
                "the slots are not each accounted for once with $colorCount colours",
            )
        }
    }

    @Test
    fun `every character is drawn exactly once whatever the split`() {
        val name = "Yarasa 🇹🇷 á"
        val characters = graphemesOf(name)

        (1..8).forEach { colorCount ->
            val parts = partsOf(name, colorCount).map { it.orEmpty() }

            assertEquals(name, parts.joinToString(separator = ""), "the name changed at $colorCount colours")
            assertEquals(
                characters.size,
                parts.sumOf { graphemesOf(it).size },
                "a character was lost or doubled at $colorCount colours",
            )
        }
    }

    @Test
    fun `the shares never differ by more than one and the longer ones come first`() {
        val name = "Kırmızı Yarasa"

        (1..7).forEach { colorCount ->
            val sizes = partsOf(name, colorCount).map { graphemesOf(it.orEmpty()).size }

            assertEquals(sizes.sortedDescending(), sizes, "a later colour got more than an earlier one")
            assertTrue(sizes.max() - sizes.min() <= 1, "the shares are uneven at $colorCount colours: $sizes")
        }
    }

    @Test
    fun `the rule can be checked on boundaries alone`() {
        // Nine characters, whatever they were written as.
        val boundaries = (0..9).toList()

        val layout = taskColorLayoutOf(boundaries, colorCount = 4)

        assertEquals(listOf(0 to 3, 3 to 5, 5 to 7, 7 to 9), layout.slices.map { it.start to it.end })
        assertTrue(layout.markerSlots.isEmpty())
    }
}
