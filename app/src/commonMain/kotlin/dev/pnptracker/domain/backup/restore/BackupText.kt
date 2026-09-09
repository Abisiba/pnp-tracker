package dev.pnptracker.domain.backup.restore

/**
 * The mark some programs put at the head of a UTF-8 file.
 *
 * The backup writer does not write one. It is accepted on the way in for the
 * same reason `CsvParser` accepts one: a file that has been through a text
 * editor may come back with a mark it did not leave with, and refusing it would
 * be refusing the user's own backup over three bytes that carry no meaning. One
 * is skipped; a second is left where it is and the document is then not JSON.
 */
internal const val BYTE_ORDER_MARK: Char = '\uFEFF'

/**
 * How deeply a document may nest.
 *
 * A backup nests four deep — the document, `data`, one array, one row — and
 * nothing in the format can ever nest further, because the format has no
 * recursive shape in it. Thirty-two is many times that and still nowhere near
 * where a parser gets into trouble, which is the room a limit like this wants:
 * enough that no honest file meets it, small enough that no hostile one gets
 * past it.
 */
internal const val MAXIMUM_JSON_DEPTH: Int = 32

/**
 * The bytes as text, or nothing if they are not UTF-8.
 *
 * Strictly, and that is the whole point. The tolerant reading — the one every
 * platform offers by default — turns a byte it cannot make sense of into a
 * replacement character and carries on, which would let a corrupted backup
 * through with a silently altered game name in it. A backup is either the bytes
 * that were written or it is refused.
 *
 * Nothing here consults a platform default charset. UTF-16 and UTF-32 files
 * either fail this outright, when their byte order mark is not valid UTF-8, or
 * pass it as text full of NUL characters that is then not JSON; either way they
 * are refused rather than half read.
 */
internal fun decodeUtf8(bytes: ByteArray): Checked<String> {
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (notUtf8: CharacterCodingException) {
            return refuse(BackupProblem.INVALID_UTF8)
        }
    return Checked.Passed(if (text.startsWith(BYTE_ORDER_MARK)) text.substring(1) else text)
}

/**
 * Looks over the JSON text before anything parses it.
 *
 * There are two things a parser will not answer for us, and a third this
 * deliberately does not ask.
 *
 * The first is repeated keys. `kotlinx.serialization` reads an object into a
 * map, and a map keeps one value per key: a document naming `formatVersion`
 * twice would arrive saying whichever came last and look perfectly ordinary.
 * PLAN 14.4.1 refuses a repeated key outright, and the only place that decision
 * can be made is before the repetition has been thrown away.
 *
 * The second is depth. A file of ten thousand opening brackets is a few
 * kilobytes and would take a recursive-descent parser down with it. The limit is
 * checked as the brackets are counted, not after.
 *
 * The third is what this is not: a second JSON parser. It does not decide what a
 * number is, whether a value belongs where it sits, or whether the document is
 * well formed — that is the real parser's work, and a second opinion about it
 * would eventually disagree with the first. It reads strings, because it must in
 * order to know which quoted things are keys and to not be fooled by a brace
 * inside one, and it counts brackets. Everything else it steps over.
 *
 * Keys are compared with their escapes resolved, so a key written out and the
 * same key written with `\u` escapes are one key — which they are to every JSON
 * reader, and writing one of each would otherwise be a way past the check.
 *
 * @return the reason to refuse the file, or null to go on.
 */
internal fun scanJsonText(
    text: String,
    maximumDepth: Int = MAXIMUM_JSON_DEPTH,
): BackupRejection? = JsonScan(text, maximumDepth).run()

private class JsonScan(
    private val text: String,
    private val maximumDepth: Int,
) {
    private var at = 0

    /** One entry per open bracket: the keys seen so far, or null for an array. */
    private val open = ArrayDeque<MutableSet<String>?>()
    private var pendingKey: String? = null
    private var closed = false

    fun run(): BackupRejection? {
        while (at < text.length) {
            when (val character = text[at]) {
                ' ', '\t', '\n', '\r' -> at++

                '"' -> {
                    if (closed) return malformed()
                    pendingKey = readString() ?: return malformed()
                }

                ':' -> {
                    val keys = open.lastOrNull()
                    val key = pendingKey
                    if (keys != null && key != null && !keys.add(key)) {
                        return BackupRejection(BackupProblem.DUPLICATE_KEY)
                    }
                    pendingKey = null
                    at++
                }

                ',' -> {
                    pendingKey = null
                    at++
                }

                '{', '[' -> {
                    if (closed) return malformed()
                    if (open.size + 1 > maximumDepth) return BackupRejection(BackupProblem.TOO_DEEPLY_NESTED)
                    open.addLast(if (character == '{') mutableSetOf() else null)
                    pendingKey = null
                    at++
                }

                '}', ']' -> {
                    // Checked for emptiness rather than by asking the removal to
                    // answer: an array scope is stored as a null, so a removal
                    // that returns null means an array closed, not that there
                    // was nothing to close.
                    if (open.isEmpty()) return malformed()
                    open.removeLast()
                    if (open.isEmpty()) closed = true
                    pendingKey = null
                    at++
                }

                else -> {
                    // Anything after the outermost value has closed is trailing
                    // rubbish, whatever it looks like.
                    if (closed) return malformed()
                    at++
                }
            }
        }
        return null
    }

    private fun malformed() = BackupRejection(BackupProblem.MALFORMED_JSON)

    /** The string starting at [at] with its escapes resolved, or null if it is not one. */
    private fun readString(): String? {
        at++
        val built = StringBuilder()
        while (at < text.length) {
            when (val character = text[at]) {
                '"' -> {
                    at++
                    return built.toString()
                }

                '\\' -> {
                    at++
                    if (at >= text.length) return null
                    built.append(readEscape() ?: return null)
                }

                else -> {
                    // A raw control character is not allowed inside a JSON
                    // string; letting one through would mean the scanner and
                    // the parser disagreed about where the string ended.
                    if (character < ' ') return null
                    built.append(character)
                    at++
                }
            }
        }
        return null
    }

    /** What one escape sequence stands for, or null if it is not a legal one. */
    private fun readEscape(): String? {
        val marker = text[at]
        at++
        val simple =
            when (marker) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> null
                else -> return null
            }
        if (simple != null) return simple.toString()

        val first = readHexQuad() ?: return null
        if (!first.isHighSurrogate()) {
            // A low surrogate on its own is not a character. Refused here rather
            // than left to travel unpaired, which would surface much later as a
            // checksum that does not match.
            return if (first.isLowSurrogate()) null else first.toString()
        }
        if (at + 1 >= text.length || text[at] != '\\' || text[at + 1] != 'u') return null
        at += 2
        val second = readHexQuad() ?: return null
        if (!second.isLowSurrogate()) return null
        return charArrayOf(first, second).concatToString()
    }

    private fun readHexQuad(): Char? {
        if (at + 4 > text.length) return null
        var value = 0
        repeat(4) {
            val digit = text[at].digitToIntOrNull(radix = 16) ?: return null
            value = value * 16 + digit
            at++
        }
        return value.toChar()
    }
}
