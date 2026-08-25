package dev.pnptracker.ui.theme

import dev.pnptracker.data.database.seedColors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a task's name can be read on the colour it is made in.
 *
 * The colour is the user's, chosen for filament rather than for legibility, so
 * the ink is the part that has to adapt. PLAN 17 asks for readable text and for
 * colour never to be the only carrier of a meaning; this is the readable half.
 */
class ReadableInkTest {
    @Test
    fun `every colour in the catalogue reads at four and a half to one or better`() {
        seedColors.forEach { color ->
            val ground = opaqueColorOf(color.hex)
            val contrast = contrastRatio(readableInkOn(ground), ground)

            assertTrue(contrast >= 4.5, "${color.canonicalName} reads at only $contrast:1")
        }
    }

    @Test
    fun `no colour whatever falls below what ordinary text needs`() {
        // Swept rather than sampled: the worst case is a middling colour, and it
        // is exactly the sort a user picks. Sixteen steps a channel is fine
        // enough to find the band where neither black nor white would do.
        var worst = Double.MAX_VALUE
        val steps = (0..255 step 17).toList()
        steps.forEach { red ->
            steps.forEach { green ->
                steps.forEach { blue ->
                    val ground = opaqueColorOf("#" + byteOf(red) + byteOf(green) + byteOf(blue))
                    worst = minOf(worst, contrastRatio(readableInkOn(ground), ground))
                }
            }
        }

        assertTrue(worst >= 4.5, "some colour leaves its task unreadable at $worst:1")
    }

    @Test
    fun `dark grounds take light ink and light grounds take dark ink`() {
        assertTrue(relativeLuminance(readableInkOn(opaqueColorOf("#111111"))) > 0.5, "black takes dark ink")
        assertTrue(relativeLuminance(readableInkOn(opaqueColorOf("#FDD835"))) < 0.5, "yellow takes light ink")
        assertTrue(relativeLuminance(readableInkOn(opaqueColorOf("#FFFFFF"))) < 0.5, "white takes light ink")
    }

    @Test
    fun `brightness is judged by the eye rather than by the average of the channels`() {
        // The same numeric value in green and in blue are nothing like as bright
        // to look at, which is why a plain average would choose the wrong ink.
        val green = relativeLuminance(opaqueColorOf("#00FF00"))
        val blue = relativeLuminance(opaqueColorOf("#0000FF"))

        assertTrue(green > blue * 5, "green is not being weighted as the eye weights it")
    }

    // ------------------------------------------- a colour matching the ground

    /** The two grounds a cell is ever drawn on. */
    private val surfaces = listOf("light" to opaqueColorOf("#FFFBFE"), "dark" to opaqueColorOf("#1C1B1F"))

    @Test
    fun `every colour in the catalogue can be seen against both themes`() {
        // The case this exists for: white on a light surface, black on a dark
        // one. Painted with no edge, such a task shows no colour at all.
        seedColors.forEach { color ->
            val fill = opaqueColorOf(color.hex)
            surfaces.forEach { (theme, surface) ->
                val visible =
                    maxOf(contrastRatio(fill, surface), contrastRatio(visibleEdgeOn(fill), surface))
                assertTrue(visible >= 3.0, "${color.canonicalName} is invisible on the $theme surface at $visible:1")
            }
        }
    }

    @Test
    fun `no colour whatever disappears into either theme`() {
        var worst = Double.MAX_VALUE
        var worstAt = ""
        val steps = (0..255 step 17).toList()
        steps.forEach { red ->
            steps.forEach { green ->
                steps.forEach { blue ->
                    val fill = opaqueColorOf("#" + byteOf(red) + byteOf(green) + byteOf(blue))
                    surfaces.forEach { (theme, surface) ->
                        val visible =
                            maxOf(contrastRatio(fill, surface), contrastRatio(visibleEdgeOn(fill), surface))
                        if (visible < worst) {
                            worst = visible
                            worstAt = "#${byteOf(red)}${byteOf(green)}${byteOf(blue)} on $theme"
                        }
                    }
                }
            }
        }

        assertTrue(worst >= 3.0, "a task is invisible: $worstAt at $worst:1")
    }

    @Test
    fun `the edge always stands away from the colour it goes round`() {
        // Which is what makes the boundary visible from the inside as well, and
        // is why one rule covers both cases.
        val steps = (0..255 step 17).toList()
        steps.forEach { red ->
            steps.forEach { green ->
                steps.forEach { blue ->
                    val fill = opaqueColorOf("#" + byteOf(red) + byteOf(green) + byteOf(blue))
                    assertTrue(
                        contrastRatio(visibleEdgeOn(fill), fill) >= 4.5,
                        "the edge cannot be told from the colour it goes round",
                    )
                }
            }
        }
    }

    @Test
    fun `the check asks the colour's value and not its name`() {
        // A test that trusted the word "Beyaz" would pass while the square it
        // stands for was invisible. Every assertion here is arithmetic on the
        // stored value, which is the only thing the user actually sees.
        val white = seedColors.first { it.canonicalName == "Beyaz" }
        val light = opaqueColorOf("#FFFBFE")

        assertTrue(contrastRatio(opaqueColorOf(white.hex), light) < 1.2, "white is not the case this is about")
        assertTrue(contrastRatio(visibleEdgeOn(opaqueColorOf(white.hex)), light) >= 3.0, "white has no visible edge")
    }

    private fun byteOf(value: Int): String = value.toString(16).padStart(2, '0')
}
