package dev.pnptracker.domain.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ColorNormalizationTest {
    @Test
    fun `the four ways of typing grey agree`() {
        val forms = listOf("gri", "Gri", "GRİ", "GRI", " gri ")

        assertEquals(setOf("gri"), forms.map(::normalizeColorTerm).toSet())
    }

    @Test
    fun `the dotted and dotless i in red agree`() {
        val forms = listOf("Kırmızı", "KIRMIZI", "KİRMİZİ", "kirmizi", "kırmızı")

        assertEquals(setOf("kirmizi"), forms.map(::normalizeColorTerm).toSet())
    }

    @Test
    fun `runs of whitespace collapse in a two word name`() {
        val forms = listOf("Açık Mavi", "  AÇIK   MAVİ", "açik mavi", "açık\tmavi")

        assertEquals(setOf("açik mavi"), forms.map(::normalizeColorTerm).toSet())
    }

    @Test
    fun `a combining dot above is dropped`() {
        // "C" followed by U+0307, the mark that lower casing a dotted capital leaves behind.
        val withCombiningDot = "AC\u0307IK"

        assertEquals(normalizeColorTerm("acik"), normalizeColorTerm(withCombiningDot))
    }

    @Test
    fun `the other turkish letters are kept`() {
        assertEquals("çşğöü", normalizeColorTerm("ÇŞĞÖÜ"))
        assertEquals("yeşil", normalizeColorTerm("YEŞİL"))
        assertEquals("kahverengi", normalizeColorTerm("Kahverengi"))
    }

    @Test
    fun `a blank term is rejected`() {
        assertFailsWith<IllegalArgumentException> { normalizeColorTerm("") }
        assertFailsWith<IllegalArgumentException> { normalizeColorTerm("   ") }
        assertFailsWith<IllegalArgumentException> { normalizeColorTerm("\t\n") }
    }

    @Test
    fun `a term made only of combining marks is rejected`() {
        assertFailsWith<IllegalArgumentException> { normalizeColorTerm("\u0307") }
    }

    @Test
    fun `normalising is idempotent`() {
        listOf("GRİ", "  AÇIK   MAVİ", "Kırmızı").forEach { term ->
            val once = normalizeColorTerm(term)

            assertEquals(once, normalizeColorTerm(once))
        }
    }

    @Test
    fun `colors that are genuinely different stay different`() {
        val distinct = listOf("Mavi", "Açık Mavi", "Mor", "Pembe", "Sarı", "Siyah", "Beyaz").map(::normalizeColorTerm)

        assertEquals(distinct.size, distinct.toSet().size)
    }

    @Test
    fun `a precomposed dotted capital and its decomposed form agree`() {
        // U+0130 as a single character, versus "I" followed by U+0307.
        val precomposed = normalizeColorTerm("GR\u0130")
        val decomposed = normalizeColorTerm("GRI\u0307")

        assertEquals("gri", precomposed)
        assertEquals(precomposed, decomposed)
    }
}
