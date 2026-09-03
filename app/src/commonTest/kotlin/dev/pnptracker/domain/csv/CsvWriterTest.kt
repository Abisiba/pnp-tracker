package dev.pnptracker.domain.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the CSV writer puts on disk, character by character.
 *
 * A string in and a string out: no file, no database, no screen. The writer's
 * whole job is that a value survives being written and read again, so what is
 * pinned here is the exact bytes — a reader written by somebody else has nothing
 * but these to go on.
 */
class CsvWriterTest {
    private val bom = BYTE_ORDER_MARK.toString()

    // --------------------------------------------------------------- quoting

    @Test
    fun `an ordinary value is written as it is`() {
        assertEquals("Kırmızı ev", csvField("Kırmızı ev"))
    }

    @Test
    fun `a value holding the separator is quoted`() {
        assertEquals("\"kırmızı, mavi\"", csvField("kırmızı, mavi"))
    }

    @Test
    fun `a quote inside a value is doubled and the value is quoted`() {
        assertEquals("\"12 \"\"büyük\"\" ev\"", csvField("12 \"büyük\" ev"))
    }

    @Test
    fun `a carriage return makes a value quoted`() {
        assertEquals("\"üst\ralt\"", csvField("üst\ralt"))
    }

    @Test
    fun `a line feed makes a value quoted`() {
        assertEquals("\"üst\nalt\"", csvField("üst\nalt"))
    }

    @Test
    fun `a note with a CRLF in it stays one field`() {
        val note = "ilk satır\r\nikinci satır"

        val record = csvRecord(listOf("Harmonies", note))

        assertEquals("Harmonies,\"ilk satır\r\nikinci satır\"", record)
        assertEquals(note, parseCsvRecords(record, CsvDelimiter.COMMA).single().fields[1], "the note did not survive")
    }

    @Test
    fun `an empty value is an empty field`() {
        assertEquals("", csvField(""))
        assertEquals("a,,c", csvRecord(listOf("a", "", "c")))
    }

    @Test
    fun `a semicolon file quotes semicolons and leaves commas alone`() {
        assertEquals("\"a;b\"", csvField("a;b", CsvDelimiter.SEMICOLON))
        assertEquals("a,b", csvField("a,b", CsvDelimiter.SEMICOLON))
    }

    // --------------------------------------------------------------- unicode

    @Test
    fun `Turkish letters are written unchanged`() {
        val turkish = "IŞIKLI DİREK ığüşöçİĞÜŞÖÇ"

        assertEquals(turkish, csvField(turkish))
    }

    @Test
    fun `a combining mark stays beside the letter it belongs to`() {
        val decomposed = "İsim"

        assertEquals(decomposed, csvField(decomposed))
    }

    @Test
    fun `a surrogate pair emoji survives`() {
        assertEquals("😀 gülen", csvField("😀 gülen"))
    }

    @Test
    fun `a skin tone emoji keeps its modifier`() {
        assertEquals("👍🏽", csvField("👍🏽"))
    }

    @Test
    fun `a zero width joiner family stays one sequence`() {
        assertEquals("👨‍👩‍👧‍👦", csvField("👨‍👩‍👧‍👦"))
    }

    @Test
    fun `a flag keeps both of its regional indicators`() {
        assertEquals("🇹🇷", csvField("🇹🇷"))
    }

    // ---------------------------------------------------------- the whole file

    @Test
    fun `the file begins with a byte order mark`() {
        val document = csvDocument(listOf(listOf("game")))

        assertTrue(document.startsWith(bom), "a spreadsheet would read this as the system code page")
        assertEquals('\uFEFF', BYTE_ORDER_MARK)
    }

    @Test
    fun `every record ends with CRLF, the last one included`() {
        val document = csvDocument(listOf(listOf("a"), listOf("b")))

        assertEquals(bom + "a\r\nb\r\n", document)
    }

    @Test
    fun `the same records always give the same bytes`() {
        val records = listOf(listOf("game", "task"), listOf("Harmonies", "Kırmızı ev"))

        assertEquals(csvDocument(records), csvDocument(records))
        assertEquals(bom + "game,task\r\nHarmonies,Kırmızı ev\r\n", csvDocument(records))
    }

    @Test
    fun `a document can be written without a mark, and reads back the same`() {
        val records = listOf(listOf("game"), listOf("Harmonies"))

        val document = csvDocument(records, withByteOrderMark = false)

        assertEquals("game\r\nHarmonies\r\n", document)
        assertEquals(records, parseCsvRecords(document, CsvDelimiter.COMMA).map { it.fields })
    }

    @Test
    fun `what the writer writes, the reader reads back`() {
        val awkward =
            listOf(
                listOf("game", "notes"),
                listOf("Har,monies", "iki\r\nsatır"),
                listOf("\"tırnaklı\"", ""),
                listOf("😀 IŞIK", "a;b"),
            )

        val document = csvDocument(awkward, withByteOrderMark = false)

        assertEquals(awkward, parseCsvRecords(document, CsvDelimiter.COMMA).map { it.fields })
    }
}
