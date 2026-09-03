package dev.pnptracker.domain.csv

private const val QUOTE = '"'
private const val CARRIAGE_RETURN = '\r'
private const val LINE_FEED = '\n'

/** Why a file is not CSV at all, as opposed to CSV that says the wrong things. */
enum class CsvParseFailure {
    /** A field opened with a quote and the file ended before it closed. */
    UNCLOSED_QUOTED_FIELD,

    /** Something other than a separator or a line ending followed a closing quote. */
    TEXT_AFTER_QUOTED_FIELD,

    /** A quote turned up in the middle of a field that never opened with one. */
    QUOTE_IN_PLAIN_FIELD,
}

/**
 * A file that cannot be read as CSV.
 *
 * [lineNumber] counts physical lines from one, the way an editor does, so the
 * user can be sent straight to the line that has to be fixed. Nothing from
 * inside the file is carried: a message about a broken quote must not quote it
 * back.
 */
class CsvParseException(
    val failure: CsvParseFailure,
    val lineNumber: Int,
) : Exception("CSV cannot be read: $failure at line $lineNumber")

/**
 * One record of a CSV file: its fields, and where it started.
 *
 * [lineNumber] is the physical line the record began on, counting from one. A
 * record with a quoted field holding a line break covers several physical lines,
 * and the first of them is the one worth naming.
 */
data class CsvRecord(
    val lineNumber: Int,
    val fields: List<String>,
) {
    /** True for a record that holds nothing at all, such as a blank line. */
    val isEmpty: Boolean get() = fields.all { it.isEmpty() }
}

/**
 * RFC 4180, read one record at a time.
 *
 * Written by hand rather than taken from a library, because the whole of what is
 * needed is here and a CSV dependency would be a large surface for a small job
 * (PLAN 14.3 asks for exactly this: small, testable, RFC compliant).
 *
 * What it does not do is as important as what it does. It never trims a field,
 * never collapses a line ending, never turns `=1+1` into anything but the four
 * characters that were written, and never merges two identical records: the file
 * is a record of what somebody typed, and this only cuts it into fields.
 *
 * Reading is a single left-to-right pass with an index — no recursion, no
 * backtracking, and no string built by repeated concatenation — so a file costs
 * time in proportion to its length however long it is.
 *
 * A leading byte order mark is dropped once, here rather than at the file
 * boundary, so that the first heading of a file saved by a spreadsheet compares
 * equal to the same heading typed by hand.
 */
class CsvReader(
    text: String,
    private val delimiter: CsvDelimiter,
) {
    private val text: String = if (text.startsWith(BYTE_ORDER_MARK)) text.substring(1) else text

    private var index = 0
    private var lineNumber = 1

    /** The next record, or null once the file is used up. */
    fun readRecord(): CsvRecord? {
        if (index >= text.length) return null

        val startLine = lineNumber
        val fields = mutableListOf<String>()
        while (true) {
            fields += readField()
            if (index >= text.length) break
            val character = text[index]
            if (character == delimiter.character) {
                index++
                continue
            }
            consumeLineEnding()
            break
        }
        return CsvRecord(lineNumber = startLine, fields = fields)
    }

    private fun readField(): String = if (index < text.length && text[index] == QUOTE) readQuotedField() else readPlainField()

    private fun readQuotedField(): String {
        val openedAt = lineNumber
        index++
        val field = StringBuilder()
        while (true) {
            if (index >= text.length) throw CsvParseException(CsvParseFailure.UNCLOSED_QUOTED_FIELD, openedAt)
            val character = text[index]
            if (character == QUOTE) {
                // Two quotes stand for one; a single quote ends the field.
                if (index + 1 < text.length && text[index + 1] == QUOTE) {
                    field.append(QUOTE)
                    index += 2
                    continue
                }
                index++
                break
            }
            // A line break inside quotes belongs to the value and is kept exactly
            // as written, but the file still moved on to the next line.
            if (character == LINE_FEED) lineNumber++
            field.append(character)
            index++
        }

        if (index < text.length && text[index] != delimiter.character && !isLineEnding(text[index])) {
            throw CsvParseException(CsvParseFailure.TEXT_AFTER_QUOTED_FIELD, lineNumber)
        }
        return field.toString()
    }

    private fun readPlainField(): String {
        val start = index
        while (index < text.length) {
            val character = text[index]
            if (character == delimiter.character || isLineEnding(character)) break
            // A field that did not open with a quote cannot hold one. Accepting
            // it would mean inventing a rule RFC 4180 does not have, and two
            // readers would then disagree about what the file says.
            if (character == QUOTE) throw CsvParseException(CsvParseFailure.QUOTE_IN_PLAIN_FIELD, lineNumber)
            index++
        }
        return text.substring(start, index)
    }

    private fun consumeLineEnding() {
        if (text[index] == CARRIAGE_RETURN) {
            index++
            if (index < text.length && text[index] == LINE_FEED) index++
        } else {
            index++
        }
        lineNumber++
    }

    private fun isLineEnding(character: Char): Boolean = character == CARRIAGE_RETURN || character == LINE_FEED
}

/**
 * Every record of [text], in the order the file wrote them.
 *
 * Two identical records stay two records. Deciding that a repeat is a mistake is
 * not a reader's decision to make.
 *
 * @throws CsvParseException if the file is not CSV.
 */
fun parseCsvRecords(
    text: String,
    delimiter: CsvDelimiter,
): List<CsvRecord> {
    val reader = CsvReader(text, delimiter)
    val records = mutableListOf<CsvRecord>()
    while (true) {
        records += reader.readRecord() ?: break
    }
    return records
}
