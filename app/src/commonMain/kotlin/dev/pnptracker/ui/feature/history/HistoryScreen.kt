package dev.pnptracker.ui.feature.history

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.history.HistoryChange
import dev.pnptracker.domain.history.HistoryEntry
import dev.pnptracker.domain.history.HistoryGame
import dev.pnptracker.domain.history.HistoryPeriod
import dev.pnptracker.domain.time.localMomentOf
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.search.FilterButton
import dev.pnptracker.ui.feature.search.FilterChoice
import dev.pnptracker.ui.feature.search.FilterChoiceRow
import dev.pnptracker.ui.feature.search.FilterPanel
import dev.pnptracker.ui.feature.search.FilterSectionTitle
import dev.pnptracker.ui.feature.search.FilterSummary
import dev.pnptracker.ui.feature.search.closesFilterPanelOnEscape
import dev.pnptracker.ui.feature.tasks.focusOutline
import dev.pnptracker.ui.stageNameOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val MaxContentWidth = 720.dp
private val ChipShape = RoundedCornerShape(8.dp)

/** How far one arrow key moves the list, in pixels: about one line. */
private const val LINE_STEP = 72f

/** How much of the visible height one page key moves, leaving an overlap to read from. */
private const val PAGE_OVERLAP = 0.9f

/**
 * What has happened, newest first (PLAN 12.15).
 *
 * A reading and nothing else. There is no control on this screen that changes a
 * record, and there is nothing behind one either: the section is given a source
 * that can only observe, so a screen that wanted to write would have nothing to
 * write with. PLAN 5.12 makes the history something appended by the transaction
 * that caused it, and a screen that could edit it would make it a second,
 * disagreeing record.
 *
 * A column down the page rather than a table. Every line is one sentence about
 * one thing, and a sentence reads better on a line of its own than in a cell.
 */
@Composable
fun HistoryScreen(controller: HistoryController) {
    LaunchedEffect(controller) { controller.observeHistory() }

    val state = controller.state
    Column(
        modifier = Modifier.fillMaxHeight().widthIn(max = MaxContentWidth).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Strings.ScreenTitles.history),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(Strings.ScreenDescriptions.history),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HistoryToolbar(controller, state)
        when (val content = state.content) {
            HistoryContentState.Loading -> Message(stringResource(Strings.History.loading))
            HistoryContentState.Failed -> Message(stringResource(Strings.History.error))
            is HistoryContentState.Content ->
                when {
                    // Two different empty screens, answered differently: one is
                    // a filter to loosen, the other is a history nothing has
                    // been recorded into yet.
                    content.hasHiddenEntries ->
                        FilteredEmpty(
                            title = stringResource(Strings.History.emptyFiltered),
                            hint = stringResource(Strings.History.emptyFilteredHint),
                            onClearAll = controller::clearFilters,
                        )

                    content.log.isEmpty -> Message(stringResource(Strings.History.empty))
                    else -> HistoryList(content.shown)
                }
        }
    }
}

/**
 * The filter button, what is in force, and the choices when they are open.
 *
 * The same three parts the pools use, in the same order and from the same
 * components, so the two sections are worked the same way. There is no search
 * box: the only names on this screen belong to games and tasks, and the game is
 * already a choice of its own.
 */
@Composable
private fun HistoryToolbar(
    controller: HistoryController,
    state: HistoryScreenState,
) {
    val filterFocus = remember { FocusRequester() }
    // The keyboard comes back to the button the panel was opened from, rather
    // than to wherever the closing panel happened to leave it.
    LaunchedEffect(state.focusRecall) {
        if (state.filterSurface == HistoryFilterSurface.CLOSED) {
            runCatching { filterFocus.requestFocus() }
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .closesFilterPanelOnEscape(
                    state.filterSurface == HistoryFilterSurface.OPEN,
                    controller::closeFilters,
                ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterButton(
                chosenCount = state.chosenFilterCount,
                onClick =
                    if (state.filterSurface == HistoryFilterSurface.OPEN) {
                        controller::closeFilters
                    } else {
                        controller::openFilters
                    },
                focus = filterFocus,
            )
            FilterSummary(parts = historyFilterSummaryOf(state), onClearAll = controller::clearFilters)
        }
        if (state.filterSurface == HistoryFilterSurface.OPEN) {
            FilterPanel(
                onClose = controller::closeFilters,
                onClearAll = controller::clearFilters,
                hasChoices = state.chosenFilterCount > 0,
            ) {
                HistoryFilterChoices(controller, state)
            }
        }
        // How much is on screen, in words rather than as a badge nobody reads.
        Text(
            text = stringResource(Strings.History.shownCount, state.shown.size.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What is in force right now, written out.
 *
 * The same sentence the pools show, built from the same pieces: the game by its
 * own name, and the date range by the words the user chose it with.
 */
@Composable
private fun historyFilterSummaryOf(state: HistoryScreenState): List<String> {
    val parts = mutableListOf<String>()
    val chosenGame = state.filter.gameId
    if (chosenGame != null) {
        val name =
            (state.content as? HistoryContentState.Content)
                ?.log
                ?.games
                ?.firstOrNull { it.id == chosenGame }
                ?.name
        // A game chosen and then no longer named by any line still says which
        // one it was as far as it can, rather than dropping out of the sentence
        // and leaving a short list with no explanation.
        parts += stringResource(Strings.History.summaryGame, name ?: stringResource(Strings.History.gameUnknown))
    }
    if (state.filter.period != HistoryPeriod.ALL) {
        parts += stringResource(Strings.History.summaryPeriod, stringResource(historyPeriodNameOf(state.filter.period)))
    }
    return parts
}

/** The two things that can be asked of the history: whose, and how far back. */
@Composable
private fun HistoryFilterChoices(
    controller: HistoryController,
    state: HistoryScreenState,
) {
    val games = (state.content as? HistoryContentState.Content)?.log?.games.orEmpty()
    FilterSectionTitle(stringResource(Strings.History.filterSectionGame))
    FilterChoiceRow {
        FilterChoice(
            label = stringResource(Strings.History.filterAllGames),
            selected = state.filter.gameId == null,
            onToggle = { controller.chooseGame(null) },
        )
        games.forEach { game -> GameChoice(game, state, controller) }
    }
    FilterSectionTitle(stringResource(Strings.History.filterSectionPeriod))
    FilterChoiceRow {
        HistoryPeriod.entries.forEach { period ->
            FilterChoice(
                label = stringResource(historyPeriodNameOf(period)),
                selected = state.filter.period == period,
                onToggle = { controller.choosePeriod(period) },
            )
        }
    }
}

/**
 * One game to narrow to.
 *
 * Choosing the game that is already chosen widens back to every game, so the
 * choice can be undone from where it was made rather than only from the button
 * above.
 */
@Composable
private fun GameChoice(
    game: HistoryGame,
    state: HistoryScreenState,
    controller: HistoryController,
) {
    val chosen = state.filter.gameId == game.id
    FilterChoice(
        label = game.name,
        selected = chosen,
        onToggle = { controller.chooseGame(if (chosen) null else game.id) },
    )
}

/**
 * The lines themselves.
 *
 * Lazy, because a history only ever grows: PLAN 18 (Faz 3 testleri) asks the application to
 * hold up at a thousand tasks, and each of those leaves several lines behind it.
 * Keyed by the event's identity so scrolling does not rebuild rows that have not
 * changed, and so a line arriving at the top does not shift the ones below it
 * into different composables.
 *
 * The list itself is a focus stop, which is the whole of how it is read without
 * a mouse: PLAN 17 asks for every main action to be reachable by keyboard, and
 * on a screen whose only action is reading, reaching the reading *is* the
 * action. Focused, it answers the arrow and page keys; the ring says where the
 * keyboard is.
 */
@Composable
private fun HistoryList(entries: List<HistoryEntry>) {
    val label = stringResource(Strings.History.listLabel)
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusOutline(ChipShape)
                .scrollsWithTheKeyboard(listState)
                .focusable()
                .semantics { contentDescription = label },
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(
            items = entries,
            key = { _, entry -> entry.id.toString() },
        ) { index, entry ->
            HistoryRow(entry)
            if (index < entries.lastIndex) HorizontalDivider()
        }
    }
}

/**
 * One thing that happened: when, what, and in which game.
 *
 * The whole row is one thing to a reader — the moment, the sentence and the game
 * read out together — so the three lines carry no semantics of their own and the
 * row carries all of it. Read out separately they would be three announcements
 * for one event, and the moment would arrive before there was anything for it to
 * be the moment of.
 */
@Composable
private fun HistoryRow(entry: HistoryEntry) {
    val moment =
        stringResource(
            Strings.History.moment,
            *momentArgumentsOf(localMomentOf(entry.occurredAt)).toTypedArray(),
        )
    val taskName = entry.taskName ?: stringResource(Strings.History.taskUnknown)
    val stageName =
        (entry.change as? HistoryChange.StageMoved)?.let { moved -> stringResource(stageNameOf(moved.stage)) }
    val sentence =
        stringResource(
            historySentenceOf(entry.change),
            *historyArgumentsOf(entry.change, taskName, stageName).toTypedArray(),
        )
    val game = stringResource(Strings.History.gameLabel, entry.gameName ?: stringResource(Strings.History.gameUnknown))
    val details = historyDetailsOf(entry.change).map { detail -> detailTextOf(detail) }
    val spoken = (listOf(moment, sentence, game) + details).joinToString(". ")
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = moment,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            text = sentence,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            text = game,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
        details.forEach { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * Moves a focused list with the arrow, page and home keys.
 *
 * Written out rather than left to the framework, because a focusable lazy list
 * is a focus stop and nothing more: it takes the keyboard and then answers none
 * of the keys somebody would try. PLAN 17 asks for every main action to be
 * reachable by keyboard, and on a screen whose only action is reading, moving
 * through the reading is that action.
 *
 * A page is most of the visible height rather than all of it, so the line that
 * was at the bottom is still there at the top to read on from. Nothing else is
 * intercepted, so Tab still leaves the list.
 */
@Composable
private fun Modifier.scrollsWithTheKeyboard(state: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val page = state.layoutInfo.viewportSize.height * PAGE_OVERLAP
        val movement: suspend () -> Unit =
            when (event.key) {
                Key.DirectionDown -> ({ state.animateScrollBy(LINE_STEP) })
                Key.DirectionUp -> ({ state.animateScrollBy(-LINE_STEP) })
                Key.PageDown -> ({ state.animateScrollBy(page) })
                Key.PageUp -> ({ state.animateScrollBy(-page) })
                Key.MoveHome -> ({ state.scrollToItem(0) })
                Key.MoveEnd -> ({ state.scrollToItem(lastItemOf(state)) })
                else -> null
            } ?: return@onPreviewKeyEvent false
        scope.launch { movement() }
        true
    }
}

/** The last line there is to go to, or the first when there are none. */
private fun lastItemOf(state: LazyListState): Int = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)

/** One detail line, named and filled in. */
@Composable
private fun detailTextOf(detail: HistoryDetail): String =
    stringResource(
        historyDetailLabelOf(detail),
        when (detail) {
            is HistoryDetail.Note -> detail.text
            is HistoryDetail.Card -> detail.reference
            is HistoryDetail.Stage -> stringResource(stageNameOf(detail.stage))
        },
    )

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** An empty screen that a filter made empty, with the way back out of it. */
@Composable
private fun FilteredEmpty(
    title: String,
    hint: String,
    onClearAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClearAll, modifier = Modifier.focusOutline(ChipShape)) {
            Text(text = stringResource(Strings.Search.clearAll), style = MaterialTheme.typography.labelMedium)
        }
    }
}
