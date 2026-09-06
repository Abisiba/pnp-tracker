package dev.pnptracker.ui.feature.history

import dev.pnptracker.domain.history.HistoryChange
import dev.pnptracker.domain.history.HistoryPeriod
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.ui.Strings
import org.jetbrains.compose.resources.StringResource

/** How many digits a day, a month, an hour and a minute are written with. */
private const val TWO_DIGITS = 2

/**
 * The sentence one history line reads as.
 *
 * Exhaustive and with no fallback, which is the point: a kind of thing that can
 * happen will not compile until somebody has written the Turkish for it. Nothing
 * here falls back on an enum name, and there is nowhere for one to leak out —
 * every branch names a catalogue entry (PLAN 17), and the values that fill it
 * are the user's own words and numbers.
 */
fun historySentenceOf(change: HistoryChange): StringResource =
    when (change) {
        is HistoryChange.StageMoved -> Strings.History.lineStageMoved
        HistoryChange.TaskCompleted -> Strings.History.lineTaskCompleted
        HistoryChange.TaskReopened -> Strings.History.lineTaskReopened
        HistoryChange.TaskDeleted -> Strings.History.lineTaskDeleted
        HistoryChange.TaskRestored -> Strings.History.lineTaskRestored
        HistoryChange.TaskConvertedToText -> Strings.History.lineTaskConverted
        HistoryChange.GameDeleted -> Strings.History.lineGameDeleted
        HistoryChange.GameRestored -> Strings.History.lineGameRestored
        is HistoryChange.ShortageReported -> Strings.History.lineShortageReported
        is HistoryChange.ShortageResolved -> Strings.History.lineShortageResolved
    }

/**
 * What fills that sentence, in the order the catalogue entry expects.
 *
 * [taskName] and [stageName] are handed in already looked up, because both come
 * from somewhere this function has no business reaching: one is the user's own
 * text out of the database, the other is a catalogue entry of its own. A line
 * about the game itself uses neither, and asking for a stage name it does not
 * need is how a caller would find that out.
 */
fun historyArgumentsOf(
    change: HistoryChange,
    taskName: String,
    stageName: String? = null,
): List<String> =
    when (change) {
        is HistoryChange.StageMoved ->
            listOf(
                taskName,
                // Named rather than left to the caller's guess: a line that said
                // `LAMINATE` would be the developer's word on the user's screen.
                requireNotNull(stageName) { "A moved step has to be named before it can be shown." },
                change.previousQuantity.toString(),
                change.newQuantity.toString(),
            )

        is HistoryChange.ShortageReported -> listOf(taskName, change.quantity.toString())
        is HistoryChange.ShortageResolved -> listOf(taskName, change.quantity.toString())

        // The two that are about the game itself, and name no task at all.
        HistoryChange.GameDeleted, HistoryChange.GameRestored -> emptyList()

        HistoryChange.TaskCompleted,
        HistoryChange.TaskReopened,
        HistoryChange.TaskDeleted,
        HistoryChange.TaskRestored,
        HistoryChange.TaskConvertedToText,
        -> listOf(taskName)
    }

/**
 * One extra thing said under the sentence, when the user wrote one at the time.
 *
 * Only what was really recorded: PLAN 11 keeps the user's text as they typed it
 * and never makes one up, so a shortage recorded as a bare number produces no
 * detail lines at all.
 */
sealed interface HistoryDetail {
    /** What the user wrote about it. */
    data class Note(
        val text: String,
    ) : HistoryDetail

    /** Which card it was, on a card shortage (PLAN 7.4). */
    data class Card(
        val reference: String,
    ) : HistoryDetail

    /** Where in the pipeline it was noticed. */
    data class Stage(
        val stage: ProductionStage,
    ) : HistoryDetail
}

/** The detail lines of one change, in the order they are shown. */
fun historyDetailsOf(change: HistoryChange): List<HistoryDetail> =
    when (change) {
        is HistoryChange.ShortageReported ->
            detailsOf(note = change.note, cardReference = change.cardReference, stage = change.stage)

        is HistoryChange.ShortageResolved ->
            detailsOf(note = change.note, cardReference = change.cardReference, stage = change.stage)

        // Everything else says all it has to say in its sentence. A moved step
        // already carries its stage and both counts there, so repeating the
        // stage underneath would be the same fact twice.
        is HistoryChange.StageMoved,
        HistoryChange.TaskCompleted,
        HistoryChange.TaskReopened,
        HistoryChange.TaskDeleted,
        HistoryChange.TaskRestored,
        HistoryChange.TaskConvertedToText,
        HistoryChange.GameDeleted,
        HistoryChange.GameRestored,
        -> emptyList()
    }

private fun detailsOf(
    note: String?,
    cardReference: String?,
    stage: ProductionStage?,
): List<HistoryDetail> =
    listOfNotNull(
        cardReference?.let(HistoryDetail::Card),
        stage?.let(HistoryDetail::Stage),
        note?.let(HistoryDetail::Note),
    )

/** What each detail line is called. */
fun historyDetailLabelOf(detail: HistoryDetail): StringResource =
    when (detail) {
        is HistoryDetail.Note -> Strings.History.detailNote
        is HistoryDetail.Card -> Strings.History.detailCard
        is HistoryDetail.Stage -> Strings.History.detailStage
    }

/** What one date range is called. */
fun historyPeriodNameOf(period: HistoryPeriod): StringResource =
    when (period) {
        HistoryPeriod.ALL -> Strings.History.periodAll
        HistoryPeriod.LAST_WEEK -> Strings.History.periodWeek
        HistoryPeriod.LAST_MONTH -> Strings.History.periodMonth
    }

/**
 * The pieces of a moment, in the order the catalogue arranges them.
 *
 * The arranging is the catalogue's and not this function's: which of day and
 * month comes first, and what separates them, is written in `history_moment`
 * where a translator can reach it (PLAN 17). All that happens here is the
 * padding, so `06.09.2026 09:05` lines up under the one above it.
 */
fun momentArgumentsOf(moment: LocalMoment): List<String> =
    listOf(
        padded(moment.dayOfMonth),
        padded(moment.month),
        moment.year.toString(),
        padded(moment.hour),
        padded(moment.minute),
    )

private fun padded(value: Int): String = value.toString().padStart(TWO_DIGITS, '0')
