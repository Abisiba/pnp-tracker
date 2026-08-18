package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.rules.normalizeColorTerm

/** A colour the catalogue knows, together with the other ways it gets written. */
data class ColorVocabularyEntry(
    val canonicalName: String,
    val aliases: List<String> = emptyList(),
) {
    init {
        require(canonicalName.isNotBlank()) { "A colour entry needs a name" }
    }
}

/**
 * The colour names a detector is allowed to recognise, handed in from outside.
 *
 * The domain does not reach into the database for this on purpose: the same
 * detector has to work against the stored catalogue in the running application
 * and against a three-colour fixture in a test, and neither of those should have
 * to know about the other.
 *
 * Terms are matched after [normalizeColorTerm], so the four Turkish i letters
 * and stray spacing do not change the answer.
 */
class ColorVocabulary private constructor(
    private val canonicalByTerm: Map<String, String>,
    /** How many words the longest known colour name has, such as two for `Açık Mavi`. */
    val longestTermWordCount: Int,
) {
    val size: Int get() = canonicalByTerm.size

    /** The canonical colour [phrase] names, or null if it names no colour. */
    fun canonicalNameOf(phrase: String): String? {
        val normalized = phrase.trim()
        if (normalized.isEmpty()) return null
        return canonicalByTerm[normalizeColorTerm(normalized)]
    }

    companion object {
        fun of(entries: List<ColorVocabularyEntry>): ColorVocabulary {
            val canonicalByTerm = mutableMapOf<String, String>()
            var longest = 1
            entries.forEach { entry ->
                (listOf(entry.canonicalName) + entry.aliases).forEach { term ->
                    val normalized = normalizeColorTerm(term)
                    canonicalByTerm[normalized] = entry.canonicalName
                    longest = maxOf(longest, normalized.count { it == ' ' } + 1)
                }
            }
            return ColorVocabulary(canonicalByTerm.toMap(), longest)
        }
    }
}
