package dev.pnptracker.domain.importprep

/** Left over when `İ` is lower cased; carries no meaning in a heading. */
private const val COMBINING_DOT_ABOVE = '\u0307'

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Folds a column heading into the form headings are compared in.
 *
 * Trims the ends, collapses runs of whitespace to one space and lower cases with
 * the root locale, so `  3D   PRINT ` and `3d print` are the same heading. The
 * dot that lower casing `İ` can leave behind is dropped, which is what makes
 * `EKSİK` and `Eksik` match.
 *
 * This is not the colour normaliser and must not become it: that one folds `ı`
 * into `i` to make colour names typed on different keyboards meet, and doing the
 * same to headings would quietly merge words a heading means to keep apart.
 *
 * The original cell text is never changed; only a copy taken for comparison is.
 */
fun normalizeHeader(text: String): String =
    buildString(text.length) {
        text.trim().replace(WHITESPACE_RUN, " ").forEach { character ->
            if (character != COMBINING_DOT_ABOVE) append(character.lowercaseChar())
        }
    }
