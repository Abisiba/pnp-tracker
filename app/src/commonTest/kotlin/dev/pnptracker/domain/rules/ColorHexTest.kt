package dev.pnptracker.domain.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColorHexTest {
    @Test
    fun `six digit values with a leading hash are accepted in either case`() {
        listOf("#FFFFFF", "#111111", "#4fc3f7", "#4FC3F7", "#aB12Cd").forEach { hex ->
            assertTrue(isValidColorHex(hex), "should have been accepted: $hex")
            assertEquals(hex, requireValidColorHex(hex))
        }
    }

    @Test
    fun `anything that is not exactly six hex digits is refused`() {
        listOf("", "#", "FFFFFF", "#FFF", "#FFFFFFF", "#GGGGGG", "#FFFFF", " #FFFFFF", "#FFFFFF ").forEach { hex ->
            assertFalse(isValidColorHex(hex), "should have been refused: '$hex'")
            assertFailsWith<IllegalArgumentException>("should have been refused: '$hex'") {
                requireValidColorHex(hex)
            }
        }
    }
}
