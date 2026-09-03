package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.model.SourceColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Which words a row may use to say which column it came from.
 *
 * Two vocabularies, and deliberately no third. A file written for this
 * application can name the source column itself; a file exported from the
 * reference workbook can keep the Turkish heading it already had. Inventing a
 * looser list of aliases would mean the day somebody writes `kartlar` the row
 * lands in a pool nobody chose, and PLAN 10 is clear that an unclassifiable
 * cell is never swept into one quietly.
 */
class CsvSourceTypeTest {
    @Test
    fun `every source column can be named by its own name`() {
        SourceColumnType.entries.forEach { columnType ->
            assertEquals(columnType, sourceColumnTypeOf(columnType.name), columnType.name)
        }
    }

    @Test
    fun `every Turkish heading the spreadsheet import knows names the same column`() {
        val headings =
            mapOf(
                "oyun" to SourceColumnType.GAME,
                "oyun adı" to SourceColumnType.GAME,
                "oyun adi" to SourceColumnType.GAME,
                "3d" to SourceColumnType.THREE_D,
                "3d print" to SourceColumnType.THREE_D,
                "3d print (figür vb.)" to SourceColumnType.THREE_D,
                "kart" to SourceColumnType.CARD,
                "laminasyon" to SourceColumnType.CARD,
                "laminasyon (kart vb.)" to SourceColumnType.CARD,
                "mukavva" to SourceColumnType.BOARD,
                "mukavva (board, token vb.)" to SourceColumnType.BOARD,
                "özel" to SourceColumnType.SPECIAL,
                "eksik" to SourceColumnType.MISSING,
                "ödünç parçalar" to SourceColumnType.BORROWED,
            )
        headings.forEach { (heading, expected) ->
            assertEquals(expected, sourceColumnTypeOf(heading), heading)
        }
    }

    @Test
    fun `capitals make no difference, in Turkish either`() {
        assertEquals(SourceColumnType.THREE_D, sourceColumnTypeOf("THREE_D"))
        assertEquals(SourceColumnType.CARD, sourceColumnTypeOf("KaRt"))
        assertEquals(SourceColumnType.MISSING, sourceColumnTypeOf("EKSİK"))
        assertEquals(SourceColumnType.SPECIAL, sourceColumnTypeOf("ÖZEL"))
    }

    @Test
    fun `space at either end makes no difference`() {
        assertEquals(SourceColumnType.BOARD, sourceColumnTypeOf("  mukavva  "))
        assertEquals(SourceColumnType.BORROWED, sourceColumnTypeOf("\tödünç   parçalar "))
    }

    @Test
    fun `a word nobody has agreed on names nothing`() {
        listOf("kartlar", "figür", "board", "?", "3", "").forEach { unknown ->
            if (unknown != "board") assertNull(sourceColumnTypeOf(unknown), unknown)
        }
        // `board` is a real source column name and does answer; the point is that
        // it answers as itself and not as the Turkish `mukavva` by coincidence.
        assertEquals(SourceColumnType.BOARD, sourceColumnTypeOf("board"))
    }

    @Test
    fun `an unknown source type stops the file instead of choosing a pool`() {
        val refused =
            assertFailsWith<ImportPreparationException> {
                readCsvWorkbook("liste.csv", "game,source_type,raw_text\nHarmonies,kartlar,Kırmızı ev")
            }

        assertEquals(ImportFailure.CSV_UNKNOWN_SOURCE_TYPE, refused.failure)
        assertEquals(2, refused.csvLocation?.lineNumber)
        assertEquals("source_type", refused.csvLocation?.columnName)
    }

    @Test
    fun `an unknown source type on the last row still leaves nothing behind`() {
        val refused =
            assertFailsWith<ImportPreparationException> {
                readCsvWorkbook(
                    "liste.csv",
                    "game,source_type,raw_text\nHarmonies,3d,Kırmızı ev\nWingspan,belirsiz,Mavi çatı",
                )
            }

        // The whole file is read before anything at all is made of it, so the
        // rows above the bad one produced no workbook and can produce no import.
        assertEquals(ImportFailure.CSV_UNKNOWN_SOURCE_TYPE, refused.failure)
        assertEquals(3, refused.csvLocation?.lineNumber)
    }
}
