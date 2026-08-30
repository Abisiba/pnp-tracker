package dev.pnptracker.ui.feature.colors

import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.WheelNudge
import dev.pnptracker.domain.colors.WheelPoint
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.model.IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The form's own rules, with no screen and no storage in the way. */
class ColorComposerTest {
    private fun aColor(
        name: String,
        hex: String,
        sortOrder: Int = 0,
    ) = ColorSummary(IdGenerator.Random.newId(), name, hex, sortOrder)

    @Test
    fun `a form opened on a colour opens on that colour and on no name`() {
        val composer = ColorComposer.startingFrom("#1A237E")

        assertEquals("#1A237E", composer.hex)
        assertEquals("", composer.name, "a name nobody chose was offered")
        assertTrue(!composer.canSave, "a colour with no name could be saved")
    }

    @Test
    fun `a form opened on nothing still has somewhere to start`() {
        assertEquals(ColorComposer.FALLBACK_START, ColorComposer.startingFrom(null).hex)
    }

    @Test
    fun `a form nobody has touched is known to be untouched`() {
        val composer = ColorComposer.startingFrom("#1A237E")
        assertTrue(!composer.isTouched)

        assertTrue(composer.copy(name = "a").isTouched, "a typed name did not count as a change")
        assertTrue(composer.brightenedTo(0.1f).isTouched, "a moved brightness did not count as a change")
        assertTrue(composer.nudgedBy(WheelNudge.HUE_FORWARD).isTouched, "a moved wheel did not count as a change")
        assertTrue(
            composer.copy(name = " ").isTouched,
            "a space is still something the user typed, and losing it silently is still losing it",
        )
    }

    @Test
    fun `a name of nothing but spaces is not a name`() {
        val composer = ColorComposer.startingFrom("#1A237E")
        listOf("", " ", "   ", "\t").forEach { attempt ->
            assertTrue(!composer.copy(name = attempt).canSave, "'$attempt' was accepted as a name")
        }
    }

    @Test
    fun `the name is offered to storage with the spaces at its ends left behind`() {
        assertEquals("Lacivert", ColorComposer.startingFrom("#1A237E").copy(name = "  Lacivert  ").cleanName)
    }

    @Test
    fun `the value written is the one the wheel is on`() {
        val composer = ColorComposer.startingFrom("#FFFFFF").setTo("#43A047")

        assertEquals("#43A047", composer.hex)
    }

    @Test
    fun `moving the wheel leaves the brightness where it was`() {
        val composer = ColorComposer.startingFrom("#1A237E").brightenedTo(0.3f)

        val moved = composer.movedTo(WheelPoint(30f, -30f), radius = 50f)

        assertEquals(0.3f, moved.color.brightness)
    }

    @Test
    fun `a value another colour carries is remarked on by name`() {
        val catalogue = listOf(aColor("Gri", "#808080"), aColor("Duman", "#808080"), aColor("Mavi", "#1E88E5"))

        val sharing = ColorComposer.startingFrom("#808080").sharedWith(catalogue)

        assertEquals(listOf("Gri", "Duman"), sharing.map { it.canonicalName })
    }

    @Test
    fun `the remark does not care how the value was typed`() {
        val catalogue = listOf(aColor("Gri", "#808080"))

        // Every value the picker produces is upper case, but the catalogue holds
        // what was written into it, and `#808080` is the same colour either way.
        assertEquals(listOf("Gri"), ColorComposer.startingFrom("#808080").sharedWith(catalogue).map { it.canonicalName })
        assertEquals(
            listOf("Gri"),
            ColorComposer.startingFrom("#808080").sharedWith(listOf(aColor("Gri", "#808080".lowercase()))).map { it.canonicalName },
        )
    }

    @Test
    fun `a value nobody carries is remarked on by nobody`() {
        assertEquals(emptyList(), ColorComposer.startingFrom("#123456").sharedWith(listOf(aColor("Gri", "#808080"))))
    }

    @Test
    fun `the remark never stands in the way of saving`() {
        val composer = ColorComposer.startingFrom("#808080").copy(name = "Duman")

        assertTrue(composer.sharedWith(listOf(aColor("Gri", "#808080"))).isNotEmpty())
        assertTrue(composer.canSave, "a value another colour carries stopped a save PLAN 5.7 allows")
    }

    @Test
    fun `the squares offered are the base colours, in the catalogue's own order`() {
        val catalogue =
            baseColors.map { ColorSummary(it.id, it.canonicalName, it.hex, it.sortOrder) } +
                aColor("Lacivert", "#1A237E", sortOrder = 12)

        val squares = baseColorsIn(catalogue)

        assertEquals(12, squares.size, "a colour that is not a base one was offered as a square")
        assertEquals(baseColors.map { it.canonicalName }, squares.map { it.canonicalName })
    }

    @Test
    fun `a square shows what the user made of it, not what it started as`() {
        val renamed =
            baseColors.map { base ->
                ColorSummary(
                    id = base.id,
                    canonicalName = if (base.canonicalName == "Gri") "Duman" else base.canonicalName,
                    hex = if (base.canonicalName == "Gri") "#9E9E9E" else base.hex,
                    sortOrder = base.sortOrder,
                )
            }

        val square = baseColorsIn(renamed)[2]

        assertEquals("Duman", square.canonicalName)
        assertEquals("#9E9E9E", square.hex)
    }

    @Test
    fun `a base colour that has been removed is simply not offered`() {
        val short = baseColors.drop(3).map { ColorSummary(it.id, it.canonicalName, it.hex, it.sortOrder) }

        // Putting it back is PLAN 12.14's restore, which belongs to a later step;
        // nothing here quietly recreates it.
        assertEquals(9, baseColorsIn(short).size)
        assertEquals("Kahverengi", baseColorsIn(short).first().canonicalName)
    }

    // ------------------------------------------------------------------ editing

    private fun aCatalogueColor(
        name: String = "Gri",
        hex: String = "#808080",
    ) = ColorSummary(IdGenerator.Random.newId(), name, hex, 2)

    @Test
    fun `a form opened on a colour starts on its name and its value`() {
        val color = aCatalogueColor()

        val composer = ColorComposer.editingOf(color)

        assertEquals("Gri", composer.name)
        assertEquals("#808080", composer.hex)
        assertEquals(color.id, composer.editing)
        assertEquals("Gri", composer.startedName)
        assertEquals("#808080", composer.startedHex)
    }

    @Test
    fun `an edit form nobody has touched knows it has nothing to lose`() {
        val composer = ColorComposer.editingOf(aCatalogueColor())

        assertFalse(composer.isTouched)
        assertTrue(composer.isNoOp, "a form that would write the row back unchanged is not a change")
    }

    @Test
    fun `typing a different name makes it a change`() {
        val composer = ColorComposer.editingOf(aCatalogueColor()).copy(name = "Duman")

        assertTrue(composer.isTouched)
        assertFalse(composer.isNoOp)
    }

    @Test
    fun `changing only the letter case is a change`() {
        val composer = ColorComposer.editingOf(aCatalogueColor()).copy(name = "GRİ")

        assertTrue(composer.isTouched)
        assertFalse(composer.isNoOp, "the user's own spelling would have been thrown away silently")
    }

    @Test
    fun `spaces at the ends of an unchanged name are still no change`() {
        val composer = ColorComposer.editingOf(aCatalogueColor()).copy(name = "  Gri  ")

        assertTrue(composer.isTouched, "the field says something different from what it opened on")
        assertTrue(composer.isNoOp, "trimming makes it the same name, so there is nothing to write")
    }

    @Test
    fun `moving the wheel makes it a change even with the same name`() {
        // A colour with a hue to move: turning the wheel on a grey changes
        // nothing, because a grey has no hue to turn.
        val composer = ColorComposer.editingOf(aCatalogueColor("Mavi", "#1E88E5")).nudgedBy(WheelNudge.HUE_FORWARD)

        assertTrue(composer.isTouched)
        assertFalse(composer.isNoOp)
    }

    @Test
    fun `coming back to exactly where it started is not a change`() {
        val start = ColorComposer.editingOf(aCatalogueColor("Mavi", "#1E88E5"))

        val there = start.nudgedBy(WheelNudge.HUE_FORWARD).nudgedBy(WheelNudge.HUE_BACK)

        assertEquals(start.hex, there.hex)
        assertTrue(there.isNoOp)
    }

    @Test
    fun `a colour being edited is never listed as sharing its own value`() {
        val grey = aCatalogueColor()
        val other = ColorSummary(IdGenerator.Random.newId(), "Duman", "#808080", 12)
        val composer = ColorComposer.editingOf(grey)

        assertEquals(listOf("Duman"), composer.sharedWith(listOf(grey, other)).map { it.canonicalName })
    }

    @Test
    fun `a colour being made is compared against the whole catalogue`() {
        val grey = aCatalogueColor()
        val composer = ColorComposer.startingFrom("#808080")

        assertEquals(listOf("Gri"), composer.sharedWith(listOf(grey)).map { it.canonicalName })
        assertNull(composer.editing)
        assertFalse(composer.isNoOp, "a colour that does not exist yet was called a no-op")
    }

    @Test
    fun `an edit form still refuses an empty name`() {
        val composer = ColorComposer.editingOf(aCatalogueColor()).copy(name = "   ")

        assertFalse(composer.isNameUsable)
        assertFalse(composer.canSave)
    }

    @Test
    fun `a form for making a colour is unchanged by all of this`() {
        val composer = ColorComposer.startingFrom("#808080")

        assertEquals("", composer.name)
        assertEquals("", composer.startedName)
        assertNull(composer.startedHex)
        assertFalse(composer.isTouched)
        assertFalse(composer.canSave, "a colour with no name could be saved")
    }
}
