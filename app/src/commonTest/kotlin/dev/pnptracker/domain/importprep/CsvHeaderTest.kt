package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.csv.CsvDelimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * How the heading row is read, and which separator that settles on.
 *
 * The user is never asked. Counting commas and semicolons would be the obvious
 * way to guess and the wrong one: a file whose raw text is full of prose would
 * out-vote its own heading row. So each candidate separator is given the heading
 * record to parse by the rules of RFC 4180, and the one that produces the three
 * columns PLAN 11.8 names is the one the file used.
 */
class CsvHeaderTest {
    private fun readingOf(text: String) = readCsvWorkbook("liste.csv", text)

    private fun failureOf(text: String): ImportPreparationException = assertFailsWith { readingOf(text) }

    private val oneRow = "\nHarmonies,3d,Kırmızı ev"
    private val oneRowSemicolon = "\nHarmonies;3d;Kırmızı ev"

    // ------------------------------------------------------------- what is chosen

    @Test
    fun `a comma file is read with commas`() {
        assertEquals(CsvDelimiter.COMMA, readingOf("game,source_type,raw_text$oneRow").delimiter)
    }

    @Test
    fun `a semicolon file is read with semicolons`() {
        assertEquals(
            CsvDelimiter.SEMICOLON,
            readingOf("game;source_type;raw_text$oneRowSemicolon").delimiter,
        )
    }

    @Test
    fun `a quoted comma in the heading row does not make the file a comma file`() {
        val reading = readingOf("game;\"source_type, gerçekten\";source_type;raw_text\nHarmonies;serbest;3d;Kırmızı ev")

        assertEquals(CsvDelimiter.SEMICOLON, reading.delimiter)
    }

    @Test
    fun `a quoted semicolon in the heading row does not make the file a semicolon file`() {
        val reading = readingOf("game,\"raw_text; belki\",source_type,raw_text\nHarmonies,serbest,3d,Kırmızı ev")

        assertEquals(CsvDelimiter.COMMA, reading.delimiter)
    }

    // ------------------------------------------------------------ the column set

    @Test
    fun `the columns may come in any order`() {
        val reading = readingOf("raw_text,game,source_type\nKırmızı ev,Harmonies,3d")
        val sheet = reading.workbook.sheets.single()

        assertEquals("Harmonies", sheet.cellAt(1, 0)?.rawText)
        assertEquals("Kırmızı ev", sheet.cellAt(1, 1)?.rawText)
    }

    @Test
    fun `extra columns are carried past without complaint and without being read`() {
        val reading = readingOf("id,game,not,source_type,raw_text\n7,Harmonies,serbest not,3d,Kırmızı ev")
        val sheet = reading.workbook.sheets.single()

        assertEquals(2, sheet.cells.count { it.rowIndex == 1 })
        assertEquals("Harmonies", sheet.cellAt(1, 0)?.rawText)
        assertEquals("Kırmızı ev", sheet.cellAt(1, 1)?.rawText)
    }

    @Test
    fun `a byte order mark does not hide the first heading`() {
        assertEquals(CsvDelimiter.COMMA, readingOf("﻿game,source_type,raw_text$oneRow").delimiter)
    }

    @Test
    fun `headings are matched with spaces trimmed and case ignored`() {
        assertEquals(CsvDelimiter.COMMA, readingOf("  GAME , Source_Type ,raw_text$oneRow").delimiter)
    }

    // ------------------------------------------------------------- what is refused

    @Test
    fun `a heading row missing one of the three columns is refused`() {
        val refused = failureOf("game,source_type\nHarmonies,3d")

        assertEquals(ImportFailure.CSV_MISSING_HEADER_COLUMN, refused.failure)
        assertEquals(1, refused.csvLocation?.lineNumber)
    }

    @Test
    fun `a heading row naming a required column twice is refused`() {
        val refused = failureOf("game,source_type,raw_text,game\nHarmonies,3d,Kırmızı ev,Harmonies")

        assertEquals(ImportFailure.CSV_DUPLICATE_HEADER_COLUMN, refused.failure)
    }

    @Test
    fun `a file with neither separator is refused as one whose separator is unknown`() {
        val refused = failureOf("game|source_type|raw_text\nHarmonies|3d|Kırmızı ev")

        assertEquals(ImportFailure.CSV_UNDETECTABLE_DELIMITER, refused.failure)
    }

    @Test
    fun `an empty file is refused rather than read as an import of nothing`() {
        assertEquals(ImportFailure.CSV_UNDETECTABLE_DELIMITER, failureOf("").failure)
    }

    @Test
    fun `a heading row that is not CSV at all is refused with the line it broke on`() {
        val refused = failureOf("\"game,source_type,raw_text\nHarmonies,3d,Kırmızı ev")

        assertEquals(ImportFailure.CSV_UNCLOSED_QUOTE, refused.failure)
        assertEquals(1, refused.csvLocation?.lineNumber)
    }

    @Test
    fun `a blank required value names the line and the column`() {
        val refused = failureOf("game,source_type,raw_text\nHarmonies,3d,Kırmızı ev\nWingspan,3d,   ")

        assertEquals(ImportFailure.CSV_BLANK_REQUIRED_VALUE, refused.failure)
        assertEquals(3, refused.csvLocation?.lineNumber)
        assertEquals("raw_text", refused.csvLocation?.columnName)
    }

    @Test
    fun `a row with the wrong number of fields is refused rather than shifted along`() {
        val refused = failureOf("game,source_type,raw_text\nHarmonies,3d,Kırmızı ev\nWingspan,3d")

        assertEquals(ImportFailure.CSV_RAGGED_ROW, refused.failure)
        assertEquals(3, refused.csvLocation?.lineNumber)
    }

    @Test
    fun `a row with one field too many is refused too`() {
        val refused = failureOf("game,source_type,raw_text\nHarmonies,3d,Kırmızı ev,fazladan")

        assertEquals(ImportFailure.CSV_RAGGED_ROW, refused.failure)
        assertEquals(2, refused.csvLocation?.lineNumber)
    }

    @Test
    fun `a blank line is not a row and is passed over`() {
        val reading = readingOf("game,source_type,raw_text\nHarmonies,3d,Kırmızı ev\n\nWingspan,3d,Mavi çatı\n")
        val sheet = reading.workbook.sheets.single()

        assertEquals(listOf("Harmonies", "Wingspan"), listOf(1, 2).map { sheet.cellAt(it, 0)?.rawText })
    }

    @Test
    fun `a heading row both separators could read is refused rather than guessed at`() {
        val bothWork =
            mapOf(
                CsvDelimiter.COMMA to HeaderCandidate.Read(listOf("game", "source_type", "raw_text")),
                CsvDelimiter.SEMICOLON to HeaderCandidate.Read(listOf("game", "source_type", "raw_text")),
            )

        val refused = assertFailsWith<ImportPreparationException> { chooseHeader(bothWork) }

        assertEquals(ImportFailure.CSV_AMBIGUOUS_DELIMITER, refused.failure)
    }

    @Test
    fun `one workable separator is chosen even though the other read something`() {
        val onlyComma =
            mapOf(
                CsvDelimiter.COMMA to HeaderCandidate.Read(listOf("game", "source_type", "raw_text")),
                CsvDelimiter.SEMICOLON to HeaderCandidate.Read(listOf("game,source_type,raw_text")),
            )

        assertEquals(CsvDelimiter.COMMA, chooseHeader(onlyComma).delimiter)
    }

    // ------------------------------------------------- the heading spellings

    @Test
    fun `no two reference columns accept the same heading`() {
        // The order the spellings are looked at in must not be able to change the
        // answer, which is only true while the seven sets stay disjoint.
        val spellings =
            listOf(
                "oyun" to 0,
                "oyun adı" to 0,
                "3d" to 1,
                "3d print (figür vb.)" to 1,
                "kart" to 2,
                "laminasyon" to 2,
                "mukavva" to 3,
                "özel" to 4,
                "eksik" to 5,
                "ödünç parçalar" to 6,
            )
        spellings.forEach { (spelling, columnIndex) ->
            assertEquals(columnIndex, ReferenceSheetLayout.columnIndexOfHeader(spelling), spelling)
        }
    }

    @Test
    fun `the game column's missing heading names no column`() {
        assertNull(ReferenceSheetLayout.columnIndexOfHeader(""))
        assertNull(ReferenceSheetLayout.columnIndexOfHeader("   "))
    }
}
