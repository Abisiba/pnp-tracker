package dev.pnptracker.ui.feature.history

import dev.pnptracker.data.repository.HistorySource
import dev.pnptracker.domain.history.HistoryChange
import dev.pnptracker.domain.history.HistoryEntry
import dev.pnptracker.domain.history.HistoryLog
import dev.pnptracker.domain.history.HistoryPeriod
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A history a test can push new readings through. */
private class FakeHistory : HistorySource {
    val log = MutableStateFlow(HistoryLog.EMPTY)

    override fun observeHistory(): Flow<HistoryLog> = log
}

/** A history that cannot be read at all. */
private class BrokenHistory : HistorySource {
    override fun observeHistory(): Flow<HistoryLog> = flow { throw IllegalStateException("the database is gone") }
}

private class StoppedClock(
    private val fixed: Instant,
) : Clock {
    override fun now(): Instant = fixed
}

/**
 * What the history section remembers while the database goes on talking to it.
 *
 * The rule underneath all of it is the pools' rule: a fresh reading is news
 * about what happened and says nothing about what the user asked to see. A line
 * arriving must not widen the filters, and choosing a filter must not go back to
 * the database.
 */
class HistoryControllerTest {
    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000L)
    private val harmonies = IdGenerator.Random.newId()
    private val root = IdGenerator.Random.newId()

    private fun entry(
        occurredAt: Instant = now,
        gameId: EntityId = harmonies,
        gameName: String = "Harmonies",
        change: HistoryChange = HistoryChange.TaskCompleted,
    ) = HistoryEntry(
        id = IdGenerator.Random.newId(),
        occurredAt = occurredAt,
        change = change,
        gameId = gameId,
        gameName = gameName,
        taskId = IdGenerator.Random.newId(),
        taskName = "Gri token",
    )

    private fun controller(history: HistorySource) = HistoryController(history, StoppedClock(now))

    /** Runs the controller's reading for as long as [block] needs it. */
    private fun watching(
        history: HistorySource,
        block: suspend (HistoryController) -> Unit,
    ) = runBlocking {
        val controller = controller(history)
        val job: Job = CoroutineScope(Dispatchers.Unconfined).launch { controller.observeHistory() }
        try {
            yield()
            block(controller)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `the section starts out saying it is still reading`() {
        assertIs<HistoryContentState.Loading>(controller(FakeHistory()).state.content)
    }

    @Test
    fun `a reading arrives and is shown newest first, as it came`() {
        val history = FakeHistory()
        val older = entry(now - 2.days)
        val newer = entry(now)
        history.log.value = HistoryLog(listOf(newer, older))

        watching(history) { controller ->
            assertEquals(listOf(newer, older), controller.state.shown)
        }
    }

    @Test
    fun `an empty history is not the same as a filtered one`() {
        val history = FakeHistory()

        watching(history) { controller ->
            val content = assertIs<HistoryContentState.Content>(controller.state.content)
            assertTrue(content.log.isEmpty)
            assertFalse(content.hasHiddenEntries, "an empty history is nothing to loosen a filter for")
        }
    }

    @Test
    fun `filters that admit nothing say so rather than looking empty`() {
        val history = FakeHistory()
        history.log.value = HistoryLog(listOf(entry(now - 40.days)))

        watching(history) { controller ->
            controller.choosePeriod(HistoryPeriod.LAST_WEEK)

            val content = assertIs<HistoryContentState.Content>(controller.state.content)
            assertTrue(content.shown.isEmpty())
            assertTrue(content.hasHiddenEntries, "the history is not empty; the filter is narrow")
        }
    }

    @Test
    fun `choosing a game shows only that game`() {
        val history = FakeHistory()
        val mine = entry(gameId = harmonies)
        val theirs = entry(gameId = root, gameName = "Root")
        history.log.value = HistoryLog(listOf(mine, theirs))

        watching(history) { controller ->
            controller.chooseGame(harmonies)

            assertEquals(listOf(mine), controller.state.shown)
            assertEquals(1, controller.state.chosenFilterCount)
        }
    }

    @Test
    fun `a fresh reading keeps the filters the user chose`() {
        val history = FakeHistory()
        history.log.value = HistoryLog(listOf(entry(gameId = harmonies)))

        watching(history) { controller ->
            controller.chooseGame(harmonies)
            val fromRoot = entry(gameId = root, gameName = "Root")
            history.log.value = HistoryLog(listOf(fromRoot, entry(gameId = harmonies)))
            yield()

            assertEquals(harmonies, controller.state.filter.gameId, "a new line widened the filter")
            assertTrue(controller.state.shown.none { it.gameId == root })
        }
    }

    @Test
    fun `clearing the filters goes back to the whole history`() {
        val history = FakeHistory()
        history.log.value = HistoryLog(listOf(entry(gameId = harmonies), entry(gameId = root, gameName = "Root")))

        watching(history) { controller ->
            controller.chooseGame(harmonies)
            controller.choosePeriod(HistoryPeriod.LAST_WEEK)
            controller.clearFilters()

            assertEquals(2, controller.state.shown.size)
            assertEquals(0, controller.state.chosenFilterCount)
            assertFalse(controller.state.filter.isNarrowed)
        }
    }

    @Test
    fun `choosing the filter that is already in force changes nothing`() {
        val history = FakeHistory()
        history.log.value = HistoryLog(listOf(entry()))

        watching(history) { controller ->
            controller.chooseGame(harmonies)
            val before = controller.state

            controller.chooseGame(harmonies)

            assertEquals(before, controller.state)
        }
    }

    @Test
    fun `a read that fails says so instead of loading for ever`() {
        watching(BrokenHistory()) { controller ->
            assertIs<HistoryContentState.Failed>(controller.state.content)
        }
    }

    @Test
    fun `closing the filter panel hands the keyboard back`() {
        val controller = controller(FakeHistory())
        controller.openFilters()
        assertEquals(HistoryFilterSurface.OPEN, controller.state.filterSurface)
        val before = controller.state.focusRecall

        controller.closeFilters()

        assertEquals(HistoryFilterSurface.CLOSED, controller.state.filterSurface)
        assertEquals(before + 1, controller.state.focusRecall, "nothing asked for the keyboard back")
    }
}
