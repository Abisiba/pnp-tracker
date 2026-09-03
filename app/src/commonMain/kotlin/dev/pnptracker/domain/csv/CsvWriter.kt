package dev.pnptracker.domain.csv

/**
 * Written at the head of the file so a spreadsheet opens it as UTF-8.
 *
 * Excel in a Turkish locale reads a CSV with no mark as the system's legacy code
 * page, which turns every `ş` and `ğ` into something else. The mark costs three
 * bytes and settles the question.
 */
const val BYTE_ORDER_MARK: Char = '\uFEFF'

private const val QUOTE = '"'
private const val RECORD_SEPARATOR = "\r\n"

/**
 * One field, quoted if RFC 4180 says it has to be.
 *
 * A field is left exactly as it is unless it holds the separator, a quote or a
 * line ending; then it is wrapped and its own quotes are doubled. Nothing else
 * is done to it — no trimming, no collapsing of line endings, no conversion of
 * anything into anything — because the point of writing a value out is that it
 * can be read back as the value that was written.
 */
fun csvField(
    value: String,
    delimiter: CsvDelimiter = CsvDelimiter.COMMA,
): String {
    val mustQuote =
        value.any { character ->
            character == delimiter.character || character == QUOTE || character == '\r' || character == '\n'
        }
    if (!mustQuote) return value
    return buildString(value.length + 2) {
        append(QUOTE)
        value.forEach { character ->
            if (character == QUOTE) append(QUOTE)
            append(character)
        }
        append(QUOTE)
    }
}

/** One record, fields joined by the separator and nothing after the last one. */
fun csvRecord(
    fields: List<String>,
    delimiter: CsvDelimiter = CsvDelimiter.COMMA,
): String = fields.joinToString(delimiter.character.toString()) { csvField(it, delimiter) }

/**
 * A whole CSV file, as text.
 *
 * Every record ends with CRLF, the last one included: a file that ends mid-record
 * is a file some readers will silently shorten, and "sometimes there is a line
 * ending at the end" is not a rule anybody can test. The same records always give
 * the same bytes.
 *
 * Built by appending to one builder rather than by joining a list of strings, so
 * a thousand rows cost a thousand appends and not a thousand copies of the whole
 * file so far.
 */
fun csvDocument(
    records: List<List<String>>,
    delimiter: CsvDelimiter = CsvDelimiter.COMMA,
    withByteOrderMark: Boolean = true,
): String =
    buildString {
        if (withByteOrderMark) append(BYTE_ORDER_MARK)
        records.forEach { record ->
            append(csvRecord(record, delimiter))
            append(RECORD_SEPARATOR)
        }
    }
