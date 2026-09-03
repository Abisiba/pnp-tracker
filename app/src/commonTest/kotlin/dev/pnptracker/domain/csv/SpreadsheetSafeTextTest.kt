package dev.pnptracker.domain.csv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a spreadsheet is allowed to make of an exported cell, which is nothing.
 *
 * Quoting the field is not this: quoting says where a field ends, and the
 * spreadsheet has already thrown it away by the time it decides whether the
 * value is a formula. So the marker goes into the value itself, and only where
 * it is needed.
 */
class SpreadsheetSafeTextTest {
    @Test
    fun `a value beginning with an equals sign is marked as text`() {
        assertEquals("'=1+1", spreadsheetSafeText("=1+1"))
    }

    @Test
    fun `a value beginning with a plus is marked as text`() {
        assertEquals("'+SUM(A1:A2)", spreadsheetSafeText("+SUM(A1:A2)"))
    }

    @Test
    fun `a value beginning with a minus is marked as text`() {
        assertEquals("'-2+3", spreadsheetSafeText("-2+3"))
    }

    @Test
    fun `a value beginning with an at sign is marked as text`() {
        assertEquals("'@IMPORT", spreadsheetSafeText("@IMPORT"))
    }

    @Test
    fun `leading spaces do not get round it, and are kept`() {
        assertEquals("'  =1+1", spreadsheetSafeText("  =1+1"))
        assertEquals("' -2", spreadsheetSafeText(" -2"), "a non-breaking space is whitespace too")
    }

    @Test
    fun `a value beginning with a tab is marked as text`() {
        assertEquals("'\tKırmızı", spreadsheetSafeText("\tKırmızı"))
    }

    @Test
    fun `a value beginning with a line ending is marked as text`() {
        assertEquals("'\rKırmızı", spreadsheetSafeText("\rKırmızı"))
        assertEquals("'\nKırmızı", spreadsheetSafeText("\nKırmızı"))
    }

    @Test
    fun `ordinary text is left completely alone`() {
        listOf("Kırmızı ev", "12 KIRMIZI", "Harmonies", "not: 5 adet", "😀 ev", "").forEach { value ->
            assertEquals(value, spreadsheetSafeText(value), value)
        }
    }

    @Test
    fun `a value that already begins with an apostrophe gets no second one`() {
        assertEquals("'zaten güvenli", spreadsheetSafeText("'zaten güvenli"))
    }

    @Test
    fun `a dangerous character anywhere but the front changes nothing`() {
        assertEquals("12 = 12", spreadsheetSafeText("12 = 12"))
        assertEquals("a-b", spreadsheetSafeText("a-b"))
    }

    @Test
    fun `a value that is only whitespace is left as it is`() {
        assertEquals("   ", spreadsheetSafeText("   "))
    }
}
