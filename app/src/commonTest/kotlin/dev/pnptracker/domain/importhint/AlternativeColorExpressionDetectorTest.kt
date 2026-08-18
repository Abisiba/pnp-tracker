package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.model.HintDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlternativeColorExpressionDetectorTest {
    /** A small synthetic catalogue; the real one comes from the database later. */
    private val vocabulary =
        ColorVocabulary.of(
            listOf(
                ColorVocabularyEntry("Beyaz"),
                ColorVocabularyEntry("Siyah"),
                ColorVocabularyEntry("Gri", aliases = listOf("Gri Ton")),
                ColorVocabularyEntry("Kahverengi"),
                ColorVocabularyEntry("Kırmızı"),
                ColorVocabularyEntry("Sarı"),
                ColorVocabularyEntry("Yeşil"),
                ColorVocabularyEntry("Mavi"),
                ColorVocabularyEntry("Açık Mavi"),
            ),
        )

    private fun detect(text: String) = detectAlternativeColorExpressions(text, vocabulary)

    private fun colorsOf(text: String) = detect(text).map { hint -> hint.options.map { it.canonicalName } }

    @Test
    fun `a slash between two colours offers a choice`() {
        assertEquals(listOf(listOf("Mavi", "Açık Mavi")), colorsOf("MAVİ/AÇIK MAVİ"))
    }

    @Test
    fun `spaces around the slash make no difference`() {
        assertEquals(listOf(listOf("Mavi", "Açık Mavi")), colorsOf("Mavi / Açık Mavi"))
        assertEquals(listOf(listOf("Mavi", "Açık Mavi")), colorsOf("Mavi/ Açık Mavi"))
        assertEquals(listOf(listOf("Mavi", "Açık Mavi")), colorsOf("Mavi /Açık Mavi"))
    }

    @Test
    fun `two single word colours are a choice too`() {
        assertEquals(listOf(listOf("Beyaz", "Gri")), colorsOf("BEYAZ/GRİ"))
    }

    @Test
    fun `the word veya offers a choice`() {
        assertEquals(listOf(listOf("Açık Mavi", "Mavi")), colorsOf("Açık Mavi veya Mavi"))
    }

    @Test
    fun `the words ya da offer a choice`() {
        assertEquals(listOf(listOf("Beyaz", "Siyah")), colorsOf("Beyaz ya da Siyah"))
    }

    @Test
    fun `turkish capitals and lower case reach the same colours`() {
        val expected = listOf(listOf("Mavi", "Açık Mavi"))

        assertEquals(expected, colorsOf("MAVİ/AÇIK MAVİ"))
        assertEquals(expected, colorsOf("mavi/açık mavi"))
        assertEquals(expected, colorsOf("MAVI/ACIK MAVI".replace("ACIK", "AÇIK")))
        assertEquals(listOf(listOf("Beyaz", "Siyah")), colorsOf("BEYAZ VEYA SİYAH"))
        assertEquals(listOf(listOf("Beyaz", "Siyah")), colorsOf("Beyaz Veya Siyah"))
    }

    @Test
    fun `a two word colour wins over the single word inside it`() {
        val options = detect("Mavi/Açık Mavi").single().options

        assertEquals(listOf("Mavi", "Açık Mavi"), options.map { it.canonicalName })
        assertEquals("Açık Mavi", options[1].sourceText, "the longer name must not be split into just Mavi")
    }

    @Test
    fun `three colours joined by slashes are one choice of three`() {
        assertEquals(listOf(listOf("Mavi", "Sarı", "Yeşil")), colorsOf("Mavi/Sarı/Yeşil"))
    }

    @Test
    fun `two colours written next to each other are one multi coloured model`() {
        assertEquals(emptyList(), detect("BEYAZ KAHVERENGİ"))
    }

    @Test
    fun `a described model that happens to name two colours is not a choice`() {
        assertEquals(emptyList(), detect("RESEARCH STATION BEYAZ KAHVERENGİ"))
    }

    @Test
    fun `a comma and the word ve make a list, not a choice`() {
        assertEquals(emptyList(), detect("MAVİ, KIRMIZI"))
        assertEquals(emptyList(), detect("KIRMIZI VE MAVİ"))
        assertEquals(emptyList(), detect("Mavi, Sarı, Yeşil"))
    }

    @Test
    fun `a slash inside a link is just a character`() {
        assertEquals(emptyList(), detect("https://example.test/board/mavi-parca"))
    }

    @Test
    fun `a slash between things that are not colours offers nothing`() {
        assertEquals(emptyList(), detect("BOARD/TOKEN"))
        assertEquals(emptyList(), detect("A4/A3 KAĞIT"))
    }

    @Test
    fun `one recognised colour on its own is not a choice`() {
        assertEquals(emptyList(), detect("MAVİ/PUNTO"))
        assertEquals(emptyList(), detect("15 KIRMIZI"))
        assertEquals(emptyList(), detect("MEŞE/CEVİZ"))
    }

    @Test
    fun `ranges point at the original text, not at a folded copy`() {
        val text = "5 MAVİ/AÇIK MAVİ WHALE"
        val hint = detect(text).single()

        assertEquals("MAVİ/AÇIK MAVİ", hint.evidence.textIn(text))
        assertEquals("MAVİ", hint.options[0].range.textIn(text))
        assertEquals("AÇIK MAVİ", hint.options[1].range.textIn(text))
        hint.options.forEach { option ->
            assertEquals(option.sourceText, option.range.textIn(text))
        }
    }

    @Test
    fun `a cell can hold more than one choice, in order and without overlapping`() {
        val text = "3 MAVİ/AÇIK MAVİ kule\n2 BEYAZ veya GRİ taban"
        val hints = detect(text)

        assertEquals(2, hints.size)
        assertEquals(listOf("Mavi", "Açık Mavi"), hints[0].options.map { it.canonicalName })
        assertEquals(listOf("Beyaz", "Gri"), hints[1].options.map { it.canonicalName })
        assertTrue(hints[0].evidence.endIndex <= hints[1].evidence.startIndex, "groups must not overlap")
        assertEquals("MAVİ/AÇIK MAVİ", hints[0].evidence.textIn(text))
        assertEquals("BEYAZ veya GRİ", hints[1].evidence.textIn(text))
    }

    @Test
    fun `a completion marker glued onto a colour does not hide it`() {
        val text = "MAVİ/AÇIK MAVİ**"
        val hint = detect(text).single()

        assertEquals(listOf("Mavi", "Açık Mavi"), hint.options.map { it.canonicalName })
        // The range stops at the colour; the marker is the other detector's business.
        assertEquals("AÇIK MAVİ", hint.options[1].range.textIn(text))
        assertEquals("MAVİ/AÇIK MAVİ", hint.evidence.textIn(text))
    }

    @Test
    fun `a marker between two colours does not make them a choice`() {
        assertEquals(emptyList(), detect("MAVİ**KIRMIZI"), "a star separates nothing")
        assertEquals(emptyList(), detect("15 KIRMIZI** 19 YEŞİL**"), "a marked list is still a list")
    }

    @Test
    fun `an alias is recognised like the name it stands for`() {
        assertEquals(listOf(listOf("Gri", "Beyaz")), colorsOf("GRİ TON/BEYAZ"))
    }

    @Test
    fun `every choice is waiting for the user`() {
        detect("MAVİ/AÇIK MAVİ veya BEYAZ").forEach { hint ->
            assertEquals(HintDecision.PENDING, hint.decision)
        }
    }

    @Test
    fun `a coloured font does not put a colour into the text`() {
        // The detector only ever sees text. Whatever colour the cell was written
        // in cannot reach it, so red lettering can never become a colour choice.
        assertEquals(emptyList(), detect("RESEARCH STATION"))
        assertEquals(emptyList(), detect("15 adet"))
    }
}
