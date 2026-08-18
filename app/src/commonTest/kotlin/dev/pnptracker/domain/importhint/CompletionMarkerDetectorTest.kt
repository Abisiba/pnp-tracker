package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.model.HintDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionMarkerDetectorTest {
    private fun rangesOf(text: String) = detectCompletionMarkers(text).markers.map { it.evidence }

    @Test
    fun `a single marker is found and cut from the display text`() {
        val scan = detectCompletionMarkers("15 KIRMIZI**")

        assertEquals(listOf(TextRange(10, 12)), scan.markers.map { it.evidence })
        assertEquals("15 KIRMIZI**", scan.rawText)
        assertEquals("15 KIRMIZI", scan.displayText)
    }

    @Test
    fun `several markers come back left to right`() {
        val scan = detectCompletionMarkers("15 KIRMIZI** 19 YEŞİL**")

        assertEquals(listOf(TextRange(10, 12), TextRange(21, 23)), scan.markers.map { it.evidence })
        assertEquals("15 KIRMIZI 19 YEŞİL", scan.displayText)
    }

    @Test
    fun `a marker can sit at the start, in the middle or at the end`() {
        assertEquals(listOf(TextRange(0, 2)), rangesOf("**MAVİ"))
        assertEquals(listOf(TextRange(5, 7)), rangesOf("MAVİ ** KIRMIZI"))
        // "MAVİ" is four UTF-16 characters: the dotted capital I is one of them.
        assertEquals(listOf(TextRange(4, 6)), rangesOf("MAVİ**"))
    }

    @Test
    fun `a lone star is not a marker and stays in the text`() {
        val scan = detectCompletionMarkers("3 YILDIZ* NOT")

        assertEquals(emptyList(), scan.markers)
        assertEquals("3 YILDIZ* NOT", scan.displayText)
    }

    @Test
    fun `four stars are two markers because a match consumes both characters`() {
        val scan = detectCompletionMarkers("MAVİ****")

        assertEquals(listOf(TextRange(4, 6), TextRange(6, 8)), scan.markers.map { it.evidence })
        assertEquals("MAVİ", scan.displayText)
    }

    @Test
    fun `three stars are one marker followed by ordinary text`() {
        val scan = detectCompletionMarkers("MAVİ***")

        assertEquals(listOf(TextRange(4, 6)), scan.markers.map { it.evidence })
        assertEquals("MAVİ*", scan.displayText)
    }

    @Test
    fun `line breaks survive on both sides of a marker`() {
        val scan = detectCompletionMarkers("12 KIRMIZI**\n8 MAVİ**\n")

        assertEquals(2, scan.markers.size)
        assertEquals("12 KIRMIZI\n8 MAVİ\n", scan.displayText)
    }

    @Test
    fun `tabs and runs of spaces are left exactly as they were`() {
        val scan = detectCompletionMarkers("  15\tKIRMIZI**   19 YEŞİL  ")

        assertEquals("  15\tKIRMIZI   19 YEŞİL  ", scan.displayText)
        assertTrue(scan.displayText.startsWith("  "), "leading spaces must not be trimmed")
        assertTrue(scan.displayText.endsWith("  "), "trailing spaces must not be trimmed")
    }

    @Test
    fun `text without a marker comes back untouched`() {
        val text = "  RESEARCH STATION BEYAZ KAHVERENGİ \n"
        val scan = detectCompletionMarkers(text)

        assertEquals(emptyList(), scan.markers)
        assertEquals(text, scan.displayText)
        assertEquals(text, scan.rawText)
    }

    @Test
    fun `every range points at the two stars in the original text`() {
        val text = "15 KIRMIZI** 19 YEŞİL** 21 SARI**"
        val scan = detectCompletionMarkers(text)

        assertEquals(3, scan.markers.size)
        scan.markers.forEach { marker ->
            assertEquals("**", marker.evidence.textIn(text))
        }
    }

    @Test
    fun `every marker is waiting for the user`() {
        val scan = detectCompletionMarkers("A** B** C**")

        assertTrue(scan.hasMarkers)
        scan.markers.forEach { marker ->
            assertEquals(HintDecision.PENDING, marker.decision)
            assertEquals(HintConfidence.HIGH, marker.confidence)
        }
    }
}
