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

    private fun byteOf(value: Int): String = value.toString(16).padStart(2, '0')
}
