package dev.pnptracker.domain.colors

import dev.pnptracker.domain.model.IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deciding what a restore would do, before any of it happens.
 *
 * The rule this is all about is one line long: a base colour is missing when its
 * fixed identity is not in the catalogue. Not when a name has changed, not when
 * a value has changed, not when it has been moved. Everything here is a way of
 * saying that back.
 */
class BaseColorRestorePlanTest {
    private val white = baseColors[0]
    private val black = baseColors[1]
    private val grey = baseColors[2]

    private fun asCatalogue(
        colors: List<BaseColor>,
        change: (BaseColor) -> ColorSummary = { ColorSummary(it.id, it.canonicalName, it.hex, it.sortOrder) },
    ) = colors.map(change)

    private fun aCustomColor(
        name: String,
        hex: String = "#7B1F2B",
        sortOrder: Int = 99,
    ) = ColorSummary(IdGenerator.Random.newId(), name, hex, sortOrder)

    @Test
    fun `a full catalogue has nothing missing and nothing in the way`() {
        val plan = planBaseColorRestore(asCatalogue(baseColors), emptyList())

        assertTrue(plan.isNothingMissing)
        assertFalse(plan.canRestore, "there was nothing to do and it offered to do it")
        assertEquals(emptyList(), plan.missing)
        assertEquals(emptyList(), plan.blocked)
    }

    @Test
    fun `a colour whose identity is gone is the one that is missing`() {
        val plan = planBaseColorRestore(asCatalogue(baseColors - grey), emptyList())

        assertEquals(listOf("Gri"), plan.missing.map { it.canonicalName })
        assertTrue(plan.canRestore)
    }

    @Test
    fun `a base colour renamed beyond recognition is not missing`() {
        val edited =
            asCatalogue(baseColors) { base ->
                if (base.id == black.id) {
                    ColorSummary(base.id, "Koyu", "#101820", 47)
                } else {
                    ColorSummary(base.id, base.canonicalName, base.hex, base.sortOrder)
                }
            }

        val plan = planBaseColorRestore(edited, emptyList())

        assertTrue(plan.isNothingMissing, "an edited base colour was offered for restoring")
    }

    @Test
    fun `a colour wearing a missing one's name does not make it present`() {
        val catalogue = asCatalogue(baseColors - grey) + aCustomColor("Gri", "#808080")

        val plan = planBaseColorRestore(catalogue, emptyList())

        assertEquals(listOf("Gri"), plan.missing.map { it.canonicalName }, "a name stood in for an identity")
    }

    @Test
    fun `a name another colour carries stops the restore and says which`() {
        val catalogue = asCatalogue(baseColors - grey) + aCustomColor("GRİ")

        val plan = planBaseColorRestore(catalogue, emptyList())

        assertEquals(
            listOf(BlockedBaseColor("Gri", "#808080", BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR)),
            plan.blocked,
        )
        assertFalse(plan.canRestore)
    }

    @Test
    fun `a name that is an alias stops the restore and says so differently`() {
        val plan = planBaseColorRestore(asCatalogue(baseColors - grey), listOf("gri"))

        assertEquals(
            listOf(BlockedBaseColor("Gri", "#808080", BaseColorRestoreBlock.NAME_TAKEN_BY_ALIAS)),
            plan.blocked,
        )
    }

    @Test
    fun `a colour's own name wins over an alias when both are in the way`() {
        val catalogue = asCatalogue(baseColors - grey) + aCustomColor("Gri")

        val plan = planBaseColorRestore(catalogue, listOf("Gri"))

        // The user can see the colour and rename it; they cannot see the alias.
        assertEquals(BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR, plan.blocked.single().reason)
    }

    @Test
    fun `every colour in the way is named, not only the first`() {
        val catalogue = asCatalogue(baseColors - white - black - grey) + aCustomColor("beyaz") + aCustomColor("gri")

        val plan = planBaseColorRestore(catalogue, listOf("SİYAH"))

        assertEquals(
            listOf(
                "Beyaz" to BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR,
                "Siyah" to BaseColorRestoreBlock.NAME_TAKEN_BY_ALIAS,
                "Gri" to BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR,
            ),
            plan.blocked.map { it.canonicalName to it.reason },
        )
        assertEquals(3, plan.missing.size, "the ones in the way stopped being missing")
    }

    @Test
    fun `one colour in the way stops the others coming back too`() {
        val catalogue = asCatalogue(baseColors - white - black) + aCustomColor("Beyaz")

        val plan = planBaseColorRestore(catalogue, emptyList())

        assertEquals(listOf("Beyaz", "Siyah"), plan.missing.map { it.canonicalName })
        assertFalse(plan.canRestore, "eleven would have gone in without the twelfth")
    }

    @Test
    fun `sharing a value with something is never in the way`() {
        val catalogue = asCatalogue(baseColors - grey) + aCustomColor("Duman", hex = "#808080")

        val plan = planBaseColorRestore(catalogue, emptyList())

        assertEquals(emptyList(), plan.blocked)
        assertTrue(plan.canRestore)
    }

    @Test
    fun `the four Turkish letter i's all count as the same name`() {
        val catalogue = asCatalogue(baseColors - grey) + aCustomColor("GRI")

        val plan = planBaseColorRestore(catalogue, emptyList())

        assertEquals(BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR, plan.blocked.single().reason)
    }

    @Test
    fun `an empty catalogue has all twelve missing and nothing in the way`() {
        val plan = planBaseColorRestore(emptyList(), emptyList())

        assertEquals(12, plan.missing.size)
        assertEquals(emptyList(), plan.blocked)
        assertTrue(plan.canRestore)
        assertEquals(baseColors.map { it.canonicalName }, plan.missing.map { it.canonicalName })
    }

    @Test
    fun `what comes back carries the default name, value and place`() {
        val plan = planBaseColorRestore(asCatalogue(baseColors - grey), emptyList())

        val gri = plan.missing.single()
        assertEquals("Gri", gri.canonicalName)
        assertEquals("#808080", gri.hex)
        assertEquals(2, gri.sortOrder, "the colour would not have gone back to its own place")
    }
}
