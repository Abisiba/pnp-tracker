package dev.pnptracker.domain.importhint

/**
 * A stretch of the raw cell text, as UTF-16 indexes into a Kotlin `String`.
 *
 * These are the indexes `String.substring` and Compose text selection use, so a
 * range can be highlighted or cut out without converting anything. They always
 * refer to the untouched source text, never to a normalised copy of it.
 *
 * [startIndex] is inclusive, [endIndex] exclusive.
 */
data class TextRange(
    val startIndex: Int,
    val endIndex: Int,
) {
    init {
        require(startIndex >= 0) { "A range cannot start before the text, was: $startIndex" }
        require(endIndex > startIndex) {
            "A range must cover at least one character, was: $startIndex..$endIndex"
        }
    }

    val length: Int get() = endIndex - startIndex

    /** @throws IndexOutOfBoundsException if this range does not fit inside [source]. */
    fun textIn(source: String): String = source.substring(startIndex, endIndex)

    fun overlaps(other: TextRange): Boolean = startIndex < other.endIndex && other.startIndex < endIndex
}
