package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.CellSnapshot
import dev.pnptracker.domain.spreadsheet.RichTextRunSnapshot
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportHintAnalyzerTest {
    private val vocabulary =
        ColorVocabulary.of(
            listOf(
                ColorVocabularyEntry("Mavi"),
                ColorVocabularyEntry("Açık Mavi"),
                ColorVocabularyEntry("Kırmızı"),
                ColorVocabularyEntry("Yeşil"),
            ),
        )
    private val analyzer = ImportHintAnalyzer(vocabulary)

    private fun cell(
        text: String,
        columnIndex: Int = 1,
        fill: String? = null,
        runs: List<RichTextRunSnapshot> = emptyList(),
    ) = CellSnapshot(
        rowIndex = 1,
        columnIndex = columnIndex,
        rawText = text,
        kind = SpreadsheetCellKind.TEXT,
        fillColorArgb = fill,
        richTextRuns = runs,
    )

    @Test
    fun `analysing the same cell twice gives an equal answer`() {
        val subject = cell("5 MAVİ/AÇIK MAVİ** WHALE", columnIndex = 1)

        val first = analyzer.analyze(subject, SourceColumnType.THREE_D)
        val second = analyzer.analyze(subject, SourceColumnType.THREE_D)

        assertEquals(first, second)
        assertEquals(first.hints, second.hints)
    }

    @Test
    fun `analysing a cell does not change it`() {
        val subject = cell("12 KIRMIZI** 8 MAVİ/AÇIK MAVİ")
        val before = subject.copy()

        analyzer.analyze(subject, SourceColumnType.THREE_D)

        assertEquals(before, subject)
        assertEquals("12 KIRMIZI** 8 MAVİ/AÇIK MAVİ", subject.rawText)
    }

    @Test
    fun `the raw text is kept beside the tidied display text`() {
        val analysis = analyzer.analyze(cell("  12 KIRMIZI**\n8 MAVİ**  "), SourceColumnType.THREE_D)

        assertEquals("  12 KIRMIZI**\n8 MAVİ**  ", analysis.rawText)
        assertEquals("  12 KIRMIZI\n8 MAVİ  ", analysis.displayText)
    }

    @Test
    fun `hints arrive in the documented order`() {
        val analysis =
            analyzer.analyze(cell("MAVİ/AÇIK MAVİ** kule", columnIndex = 1), SourceColumnType.THREE_D)

        val kinds = analysis.hints.map { it::class.simpleName }
        assertEquals(listOf("ColumnSuggestion", "CompletionMarker", "AlternativeColors"), kinds)
    }

    @Test
    fun `a green game cell and its column suggestion are both reported`() {
        val analysis =
            analyzer.analyze(
                cell("Örnek Oyun A", columnIndex = 0, fill = "FF4EA72E"),
                SourceColumnType.GAME,
            )

        val completion = assertNotNull(analysis.gameCompletion)
        assertEquals(HintConfidence.HIGH, completion.confidence)
        val column = assertNotNull(analysis.columnSuggestion)
        assertNull(column.suggestedPoolType)
        assertEquals(SourceColumnType.GAME, column.sourceColumnType)
    }

    @Test
    fun `a task column carries its pool suggestion through`() {
        val analysis = analyzer.analyze(cell("15 KIRMIZI", columnIndex = 2), SourceColumnType.CARD)

        assertEquals(PoolType.CARD, assertNotNull(analysis.columnSuggestion).suggestedPoolType)
    }

    @Test
    fun `a cell outside the reference layout gets no column suggestion`() {
        val analysis = analyzer.analyze(cell("bir not", columnIndex = 9), SourceColumnType.SPECIAL)

        assertNull(analysis.columnSuggestion)
        assertTrue(analysis.hints.none { it is ImportHint.ColumnSuggestion })
    }

    @Test
    fun `a cell with nothing to ask about produces no hints at all`() {
        val analysis = analyzer.analyze(cell("RESEARCH STATION", columnIndex = 9), SourceColumnType.THREE_D)

        assertEquals(emptyList(), analysis.hints, "there is no stand-in hint for having found nothing")
        assertEquals("RESEARCH STATION", analysis.displayText)
    }

    @Test
    fun `coloured lettering never becomes a colour choice`() {
        val text = "Kırmızı kalın ve mavi normal"
        val analysis =
            analyzer.analyze(
                cell(
                    text,
                    columnIndex = 4,
                    runs =
                        listOf(
                            RichTextRunSnapshot(0, 13, text.substring(0, 13), "FFFF0000", isBold = true),
                            RichTextRunSnapshot(13, 28, text.substring(13, 28), "FF0000FF", isBold = false),
                        ),
                ),
                SourceColumnType.SPECIAL,
            )

        assertEquals(emptyList(), analysis.alternativeColors, "'ve' is a list and font colour is not evidence")
    }

    @Test
    fun `every hint the analyzer produces is waiting for the user`() {
        val analysis =
            analyzer.analyze(
                cell("MAVİ/AÇIK MAVİ** kule", columnIndex = 0, fill = "FF4EA72E"),
                SourceColumnType.GAME,
            )

        assertTrue(analysis.hints.isNotEmpty())
        analysis.hints.forEach { hint ->
            assertEquals(HintDecision.PENDING, hint.decision, "$hint must not arrive already agreed to")
        }
    }
}
