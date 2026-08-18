package dev.pnptracker.domain.rules

/** Left over when `İ` is lower cased; carries no meaning of its own here. */
private const val COMBINING_DOT_ABOVE = '\u0307'

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Folds a color name or alias into the key the color catalogue matches on.
 *
 * The Turkish dotted and dotless i are the problem this solves: `GRİ`, `GRI`,
 * `Gri` and `gri` are the same color typed on different keyboards, so all four
 * `i` letters fold to a plain `i`. Lower casing `İ` can also leave a combining
 * dot behind, which is dropped here. Every other Turkish letter is kept, so
 * `Açık Mavi` stays recognisably itself.
 *
 * This is deliberately only for the color catalogue. General search in the
 * application must not use it: folding `ı` into `i` would merge words that a
 * user means to keep apart.
 *
 * @throws IllegalArgumentException if nothing is left after trimming.
 */
fun normalizeColorTerm(term: String): String {
    val collapsed = term.trim().replace(WHITESPACE_RUN, " ")
    require(collapsed.isNotEmpty()) { "A color term cannot be blank." }
    val folded =
        buildString(collapsed.length) {
            collapsed.forEach { character ->
                when (character) {
                    'i', 'I', 'İ', 'ı' -> append('i')
                    COMBINING_DOT_ABOVE -> Unit
                    // Char.lowercaseChar() maps by the root locale, never the system one.
                    else -> append(character.lowercaseChar())
                }
            }
        }
    require(folded.isNotEmpty()) { "A color term cannot consist only of combining marks: '$term'" }
    return folded
}
