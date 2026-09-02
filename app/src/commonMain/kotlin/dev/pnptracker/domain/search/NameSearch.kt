package dev.pnptracker.domain.search

import dev.pnptracker.domain.text.composedForm

/**
 * Folds a name into the key general search compares on.
 *
 * Two steps and no more. The text is composed first, so `İSİM` typed as one code
 * point and as `I` plus a combining dot are the same word. Then case is folded
 * the Turkish way: `İ` and `i` are one letter, `I` and `ı` are another, and the
 * two pairs are kept apart.
 *
 * That last part is the whole reason this exists rather than reusing
 * `normalizeColorTerm`. The colour catalogue folds all four `i` letters together
 * on purpose, because `GRI` and `GRİ` are one colour typed on two keyboards. A
 * name is not a catalogue entry: doing the same here would make `Kılıç` and
 * `Kilit` share a key and would hand somebody searching for one a list of the
 * other. `ColorNormalization` says so in its own words, and this is the other
 * side of that sentence.
 *
 * Nothing else is touched. No accent is stripped, no punctuation is dropped, no
 * whitespace is collapsed inside the text, and every emoji, skin tone, zero
 * width joiner and flag comes through unchanged — a search that quietly rewrote
 * the user's characters would find things they never asked for.
 */
fun searchFold(text: String): String {
    val composed = composedForm(text)
    return buildString(composed.length) {
        composed.forEach { character ->
            append(
                when (character) {
                    // The two Turkish pairs, written out because the root locale
                    // maps `I` to `i` and would merge them.
                    'I' -> 'ı'
                    'İ' -> 'i'
                    // Char.lowercaseChar() maps by the root locale, never the
                    // machine's, so the answer does not change with a setting.
                    else -> character.lowercaseChar()
                },
            )
        }
    }
}

/**
 * One thing the user is looking for, folded once.
 *
 * A search runs over every name on screen, and folding the query again for each
 * of them would be the same work several hundred times. This holds the folded
 * form so the query is prepared once and the names are the only thing measured.
 *
 * Leading and trailing whitespace is dropped, because it is how a query looks
 * halfway through being typed and never what somebody meant to look for. Space
 * *inside* the query is kept: several words are an ordinary substring, in the
 * order they were written, so `kırmızı ev` finds `Büyük kırmızı ev` and does not
 * find `ev kırmızı`.
 */
data class SearchQuery(
    val raw: String,
) {
    private val folded: String = searchFold(raw.trim())

    /** True when nothing is being looked for, which matches everything. */
    val isEmpty: Boolean get() = folded.isEmpty()

    /** Whether one piece of text is something this query was looking for. */
    fun matches(text: String): Boolean = isEmpty || searchFold(text).contains(folded)

    /** Whether any of these is. */
    fun matchesAny(texts: Iterable<String>): Boolean = isEmpty || texts.any { matches(it) }

    companion object {
        val NONE = SearchQuery("")
    }
}
