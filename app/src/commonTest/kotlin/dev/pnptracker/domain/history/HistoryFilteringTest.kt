package dev.pnptracker.domain.history

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * What the history screen is allowed to leave out, and where the edges of that
 * are.
 *
 * The date range is the part with a real boundary, and a boundary nobody pinned
 * is a boundary that moves: `Son 7 gün` either takes in the line recorded
 * exactly seven days ago or it does not, and the user has no way to tell which
 * was meant. Here it does, in both directions, to the millisecond the database
 * stores.
 */
class HistoryFilteringTest {
    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000L)
    private val harmonies = IdGenerator.Random.newId()
    private val root = IdGenerator.Random.newId()

    private fun entry(
        occurredAt: Instant,
        gameId: EntityId? = harmonies,
        gameName: String? = "Harmonies",
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

    // ------------------------------------------------------------ the game

    @Test
    fun `asking for one game leaves the other games out`() {
        val mine = entry(now, gameId = harmonies, gameName = "Harmonies")
        val theirs = entry(now, gameId = root, gameName = "Root")

        val shown = filterHistory(listOf(mine, theirs), HistoryFilter(gameId = harmonies), now)

        assertContentEquals(listOf(mine), shown)
    }

    @Test
    fun `asking for no game in particular shows every game`() {
        val lines = listOf(entry(now, gameId = harmonies), entry(now, gameId = root))

        assertContentEquals(lines, filterHistory(lines, HistoryFilter.NONE, now))
    }

    @Test
    fun `a line whose game cannot be named is left out of a game's history`() {
        // It cannot answer the question either way, and putting it under
        // Harmonies would be filing it somewhere it may not belong.
        val placeless = entry(now, gameId = null, gameName = null)

        assertTrue(filterHistory(listOf(placeless), HistoryFilter(gameId = harmonies), now).isEmpty())
        assertContentEquals(listOf(placeless), filterHistory(listOf(placeless), HistoryFilter.NONE, now))
    }

    // ------------------------------------------------------------ the dates

    @Test
    fun `the last seven days takes in a line recorded exactly seven days ago`() {
        val onTheEdge = entry(now - 7.days)

        assertContentEquals(
            listOf(onTheEdge),
            filterHistory(listOf(onTheEdge), HistoryFilter(period = HistoryPeriod.LAST_WEEK), now),
        )
    }

    @Test
    fun `one millisecond older than seven days is outside the last seven days`() {
        val justPast = entry(now - 7.days - 1.milliseconds)

        assertTrue(filterHistory(listOf(justPast), HistoryFilter(period = HistoryPeriod.LAST_WEEK), now).isEmpty())
    }

    @Test
    fun `the last thirty days reaches further back than the last seven`() {
        val threeWeeksAgo = entry(now - 21.days)

        assertTrue(filterHistory(listOf(threeWeeksAgo), HistoryFilter(period = HistoryPeriod.LAST_WEEK), now).isEmpty())
        assertContentEquals(
            listOf(threeWeeksAgo),
            filterHistory(listOf(threeWeeksAgo), HistoryFilter(period = HistoryPeriod.LAST_MONTH), now),
        )
    }

    @Test
    fun `all time has no edge at all`() {
        val ancient = entry(Instant.fromEpochMilliseconds(0))

        assertContentEquals(listOf(ancient), filterHistory(listOf(ancient), HistoryFilter.NONE, now))
        assertEquals(null, HistoryPeriod.ALL.startingFrom(now))
    }

    @Test
    fun `a line recorded this very moment is inside every period`() {
        val justNow = entry(now)

        HistoryPeriod.entries.forEach { period ->
            assertContentEquals(
                listOf(justNow),
                filterHistory(listOf(justNow), HistoryFilter(period = period), now),
                "$period left out something that just happened",
            )
        }
    }

    // ------------------------------------------------------- both at once

    @Test
    fun `the two narrowings narrow together`() {
        val wanted = entry(now - 1.days, gameId = harmonies)
        val wrongGame = entry(now - 1.days, gameId = root, gameName = "Root")
        val tooOld = entry(now - 40.days, gameId = harmonies)

        val shown =
            filterHistory(
                listOf(wanted, wrongGame, tooOld),
                HistoryFilter(gameId = harmonies, period = HistoryPeriod.LAST_MONTH),
                now,
            )

        assertContentEquals(listOf(wanted), shown)
    }

    @Test
    fun `filtering removes lines and never rearranges the ones that stay`() {
        // The reading is already a total order; a filter that reordered it would
        // move rows under a reader who only asked to see fewer of them.
        val lines = (0..5).map { entry(now - it.days) }
        val kept = filterHistory(lines, HistoryFilter(period = HistoryPeriod.LAST_WEEK), now)

        assertContentEquals(lines, kept)
    }

    // --------------------------------------------------------- what is asked

    @Test
    fun `nothing chosen is not a narrowing`() {
        assertFalse(HistoryFilter.NONE.isNarrowed)
        assertEquals(0, HistoryFilter.NONE.chosenCount)
    }

    @Test
    fun `each choice counts once, for the number on the button`() {
        assertEquals(1, HistoryFilter(gameId = harmonies).chosenCount)
        assertEquals(1, HistoryFilter(period = HistoryPeriod.LAST_WEEK).chosenCount)
        assertEquals(2, HistoryFilter(gameId = harmonies, period = HistoryPeriod.LAST_WEEK).chosenCount)
        assertTrue(HistoryFilter(period = HistoryPeriod.LAST_MONTH).isNarrowed)
    }

    // --------------------------------------------------- the games on offer

    @Test
    fun `the games offered are the ones the history names, once each`() {
        val log =
            HistoryLog(
                listOf(
                    entry(now, gameId = root, gameName = "Root"),
                    entry(now, gameId = harmonies, gameName = "Harmonies"),
                    entry(now, gameId = harmonies, gameName = "Harmonies"),
                    entry(now, gameId = null, gameName = null),
                ),
            )

        assertEquals(
            listOf(HistoryGame(harmonies, "Harmonies"), HistoryGame(root, "Root")),
            log.games,
        )
    }

    @Test
    fun `the games are ordered the Turkish way rather than the root locale's`() {
        val ilk = IdGenerator.Random.newId()
        val isik = IdGenerator.Random.newId()
        val log =
            HistoryLog(
                listOf(
                    entry(now, gameId = isik, gameName = "Işık"),
                    entry(now, gameId = ilk, gameName = "İlk Oyun"),
                ),
            )

        // `İ` folds to `i` and `I` to `ı`, so `İlk` comes before `Işık`. Folded
        // by the root locale both would start with `i` and the order would be
        // decided by the second letter instead.
        assertEquals(listOf("İlk Oyun", "Işık"), log.games.map { it.name })
    }

    @Test
    fun `a history with nothing in it offers no games and says it is empty`() {
        assertTrue(HistoryLog.EMPTY.isEmpty)
        assertTrue(HistoryLog.EMPTY.games.isEmpty())
    }
}
