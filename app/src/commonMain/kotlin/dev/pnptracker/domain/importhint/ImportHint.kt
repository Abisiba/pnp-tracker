package dev.pnptracker.domain.importhint

import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType

/**
 * Something the import noticed in a source cell and wants to ask the user about.
 *
 * A hint is a question, never an answer. [decision] is fixed at
 * [HintDecision.PENDING] and cannot be constructed as anything else, so no code
 * path can produce a hint that already agrees with itself. What the user
 * eventually decides is stored on the draft or the raw block, not here.
 *
 * There is deliberately no `accepted` flag: a second way to say the same thing
 * is a second thing to keep in step.
 */
sealed interface ImportHint {
    /** What the detector saw, always as a range of the untouched cell text. */
    val evidence: TextRange?

    val confidence: HintConfidence

    val decision: HintDecision get() = HintDecision.PENDING

    /**
     * A `**` the user may have meant as "this part is done".
     *
     * One hint per marker: a cell listing five colours can have five of them,
     * and which task each belongs to is settled later, when the user cuts a
     * draft out of the text.
     */
    data class CompletionMarker(
        override val evidence: TextRange,
        override val confidence: HintConfidence = HintConfidence.HIGH,
    ) : ImportHint

    /**
     * A game cell filled in green, which in the reference file means the game
     * was finished.
     *
     * The fill is the only evidence. It is also not reliable on its own — the
     * file was kept by hand and has games that were finished without ever being
     * coloured — so this can never be applied without asking.
     */
    data class GameCompletion(
        val fillColorArgb: String,
        override val confidence: HintConfidence,
    ) : ImportHint {
        override val evidence: TextRange? get() = null
    }

    /** What the column a cell sits in says about the work it describes. */
    data class ColumnSuggestion(
        val suggestion: ReferenceColumnSuggestion,
        override val confidence: HintConfidence = HintConfidence.HIGH,
    ) : ImportHint {
        override val evidence: TextRange? get() = null
    }

    /**
     * A count the cell opens with, such as the `15` of `15 KIRMIZI**`.
     *
     * A suggestion for the total field and nothing more. PLAN 11.7 has tasks
     * whose amount is genuinely unknown, so an amount that was never written is
     * absence rather than a nought, and this hint is simply not produced.
     */
    data class Quantity(
        override val evidence: TextRange,
        val value: Int,
        override val confidence: HintConfidence = HintConfidence.MEDIUM,
    ) : ImportHint {
        init {
            require(value > 0) { "A count is either unknown or above nothing, was: $value" }
        }
    }

    /**
     * Colours written as a choice rather than as a list, such as
     * `MAVİ/AÇIK MAVİ`.
     *
     * The task stays out of every colour pool until the user picks one of
     * [options]; this hint only records that a choice appears to be on offer.
     */
    data class AlternativeColors(
        override val evidence: TextRange,
        val options: List<AlternativeColorOption>,
        override val confidence: HintConfidence = HintConfidence.MEDIUM,
    ) : ImportHint {
        init {
            require(options.size >= 2) {
                "An alternative needs at least two colours, got ${options.size}"
            }
        }
    }
}

/** One of the colours offered by an [ImportHint.AlternativeColors]. */
data class AlternativeColorOption(
    val canonicalName: String,
    val sourceText: String,
    val range: TextRange,
) {
    init {
        require(canonicalName.isNotBlank()) { "An option names a colour from the catalogue" }
        require(sourceText.length == range.length) {
            "The matched text must be exactly what ${range.startIndex}..${range.endIndex} covers"
        }
    }
}

/**
 * What the reference layout says about a column.
 *
 * [suggestedPoolType] is null for the three columns that are not pools at all.
 */
data class ReferenceColumnSuggestion(
    val columnIndex: Int,
    val sourceColumnType: SourceColumnType,
    val suggestedPoolType: PoolType?,
    val isMissing: Boolean = false,
    val isBorrowed: Boolean = false,
    val needsClassification: Boolean = false,
) {
    init {
        require(columnIndex >= 0) { "Column indexes are zero based, was: $columnIndex" }
        require(!(isMissing && isBorrowed)) { "A cell is either missing or borrowed, never both" }
        require(suggestedPoolType == null || !needsClassification) {
            "A column that already suggests a pool does not also need classifying"
        }
    }
}
