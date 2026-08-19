package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.CellSnapshot
import dev.pnptracker.domain.spreadsheet.SheetSnapshot
import dev.pnptracker.domain.spreadsheet.SheetVisibility
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FILE_NAME = "ornek.xlsx"
private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

class ImportPreparationTest {
    private fun cell(
        row: Int,
        column: Int,
        text: String,
        fill: String? = null,
    ) = CellSnapshot(
        rowIndex = row,
        columnIndex = column,
        rawText = text,
        kind = SpreadsheetCellKind.TEXT,
        fillColorArgb = fill,
    )

    private fun sheet(
        cells: List<CellSnapshot>,
        name: String = "Sayfa1",
        visibility: SheetVisibility = SheetVisibility.VISIBLE,
    ) = SheetSnapshot(
        name = name,
        visibility = visibility,
        cells = cells.sortedWith(compareBy({ it.rowIndex }, { it.columnIndex })),
    )

    private val headerRow =
        listOf(
            cell(0, 1, "3D"),
            cell(0, 2, "Kart"),
            cell(0, 3, "Mukavva"),
            cell(0, 4, "Özel"),
            cell(0, 5, "Eksik"),
            cell(0, 6, "Ödünç Parçalar"),
        )

    private fun prepare(sheet: SheetSnapshot) = prepareImportDraft(FILE_NAME, SHA, sheet)

    @Test
    fun `a sheet with the reference headings is accepted with an empty A1`() {
        val draft = prepare(sheet(headerRow + cell(1, 0, "Örnek Oyun A")))

        assertEquals("Sayfa1", draft.sheetName)
        assertEquals(1, draft.rawBlockCount)
    }

    @Test
    fun `the other spellings of the game heading are accepted`() {
        listOf("Oyun", "Oyun Adı", "  OYUN   ADI ").forEach { heading ->
            val draft = prepare(sheet(headerRow + cell(0, 0, heading) + cell(1, 0, "Örnek Oyun A")))

            assertEquals(1, draft.rawBlockCount, "'$heading' should be a recognised game heading")
        }
    }

    @Test
    fun `the longer spellings of the work headings are accepted`() {
        val longHeadings =
            listOf(
                cell(0, 1, "3D Print (Figür vb.)"),
                cell(0, 2, "Laminasyon (Kart vb.)"),
                cell(0, 3, "Mukavva (Board, Token vb.)"),
                cell(0, 4, "Özel"),
                cell(0, 5, "Eksik"),
                cell(0, 6, "Ödünç Parçalar"),
            )

        val draft = prepare(sheet(longHeadings + cell(1, 0, "Örnek Oyun A")))

        assertEquals(1, draft.rawBlockCount)
    }

    @Test
    fun `heading cells never become raw blocks`() {
        val draft = prepare(sheet(headerRow + cell(1, 0, "Örnek Oyun A") + cell(1, 1, "3 KIRMIZI")))

        assertEquals(2, draft.rawBlockCount)
        assertTrue(draft.blocks.none { it.rowIndex == 0 }, "the heading row must not be stored")
        assertEquals(1, draft.startRowIndex, "the range starts at the first row of data")
    }

    @Test
    fun `an unrecognised heading stops the import instead of guessing`() {
        val strange = listOf(cell(0, 1, "Bilinmeyen"), cell(0, 2, "Kart"))

        val rejected = assertFailsWith<ImportPreparationException> { prepare(sheet(strange + cell(1, 0, "A"))) }

        assertEquals(ImportFailure.UNSUPPORTED_SHEET_LAYOUT, rejected.failure)
    }

    @Test
    fun `reordered columns stop the import`() {
        // Kart where 3D belongs: every heading is known, but not in this position.
        val swapped = listOf(cell(0, 1, "Kart"), cell(0, 2, "3D"))

        val rejected = assertFailsWith<ImportPreparationException> { prepare(sheet(swapped + cell(1, 0, "A"))) }

        assertEquals(ImportFailure.UNSUPPORTED_SHEET_LAYOUT, rejected.failure)
    }

    @Test
    fun `content beyond the seventh column stops the import and names the column`() {
        val rejected =
            assertFailsWith<ImportPreparationException> {
                prepare(sheet(headerRow + cell(1, 0, "A") + cell(1, 7, "fazladan")))
            }

        assertEquals(ImportFailure.UNSUPPORTED_COLUMN, rejected.failure)
        assertEquals(7, rejected.columnIndex, "the user should be told which column is not read")
    }

    @Test
    fun `an empty sheet cannot be imported`() {
        val rejected = assertFailsWith<ImportPreparationException> { prepare(sheet(emptyList())) }

        assertEquals(ImportFailure.EMPTY_SHEET, rejected.failure)
    }

    @Test
    fun `a sheet holding only its headings cannot be imported`() {
        val rejected = assertFailsWith<ImportPreparationException> { prepare(sheet(headerRow)) }

        assertEquals(ImportFailure.EMPTY_SHEET, rejected.failure)
    }

    @Test
    fun `every content cell becomes exactly one block, in reading order`() {
        val data =
            listOf(
                cell(1, 0, "Örnek Oyun A"),
                cell(1, 1, "3 KIRMIZI"),
                cell(2, 0, "Örnek Oyun B"),
                cell(2, 5, "1 eksik"),
            )

        val draft = prepare(sheet(headerRow + data))

        assertEquals(4, draft.rawBlockCount)
        assertEquals(
            listOf(1 to 0, 1 to 1, 2 to 0, 2 to 5),
            draft.blocks.map { it.rowIndex to it.columnIndex },
        )
    }

    @Test
    fun `raw text is stored exactly as it was read`() {
        val awkward = "  12 KIRMIZI**\n8 MAVİ\t  "
        val draft = prepare(sheet(headerRow + cell(1, 0, "A") + cell(1, 1, awkward)))

        val block = assertNotNull(draft.blocks.firstOrNull { it.columnIndex == 1 })
        assertEquals(awkward, block.rawText)
        assertTrue(block.rawText.startsWith("  "), "leading spaces must survive")
        assertTrue(block.rawText.contains("**"), "the marker must survive")
        assertTrue(block.rawText.contains('\n'), "the line break must survive")
        assertTrue(block.rawText.endsWith("\t  "), "trailing whitespace must survive")
    }

    @Test
    fun `a whitespace only cell is content and is kept untrimmed`() {
        val draft = prepare(sheet(headerRow + cell(1, 0, "A") + cell(1, 1, "   ")))

        assertEquals("   ", assertNotNull(draft.blocks.firstOrNull { it.columnIndex == 1 }).rawText)
    }

    @Test
    fun `each column keeps the source type its position gives it`() {
        val row =
            (0..6).map { column -> cell(1, column, "hücre $column") }

        val draft = prepare(sheet(headerRow + row))

        assertEquals(
            listOf(
                SourceColumnType.GAME,
                SourceColumnType.THREE_D,
                SourceColumnType.CARD,
                SourceColumnType.BOARD,
                SourceColumnType.SPECIAL,
                SourceColumnType.MISSING,
                SourceColumnType.BORROWED,
            ),
            draft.blocks.map { it.sourceColumnType },
        )
    }

    @Test
    fun `a fill colour is carried across without loss`() {
        val draft = prepare(sheet(headerRow + cell(1, 0, "A", fill = "FF4EA72E") + cell(1, 1, "B", fill = null)))

        assertEquals("FF4EA72E", unpackArgb(assertNotNull(draft.blocks[0].fillColorArgb)))
        assertNull(draft.blocks[1].fillColorArgb)
    }

    @Test
    fun `a green game cell is stored as a question, an uncoloured one as nothing`() {
        val draft =
            prepare(
                sheet(headerRow + cell(1, 0, "Yeşil oyun", fill = "FF4EA72E") + cell(2, 0, "Renksiz oyun")),
            )

        assertEquals(HintDecision.PENDING, draft.blocks[0].gameCompletionHint)
        assertEquals(HintDecision.NONE, draft.blocks[1].gameCompletionHint)
        assertEquals(1, draft.pendingGameCompletionHintCount)
    }

    @Test
    fun `a green task cell gets no game completion hint`() {
        val draft = prepare(sheet(headerRow + cell(1, 0, "Oyun") + cell(1, 1, "3 YEŞİL", fill = "FF4EA72E")))

        val task = assertNotNull(draft.blocks.firstOrNull { it.columnIndex == 1 })
        assertEquals(HintDecision.NONE, task.gameCompletionHint)
        assertEquals("FF4EA72E", unpackArgb(task.fillColorArgb), "the colour is still recorded")
    }

    @Test
    fun `a hint is never stored as already answered`() {
        val draft = prepare(sheet(headerRow + cell(1, 0, "Oyun", fill = "FF4EA72E")))

        draft.blocks.forEach { block ->
            assertTrue(
                block.gameCompletionHint == HintDecision.NONE || block.gameCompletionHint == HintDecision.PENDING,
                "an import can only find a hint, but found ${block.gameCompletionHint}",
            )
        }
    }

    @Test
    fun `a row with only a game name still produces its block`() {
        val draft = prepare(sheet(headerRow + cell(1, 0, "Sadece oyun adı")))

        assertEquals(1, draft.rawBlockCount)
        assertEquals(SourceColumnType.GAME, draft.blocks.single().sourceColumnType)
    }

    @Test
    fun `work on a row with no game name is kept and reported`() {
        val draft = prepare(sheet(headerRow + cell(1, 0, "Örnek Oyun A") + cell(2, 1, "3 KIRMIZI")))

        assertEquals(2, draft.rawBlockCount, "the orphan cell must not be dropped")
        val warning = assertNotNull(draft.warnings.firstOrNull { it.kind == ImportWarningKind.ROW_WITHOUT_GAME_NAME })
        assertEquals(listOf(2), warning.rowIndexes)
    }

    @Test
    fun `a game name is never carried down from the row above`() {
        val draft = prepare(sheet(headerRow + cell(1, 0, "Örnek Oyun A") + cell(2, 1, "3 KIRMIZI")))

        assertEquals(
            1,
            draft.detectedGameCellCount,
            "inventing a game name for row 2 would put a guess into the record",
        )
        assertTrue(draft.blocks.none { it.rowIndex == 2 && it.sourceColumnType == SourceColumnType.GAME })
    }

    @Test
    fun `a hidden sheet is reported as such`() {
        val draft =
            prepare(sheet(headerRow + cell(1, 0, "A"), visibility = SheetVisibility.HIDDEN))

        assertTrue(draft.warnings.any { it.kind == ImportWarningKind.HIDDEN_SHEET })
        assertEquals(SheetVisibility.HIDDEN, draft.sheetVisibility)
    }

    @Test
    fun `counts describe the sheet and never claim anything was created`() {
        val draft =
            prepare(
                sheet(
                    headerRow +
                        listOf(
                            cell(1, 0, "Örnek Oyun A", fill = "FF4EA72E"),
                            cell(1, 1, "12 KIRMIZI**\n8 MAVİ"),
                            cell(2, 0, "Örnek Oyun B"),
                        ),
                ),
            )

        assertEquals(3, draft.rawBlockCount)
        assertEquals(2, draft.detectedGameCellCount)
        assertEquals(1, draft.multiLineCellCount)
        assertEquals(mapOf(SourceColumnType.GAME to 2, SourceColumnType.THREE_D to 1), draft.blockCountsByColumnType)
    }

    @Test
    fun `the range describes the cells that were taken`() {
        val draft =
            prepare(sheet(headerRow + cell(1, 0, "A") + cell(4, 6, "ödünç")))

        assertEquals(1, draft.startRowIndex)
        assertEquals(4, draft.endRowIndex)
        assertEquals(0, draft.startColumnIndex)
        assertEquals(6, draft.endColumnIndex)
    }

    @Test
    fun `preparing the same sheet twice gives an equal draft`() {
        val subject = sheet(headerRow + cell(1, 0, "Örnek Oyun A", fill = "FF4EA72E") + cell(1, 1, "3 KIRMIZI**"))

        assertEquals(prepare(subject), prepare(subject))
    }

    @Test
    fun `preparing does not change the sheet it was given`() {
        val subject = sheet(headerRow + cell(1, 0, "Örnek Oyun A") + cell(1, 1, "3 KIRMIZI**"))
        val before = subject.copy()

        prepare(subject)

        assertEquals(before, subject)
        assertEquals("3 KIRMIZI**", subject.cellAt(1, 1)?.rawText)
    }

    @Test
    fun `no path can reach the draft`() {
        val rejected =
            assertFailsWith<IllegalArgumentException> {
                prepareImportDraft("/home/birisi/kitap.xlsx", SHA, sheet(headerRow + cell(1, 0, "A")))
            }

        assertTrue(rejected.message.orEmpty().contains("never a path"))
    }
}
