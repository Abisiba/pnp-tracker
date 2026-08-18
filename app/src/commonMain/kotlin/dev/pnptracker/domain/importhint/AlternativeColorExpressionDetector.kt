package dev.pnptracker.domain.importhint

/** The words that offer a choice rather than continue a list. */
private const val OR_WORD = "veya"
private const val OR_PHRASE_FIRST = "ya"
private const val OR_PHRASE_SECOND = "da"

private const val SLASH = '/'
private const val COMMA = ','
private const val STAR = '*'

/**
 * Finds colours written as a choice, such as `MAVİ/AÇIK MAVİ` or
 * `Açık Mavi veya Mavi`.
 *
 * The rule is narrow on purpose, because the cost of the two mistakes is not the
 * same. Missing a choice leaves the user to make it by hand. Inventing one turns
 * a single two-coloured model into two tasks that were never asked for, so
 * `BEYAZ KAHVERENGİ` — two colours simply written next to each other — is not a
 * choice, and neither is `MAVİ, KIRMIZI` or `KIRMIZI VE MAVİ`.
 *
 * A choice needs all of:
 *
 * - at least two phrases that the given vocabulary recognises as colours, and
 * - a `/`, `veya` or `ya da` between each neighbouring pair.
 *
 * A comma and a bare `ve` are lists, not choices. A `/` that has no colour on
 * both sides is just a character, which is why a URL or `BOARD/TOKEN` produces
 * nothing.
 *
 * Longer colour names win over shorter ones, so `Açık Mavi` is one colour rather
 * than a stray `Mavi`.
 *
 * Every range in the result indexes the original [rawText]. Normalisation is
 * only ever applied to a copy taken for comparison, because folding letters can
 * change a string's length and an index from the folded copy would point at the
 * wrong character.
 *
 * The result is ordered left to right and the groups never overlap. Detecting a
 * choice creates nothing: no task, no colour relation, no selection.
 */
fun detectAlternativeColorExpressions(
    rawText: String,
    vocabulary: ColorVocabulary,
): List<ImportHint.AlternativeColors> {
    val tokens = tokenize(rawText)
    val hints = mutableListOf<ImportHint.AlternativeColors>()

    var index = 0
    while (index < tokens.size) {
        val group = readAlternationAt(tokens, index, rawText, vocabulary)
        if (group == null) {
            index++
            continue
        }
        if (group.options.size >= 2) {
            hints +=
                ImportHint.AlternativeColors(
                    evidence =
                        TextRange(
                            group.options
                                .first()
                                .range.startIndex,
                            group.options
                                .last()
                                .range.endIndex,
                        ),
                    options = group.options,
                )
        }
        // Whether or not it became a hint, the tokens it covered are consumed, so
        // groups can never overlap.
        index = group.nextTokenIndex
    }
    return hints
}

private class Alternation(
    val options: List<AlternativeColorOption>,
    val nextTokenIndex: Int,
)

private fun readAlternationAt(
    tokens: List<Token>,
    start: Int,
    rawText: String,
    vocabulary: ColorVocabulary,
): Alternation? {
    val first = readColorPhraseAt(tokens, start, rawText, vocabulary) ?: return null
    val options = mutableListOf(first.option)
    var cursor = first.nextTokenIndex

    while (true) {
        val separatorEnd = readSeparatorAt(tokens, cursor) ?: break
        val next = readColorPhraseAt(tokens, separatorEnd, rawText, vocabulary) ?: break
        options += next.option
        cursor = next.nextTokenIndex
    }
    return Alternation(options, cursor)
}

private class ColorPhrase(
    val option: AlternativeColorOption,
    val nextTokenIndex: Int,
)

/**
 * Reads the longest run of neighbouring words that names a colour.
 *
 * Only word tokens can join into a phrase, so a separator always ends one and a
 * phrase can never straddle a slash.
 */
private fun readColorPhraseAt(
    tokens: List<Token>,
    start: Int,
    rawText: String,
    vocabulary: ColorVocabulary,
): ColorPhrase? {
    if (start >= tokens.size || tokens[start].kind != TokenKind.WORD) return null

    val longest = minOf(vocabulary.longestTermWordCount, tokens.size - start)
    for (wordCount in longest downTo 1) {
        val last = start + wordCount - 1
        if (last >= tokens.size) continue
        if ((start..last).any { tokens[it].kind != TokenKind.WORD }) continue

        val range = TextRange(tokens[start].startIndex, tokens[last].endIndex)
        val sourceText = range.textIn(rawText)
        val canonical = vocabulary.canonicalNameOf(sourceText) ?: continue
        return ColorPhrase(
            option = AlternativeColorOption(canonical, sourceText, range),
            nextTokenIndex = last + 1,
        )
    }
    return null
}

/** The token index just after a separator, or null if there is not one here. */
private fun readSeparatorAt(
    tokens: List<Token>,
    start: Int,
): Int? {
    if (start >= tokens.size) return null
    val token = tokens[start]
    if (token.kind == TokenKind.SLASH) return start + 1
    if (token.kind != TokenKind.WORD) return null
    // Neither separator word contains a dotted or dotless i, so ordinary case
    // folding is enough and the colour normaliser is not the right tool here.
    if (token.text.equals(OR_WORD, ignoreCase = true)) return start + 1
    if (token.text.equals(OR_PHRASE_FIRST, ignoreCase = true) &&
        start + 1 < tokens.size &&
        tokens[start + 1].kind == TokenKind.WORD &&
        tokens[start + 1].text.equals(OR_PHRASE_SECOND, ignoreCase = true)
    ) {
        return start + 2
    }
    return null
}

private enum class TokenKind { WORD, SLASH, COMMA, STAR }

private class Token(
    val kind: TokenKind,
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
)

/**
 * Splits [rawText] into words and the punctuation that can separate them.
 *
 * A slash is its own token even with no space around it, which is what makes
 * `MAVİ/AÇIK MAVİ` readable as three parts rather than as one odd word.
 *
 * A star is split off for the same reason: the source file writes completion
 * markers straight onto the end of a colour, and `AÇIK MAVİ**` has to stay
 * recognisable as `Açık Mavi`. Splitting rather than ignoring keeps the two
 * apart, so `MAVİ**KIRMIZI` is still two colours written next to each other and
 * not a choice — a star separates nothing.
 */
private fun tokenize(rawText: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var index = 0
    while (index < rawText.length) {
        val character = rawText[index]
        when {
            character.isWhitespace() -> index++

            character == SLASH -> {
                tokens += Token(TokenKind.SLASH, "/", index, index + 1)
                index++
            }

            character == COMMA -> {
                tokens += Token(TokenKind.COMMA, ",", index, index + 1)
                index++
            }

            character == STAR -> {
                tokens += Token(TokenKind.STAR, "*", index, index + 1)
                index++
            }

            else -> {
                val start = index
                while (index < rawText.length &&
                    !rawText[index].isWhitespace() &&
                    rawText[index] != SLASH &&
                    rawText[index] != COMMA &&
                    rawText[index] != STAR
                ) {
                    index++
                }
                tokens += Token(TokenKind.WORD, rawText.substring(start, index), start, index)
            }
        }
    }
    return tokens
}
