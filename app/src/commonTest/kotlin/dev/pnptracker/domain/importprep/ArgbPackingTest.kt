package dev.pnptracker.domain.importprep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArgbPackingTest {
    @Test
    fun `a colour survives the trip into the stored form and back`() {
        listOf("FF4EA72E", "FF3A7D22", "FFFFFF99", "FFFF0000", "00000000", "FFFFFFFF", "80123456")
            .forEach { hex ->
                assertEquals(hex, unpackArgb(packArgb(hex)), "$hex did not survive")
            }
    }

    @Test
    fun `a colour whose alpha puts it past the top of an int still survives`() {
        // FF4EA72E is larger than Int.MAX_VALUE; packing it wraps into a negative
        // number, which is the ordinary way ARGB is carried.
        val packed = packArgb("FF4EA72E")

        assertEquals(-11622610, packed)
        assertEquals("FF4EA72E", unpackArgb(packed))
    }

    @Test
    fun `no colour is not a colour`() {
        assertNull(packArgb(null))
        assertNull(unpackArgb(null))
    }

    @Test
    fun `a value that is not eight hex characters is refused rather than guessed at`() {
        assertNull(packArgb("4EA72E"))
        assertNull(packArgb(""))
        assertNull(packArgb("ZZZZZZZZ"))
    }
}
