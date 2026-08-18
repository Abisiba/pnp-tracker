package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.CellSnapshot
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GreenCellHintDetectorTest {
    private fun cell(
        fill: String? = null,
        fontColor: String? = null,
        columnIndex: Int = 0,
        text: String = "Örnek Oyun A",
    ) = CellSnapshot(
        rowIndex = 1,
        columnIndex = columnIndex,
        rawText = text,
        kind = SpreadsheetCellKind.TEXT,
        fillColorArgb = fill,
        fontColorArgb = fontColor,
    )

    @Test
    fun `the green the reference file uses is recognised with confidence`() {
        assertEquals(HintConfidence.HIGH, greenConfidenceOf("FF4EA72E"))
    }

    @Test
    fun `the other greens a spreadsheet offers are recognised too`() {
        assertEquals(HintConfidence.HIGH, greenConfidenceOf("FF00B050"), "the standard green")
        assertEquals(HintConfidence.HIGH, greenConfidenceOf("FF70AD47"), "the accent 6 green")
        assertEquals(HintConfidence.HIGH, greenConfidenceOf("FF006100"), "a dark green")
        assertNotNull(greenConfidenceOf("FF92D050"), "the standard light green")
        assertNotNull(greenConfidenceOf("FFC6EFCE"), "the pale green of the good cell style")
    }

    @Test
    fun `a pale or yellowish green is reported without false certainty`() {
        // Both are inside the band but at its edge, which is exactly where a
        // person should look before agreeing.
        assertEquals(HintConfidence.MEDIUM, greenConfidenceOf("FFC6EFCE"), "washed out")
        assertEquals(HintConfidence.MEDIUM, greenConfidenceOf("FF92D050"), "leaning towards yellow")
    }

    @Test
    fun `the edges of the hue band are where the constants say they are`() {
        // Hue 80: red and blue chosen so the hue lands exactly on the boundary.
        assertNotNull(greenConfidenceOf("FFAAFF00"), "hue 80 is inside the band")
        assertNull(greenConfidenceOf("FFBFFF00"), "hue 75 is yellow-green and outside")
        assertNotNull(greenConfidenceOf("FF00FFAA"), "hue 160 is inside the band")
        assertNull(greenConfidenceOf("FF00FFBF"), "hue 165 is turning to cyan and outside")
    }

    @Test
    fun `the edges of the saturation and value floors are where the constants say`() {
        assertNull(greenConfidenceOf("FFEFF7EF"), "barely tinted is a shade of grey, not green")
        assertNull(greenConfidenceOf("FF001A00"), "almost black has no colour to report")
    }

    @Test
    fun `white grey black yellow blue cyan and red are not green`() {
        listOf(
            "FFFFFFFF" to "white",
            "FF808080" to "grey",
            "FF000000" to "black",
            "FFFFFF00" to "yellow",
            "FFFFFF99" to "light yellow",
            "FF0000FF" to "blue",
            "FF00FFFF" to "cyan",
            "FFFF0000" to "red",
            "FFFFC000" to "amber",
        ).forEach { (argb, name) ->
            assertNull(greenConfidenceOf(argb), "$name ($argb) must not read as green")
        }
    }

    @Test
    fun `a fully transparent green is not a colour on the page`() {
        assertNull(greenConfidenceOf("004EA72E"))
    }

    @Test
    fun `a value that is not eight hex characters yields nothing`() {
        assertNull(greenConfidenceOf("4EA72E"))
        assertNull(greenConfidenceOf("ZZZZZZZZ"))
    }

    @Test
    fun `a green game cell asks the question rather than answering it`() {
        val hint = assertNotNull(detectGameCompletionHint(cell(fill = "FF4EA72E"), SourceColumnType.GAME))

        assertEquals(HintDecision.PENDING, hint.decision)
        assertEquals(HintConfidence.HIGH, hint.confidence)
        assertEquals("FF4EA72E", hint.fillColorArgb)
    }

    @Test
    fun `a green fill in a task column says nothing about the game`() {
        listOf(
            SourceColumnType.THREE_D,
            SourceColumnType.CARD,
            SourceColumnType.BOARD,
            SourceColumnType.SPECIAL,
            SourceColumnType.MISSING,
            SourceColumnType.BORROWED,
        ).forEach { column ->
            assertNull(
                detectGameCompletionHint(cell(fill = "FF4EA72E", columnIndex = 1), column),
                "$column is not the game column",
            )
        }
    }

    @Test
    fun `a green font on an unfilled cell is not a completion hint`() {
        assertNull(detectGameCompletionHint(cell(fill = null, fontColor = "FF4EA72E"), SourceColumnType.GAME))
    }

    @Test
    fun `a cell with no fill at all produces nothing`() {
        assertNull(detectGameCompletionHint(cell(fill = null), SourceColumnType.GAME))
    }

    @Test
    fun `no game is exempt from being asked`() {
        // The source file was kept by hand, so some finished games were never
        // coloured. There is no list of names that skips the question, and every
        // green cell produces the same pending hint whatever the game is called.
        listOf("Root", "Splendor", "Örnek Oyun A").forEach { name ->
            val hint =
                assertNotNull(detectGameCompletionHint(cell(fill = "FF4EA72E", text = name), SourceColumnType.GAME))
            assertEquals(HintDecision.PENDING, hint.decision, "$name must still be asked about")
        }
    }
}
