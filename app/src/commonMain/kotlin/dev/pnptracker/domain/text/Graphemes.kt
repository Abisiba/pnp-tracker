package dev.pnptracker.domain.text

/**
 * Where each of the user's characters begins in a piece of text.
 *
 * A character to the person reading it is not a `Char` and not a code point: `á`
 * written as `a` plus a combining accent is two code units and one character,
 * `👍🏽` is four and one, `👨‍👩‍👧‍👦` is eleven and one, and `🇹🇷` is four and one.
 * PLAN 12.7 splits a task's name across its colours over exactly these — the
 * clusters the user sees — because a split made on code units would cut an
 * accent off its letter and a flag in half.
 *
 * The result is every boundary in order, `0` first and the text's length last,
 * so `boundaries.size - 1` is how many characters there are and
 * `text.substring(boundaries[i], boundaries[i + 1])` is the i'th one. Empty text
 * has the single boundary `0` and no characters.
 *
 * Declared here and answered per platform because there is no segmentation in
 * the common runtime, and the JVM's own is a `java.text` type that has no
 * business travelling through shared code. What it actually does is not taken on
 * trust either: `GraphemesTest` pins it to real combining marks, surrogate
 * pairs, skin tones, ZWJ sequences and flags.
 */
expect fun graphemeBoundariesOf(text: String): List<Int>

/** The user's own characters, in order; what [graphemeBoundariesOf] describes. */
fun graphemesOf(text: String): List<String> {
    val boundaries = graphemeBoundariesOf(text)
    return (0..<boundaries.size - 1).map { text.substring(boundaries[it], boundaries[it + 1]) }
}
