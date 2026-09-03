package dev.pnptracker.domain.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the CSV reader makes of a file, character by character.
 *
 * Everything here is a string in and a list out: no file, no database, no
 * screen. That is the point of writing the parser by hand — the rules of RFC
 * 4180 are small enough to state, and stating them here is what stops the two
 * separators, the quoting and the Turkish text from being argued about later in
 * a place where they cannot be seen.
 */
class CsvParserTest {
    private fun fieldsOf(
        text: String,
        delimiter: CsvDelimiter = CsvDelimiter.COMMA,
    ) = parseCsvRecords(text, delimiter).map { it.fields }

    // ------------------------------------------------------- the two separators

    @Test
    fun `a comma separated file is read field by field`() {
        assertEquals(
            listOf(listOf("game", "source_type", "raw_text"), listOf("Harmonies", "3d", "Kırmızı ev")),
            fieldsOf("game,source_type,raw_text\nHarmonies,3d,Kırmızı ev"),
        )
    }

    @Test
    fun `a semicolon separated file is read the same way`() {
        assertEquals(
            listOf(listOf("game", "source_type", "raw_text"), listOf("Harmonies", "3d", "Kırmızı ev")),
            fieldsOf("game;source_type;raw_text\nHarmonies;3d;Kırmızı ev", CsvDelimiter.SEMICOLON),
        )
    }

    @Test
    fun `the other separator is ordinary text inside a field`() {
        assertEquals(listOf(listOf("a;b", "c")), fieldsOf("a;b,c"))
        assertEquals(listOf(listOf("a,b", "c")), fieldsOf("a,b;c", CsvDelimiter.SEMICOLON))
    }

    // ------------------------------------------------------------ line endings

    @Test
    fun `records are separated by CRLF`() {
        assertEquals(listOf(listOf("a"), listOf("b")), fieldsOf("a\r\nb"))
    }

    @Test
    fun `records are separated by LF`() {
        assertEquals(listOf(listOf("a"), listOf("b")), fieldsOf("a\nb"))
    }

    @Test
    fun `the last record needs no line ending`() {
        assertEquals(listOf(listOf("a", "b")), fieldsOf("a,b"))
    }

    @Test
    fun `a line ending after the last record does not invent an extra one`() {
        assertEquals(listOf(listOf("a")), fieldsOf("a\n"))
        assertEquals(listOf(listOf("a")), fieldsOf("a\r\n"))
    }

    // ---------------------------------------------------------------- quoting

    @Test
    fun `a quoted field loses its quotes and keeps its text`() {
        assertEquals(listOf(listOf("Kırmızı ev", "b")), fieldsOf("\"Kırmızı ev\",b"))
    }

    @Test
    fun `a quoted field may hold the separator`() {
        assertEquals(listOf(listOf("bir, iki", "üç")), fieldsOf("\"bir, iki\",üç"))
        assertEquals(listOf(listOf("bir; iki", "üç")), fieldsOf("\"bir; iki\";üç", CsvDelimiter.SEMICOLON))
    }

    @Test
    fun `a quoted field may hold a CRLF, and keeps it exactly`() {
        assertEquals(listOf(listOf("üst\r\nalt", "b")), fieldsOf("\"üst\r\nalt\",b"))
    }

    @Test
    fun `a quoted field may hold an LF, and keeps it exactly`() {
        assertEquals(listOf(listOf("üst\nalt", "b")), fieldsOf("\"üst\nalt\",b"))
    }

    @Test
    fun `two quotes inside a quoted field stand for one`() {
        assertEquals(listOf(listOf("12 \"büyük\" ev")), fieldsOf("\"12 \"\"büyük\"\" ev\""))
    }

    @Test
    fun `a quoted field can be empty`() {
        assertEquals(listOf(listOf("", "b")), fieldsOf("\"\",b"))
    }

    // ------------------------------------------------------------ empty fields

    @Test
    fun `an empty field between two separators stays a field`() {
        assertEquals(listOf(listOf("a", "", "c")), fieldsOf("a,,c"))
    }

    @Test
    fun `a record may end with an empty field`() {
        assertEquals(listOf(listOf("a", "b", "")), fieldsOf("a,b,"))
    }

    // --------------------------------------------------------------- unicode

    @Test
    fun `a byte order mark is dropped once and only at the very front`() {
        assertEquals(listOf(listOf("game", "source_type")), fieldsOf("\uFEFFgame,source_type"))
        // One inside a value is a character somebody typed, not a mark.
        assertEquals(listOf(listOf("a\uFEFFb")), fieldsOf("a\uFEFFb"))
    }

    @Test
    fun `Turkish letters survive unchanged`() {
        val turkish = "IŞIKLI DİREK ığüşöçİĞÜŞÖÇ"
        assertEquals(listOf(listOf(turkish)), fieldsOf(turkish))
    }

    @Test
    fun `a combining mark stays beside the letter it belongs to`() {
        // "İsim" written as I + combining dot above, which is a different string
        // from the composed one and must not be folded into it here.
        val decomposed = "İsim"
        assertEquals(listOf(listOf(decomposed, "x")), fieldsOf("$decomposed,x"))
        assertEquals(decomposed, fieldsOf("$decomposed,x").single().first())
    }

    @Test
    fun `a surrogate pair emoji is not cut in half`() {
        assertEquals(listOf(listOf("😀 gülen", "b")), fieldsOf("😀 gülen,b"))
    }

    @Test
    fun `a skin tone emoji keeps its modifier`() {
        assertEquals(listOf(listOf("👍🏽")), fieldsOf("👍🏽"))
    }

    @Test
    fun `a zero width joiner family stays one sequence`() {
        val family = "👨‍👩‍👧‍👦"
        assertEquals(listOf(listOf(family, "aile")), fieldsOf("$family,aile"))
    }

    @Test
    fun `a flag keeps both of its regional indicators`() {
        assertEquals(listOf(listOf("🇹🇷")), fieldsOf("🇹🇷"))
    }

    // ----------------------------------------------------------- what is refused

    @Test
    fun `a quoted field the file never closes is refused, at the line it opened on`() {
        val broken = assertFailsWith<CsvParseException> { fieldsOf("a,b\nc,\"hiç kapanmadı") }

        assertEquals(CsvParseFailure.UNCLOSED_QUOTED_FIELD, broken.failure)
        assertEquals(2, broken.lineNumber)
    }

    @Test
    fun `text after a closing quote is refused`() {
        val broken = assertFailsWith<CsvParseException> { fieldsOf("\"a\"b,c") }

        assertEquals(CsvParseFailure.TEXT_AFTER_QUOTED_FIELD, broken.failure)
        assertEquals(1, broken.lineNumber)
    }

    @Test
    fun `a quote inside a field that never opened with one is refused`() {
        val broken = assertFailsWith<CsvParseException> { fieldsOf("a,b\nc,d\"e") }

        assertEquals(CsvParseFailure.QUOTE_IN_PLAIN_FIELD, broken.failure)
        assertEquals(2, broken.lineNumber)
    }

    // --------------------------------------------------------------- the file

    @Test
    fun `ten thousand records keep their order and cost time in proportion`() {
        val rows = 10_000
        val text =
            buildString {
                append("game,source_type,raw_text\n")
                repeat(rows) { index -> append("Oyun $index,3d,Parça $index\n") }
            }

        val records = parseCsvRecords(text, CsvDelimiter.COMMA)

        assertEquals(rows + 1, records.size)
        assertEquals(listOf("Oyun 0", "3d", "Parça 0"), records[1].fields)
        assertEquals(listOf("Oyun ${rows - 1}", "3d", "Parça ${rows - 1}"), records.last().fields)
        // Line numbers keep step with the file, all the way down.
        assertEquals(rows + 1, records.last().lineNumber)
        assertTrue(records.drop(1).map { it.fields[0] } == (0 until rows).map { "Oyun $it" })
    }

    @Test
    fun `two identical records stay two records`() {
        val text = "Harmonies,3d,Kırmızı ev\nHarmonies,3d,Kırmızı ev"

        val records = parseCsvRecords(text, CsvDelimiter.COMMA)

        assertEquals(2, records.size, "a repeated row was quietly merged away")
        assertEquals(records[0].fields, records[1].fields)
        assertEquals(listOf(1, 2), records.map { it.lineNumber })
    }

    @Test
    fun `a value that looks like a formula is four characters and nothing more`() {
        val text = "=1+1,+2,-3,@ad"

        assertEquals(listOf(listOf("=1+1", "+2", "-3", "@ad")), fieldsOf(text))
    }
}
