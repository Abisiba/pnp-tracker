package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.SheetVisibility
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a CSV becomes on the way to the import the review screen already reads.
 *
 * A CSV is laid out as one page of the reference workbook and handed to the very
 * same preparation the spreadsheet uses. That is the whole trick, and it is what
 * keeps CSV from growing a second set of rules for raw blocks, drafts, colours
 * and confirmation.
 */
class CsvImportSourceTest {
    private fun draftOf(text: String): PreparedImportDraft {
        val reading = readCsvWorkbook("liste.csv", text)
        return prepareImportDraft(
            fileName = "liste.csv",
            sha256 = "f".repeat(64),
            sheet = reading.workbook.sheets.single(),
            sourceFormat = ImportSourceFormat.CSV,
        )
    }

    private val threeRows =
        """
        game,source_type,raw_text
        Harmonies,3d,Kırmızı ev
        Harmonies,card,Deste
        Wingspan,board,Kuş jetonu
        """.trimIndent()

    @Test
    fun `a CSV is one logical page, named after the file`() {
        val workbook = readCsvWorkbook("liste.csv", threeRows).workbook

        assertEquals("liste.csv", workbook.fileName)
        val sheet = workbook.sheets.single()
        assertEquals("liste.csv", sheet.name)
        assertEquals(SheetVisibility.VISIBLE, sheet.visibility)
    }

    @Test
    fun `the order of the file is the order of the raw blocks`() {
        val blocks = draftOf(threeRows).blocks

        assertEquals(
            listOf(
                1 to "Harmonies",
                1 to "Kırmızı ev",
                2 to "Harmonies",
                2 to "Deste",
                3 to "Wingspan",
                3 to "Kuş jetonu",
            ),
            blocks.map { it.rowIndex to it.rawText },
        )
        assertEquals(3, draftOf(threeRows).sourceRowCount)
    }

    @Test
    fun `each row is filed under the column its source type names`() {
        val blocks = draftOf(threeRows).blocks

        assertEquals(
            listOf(
                SourceColumnType.GAME,
                SourceColumnType.THREE_D,
                SourceColumnType.GAME,
                SourceColumnType.CARD,
                SourceColumnType.GAME,
                SourceColumnType.BOARD,
            ),
            blocks.map { it.sourceColumnType },
        )
    }

    @Test
    fun `the import records that it came from a CSV`() {
        assertEquals(ImportSourceFormat.CSV, draftOf(threeRows).sourceFormat)
    }

    @Test
    fun `a row about the game name itself keeps the text the file wrote`() {
        val draft = draftOf("game,source_type,raw_text\nHarmonies,game,Harmonies **")

        val block = draft.blocks.single()
        assertEquals(SourceColumnType.GAME, block.sourceColumnType)
        assertEquals("Harmonies **", block.rawText, "the game row lost the text the file actually held")
    }

    @Test
    fun `no fill colour is invented for a file that has none`() {
        assertTrue(draftOf(threeRows).blocks.all { it.fillColorArgb == null })
    }

    @Test
    fun `no green completion hint can come out of a CSV`() {
        val draft = draftOf(threeRows)

        assertEquals(0, draft.pendingGameCompletionHintCount)
        assertTrue(draft.blocks.all { it.gameCompletionHint == HintDecision.NONE })
    }

    @Test
    fun `every cell is plain text and none is a formula`() {
        val sheet = readCsvWorkbook("liste.csv", threeRows).workbook.sheets.single()

        assertTrue(sheet.cells.all { it.kind == SpreadsheetCellKind.TEXT })
        assertTrue(sheet.cells.all { it.formula == null })
        assertTrue(sheet.cells.all { it.richTextRuns.isEmpty() && !it.isBold })
    }

    @Test
    fun `text that looks like a formula is kept as the characters it is`() {
        val draft = draftOf("game,source_type,raw_text\nHesap,3d,=1+1\nHesap,card,@ad\nHesap,board,-3 adet")

        assertEquals(
            listOf("=1+1", "@ad", "-3 adet"),
            draft.blocks.filter { it.sourceColumnType != SourceColumnType.GAME }.map { it.rawText },
        )
    }

    @Test
    fun `spaces, punctuation and line breaks inside raw text are left alone`() {
        val raw = "  12 KIRMIZI**\r\n8 MAVİ  "
        val draft = draftOf("game,source_type,raw_text\nHarmonies,3d,\"$raw\"")

        assertEquals(raw, draft.blocks.first { it.sourceColumnType == SourceColumnType.THREE_D }.rawText)
        assertEquals(1, draft.multiLineCellCount)
    }

    @Test
    fun `two identical rows stay two raw blocks`() {
        val draft = draftOf("game,source_type,raw_text\nHarmonies,3d,Kırmızı ev\nHarmonies,3d,Kırmızı ev")

        val work = draft.blocks.filter { it.sourceColumnType == SourceColumnType.THREE_D }
        assertEquals(2, work.size, "a repeated row was quietly merged away")
        assertEquals(listOf(1, 2), work.map { it.rowIndex })
    }

    @Test
    fun `every row carries its own game name, so none is left orphaned`() {
        val draft = draftOf(threeRows)

        assertEquals(emptyList(), draft.warnings)
        assertEquals(3, draft.detectedGameCellCount)
    }

    @Test
    fun `a file with only a heading row imports nothing rather than an empty batch`() {
        val refused =
            kotlin.runCatching { draftOf("game,source_type,raw_text") }.exceptionOrNull() as? ImportPreparationException

        assertEquals(ImportFailure.EMPTY_SHEET, refused?.failure)
        assertNull(refused?.csvLocation)
    }

    @Test
    fun `all four production source types can live in one file`() {
        val draft =
            draftOf(
                """
                game,source_type,raw_text
                Harmonies,3d,Kırmızı ev
                Harmonies,card,Deste
                Harmonies,board,Jeton
                Harmonies,special,Kutu içi
                Harmonies,missing,Eksik zar
                Harmonies,borrowed,Ödünç kum saati
                """.trimIndent(),
            )

        assertEquals(
            mapOf(
                SourceColumnType.GAME to 6,
                SourceColumnType.THREE_D to 1,
                SourceColumnType.CARD to 1,
                SourceColumnType.BOARD to 1,
                SourceColumnType.SPECIAL to 1,
                SourceColumnType.MISSING to 1,
                SourceColumnType.BORROWED to 1,
            ),
            draft.blockCountsByColumnType,
        )
    }
}
