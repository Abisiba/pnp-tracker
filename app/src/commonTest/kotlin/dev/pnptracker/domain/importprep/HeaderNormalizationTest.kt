package dev.pnptracker.domain.importprep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeaderNormalizationTest {
    @Test
    fun `the ends are trimmed and runs of whitespace become one space`() {
        assertEquals("3d print", normalizeHeader("  3D   PRINT \n"))
        assertEquals("mukavva", normalizeHeader("\tMukavva\t"))
    }

    @Test
    fun `case is ignored, including for turkish letters`() {
        assertEquals("özel", normalizeHeader("ÖZEL"))
        assertEquals("özel", normalizeHeader("Özel"))
        assertEquals("ödünç parçalar", normalizeHeader("ÖDÜNÇ PARÇALAR"))
    }

    @Test
    fun `the dot left behind by lower casing a dotted capital i is dropped`() {
        assertEquals("eksik", normalizeHeader("EKSİK"))
        assertEquals("eksik", normalizeHeader("Eksik"))
        assertEquals("laminasyon", normalizeHeader("LAMİNASYON"))
    }

    @Test
    fun `the two turkish i letters are kept apart`() {
        // This is the difference from the colour normaliser, which folds them
        // together so that colours typed on different keyboards meet. A heading
        // is not a colour, and merging words here would hide a real mismatch.
        assertTrue(normalizeHeader("adı") != normalizeHeader("adi"))
    }

    @Test
    fun `an empty heading normalises to nothing, which is what an empty A1 is`() {
        assertEquals("", normalizeHeader(""))
        assertEquals("", normalizeHeader("   "))
    }

    @Test
    fun `the recognised headings cover the spellings the reference file uses`() {
        assertTrue(ReferenceSheetLayout.isKnownHeader(0, ""))
        assertTrue(ReferenceSheetLayout.isKnownHeader(0, "Oyun"))
        assertTrue(ReferenceSheetLayout.isKnownHeader(0, "Oyun Adı"))
        assertTrue(ReferenceSheetLayout.isKnownHeader(0, "OYUN ADI"), "capitals are how a heading is often typed")
        assertTrue(ReferenceSheetLayout.isKnownHeader(1, "3D Print (Figür vb.)"))
        assertTrue(ReferenceSheetLayout.isKnownHeader(2, "Laminasyon (Kart vb.)"))
        assertTrue(ReferenceSheetLayout.isKnownHeader(3, "Mukavva (Board, Token vb.)"))
        assertTrue(ReferenceSheetLayout.isKnownHeader(6, "Ödünç Parçalar"))
    }

    @Test
    fun `a heading in the wrong column is not recognised`() {
        assertFalse(ReferenceSheetLayout.isKnownHeader(1, "Kart"))
        assertFalse(ReferenceSheetLayout.isKnownHeader(2, "3D"))
        assertFalse(ReferenceSheetLayout.isKnownHeader(4, "Eksik"))
    }

    @Test
    fun `an unknown heading is not recognised in any column`() {
        (0..6).forEach { column ->
            assertFalse(ReferenceSheetLayout.isKnownHeader(column, "Bilinmeyen Sütun"))
        }
    }
}
