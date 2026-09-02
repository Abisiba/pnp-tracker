package dev.pnptracker.domain.text

/**
 * The same text written the composed way, so two spellings of one word compare
 * equal.
 *
 * `İ` can arrive as one code point or as `I` followed by a combining dot, and
 * `á` as one or as `a` and an accent. They are the same characters — Unicode
 * calls them canonically equivalent — and a user searching for a name typed on
 * one keyboard has to find the same name typed on another. Composing both sides
 * before comparing is what makes that true, and it is the only normalisation
 * general search does: nothing is stripped, no accent is dropped, and an emoji,
 * a skin tone, a zero width joiner and a flag all come back exactly as they went
 * in.
 *
 * Declared here and answered per platform because the common runtime has no
 * normaliser, and the JVM's is a `java.text` type that has no business
 * travelling through shared code.
 */
expect fun composedForm(text: String): String
