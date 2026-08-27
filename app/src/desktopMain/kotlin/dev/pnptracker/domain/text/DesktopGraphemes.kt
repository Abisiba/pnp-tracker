package dev.pnptracker.domain.text

import java.text.BreakIterator
import java.util.Locale

/**
 * The JVM's own grapheme segmentation, kept behind the shared declaration.
 *
 * `BreakIterator.getCharacterInstance` is the runtime's implementation of the
 * Unicode text segmentation rules, so nothing here needs a table of its own and
 * no dependency is added to get one. It is asked for the root locale rather than
 * the machine's: where a character begins is a property of the text, and a
 * result that changed with the user's settings would split one person's task
 * name differently from another's.
 */
actual fun graphemeBoundariesOf(text: String): List<Int> {
    if (text.isEmpty()) return listOf(0)
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val boundaries = mutableListOf<Int>()
    var at = iterator.first()
    while (at != BreakIterator.DONE) {
        boundaries += at
        at = iterator.next()
    }
    return boundaries
}
