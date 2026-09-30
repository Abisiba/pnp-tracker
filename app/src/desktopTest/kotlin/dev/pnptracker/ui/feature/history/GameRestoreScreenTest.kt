package dev.pnptracker.ui.feature.history

import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.activePoolTasks
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.HistoryStore
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `Geri al` on a deleted game's line in the history (PLAN 12.15).
 *
 * On the real history screen over the real stack, so "the same game comes back"
 * is read from the database the table and the pools read from.
 */
class GameRestoreScreenTest {
    private fun ComposeSceneHarness.settle(
        what: String,
        done: () -> Boolean,
    ) {
        repeat(200) {
            render()
            if (done()) {
                repeat(2) { render() }
                return
            }
            Thread.sleep(10)
        }
        fail("never happened: $what")
    }

    private fun gameWithTask(
        stack: RealStack,
        name: String,
    ): EntityId =
        runBlocking {
            insertGameCellAndTask(stack.database, gameName = name) { aTask(poolType = PoolType.THREE_D, name = "$name jeton") }
            stack.database
                .gameDao()
                .activeGames()
                .first { it.name == name }
                .id
        }

    private fun open(stack: RealStack): Pair<ComposeSceneHarness, HistoryController> {
        val controller = HistoryController(HistoryStore(stack.database.historyDao()), games = stack.gameSetup)
        val screen = ComposeSceneHarness(width = 1000, height = 900) { HistoryScreen(controller) }
        screen.settle("the history is read") { controller.state.content !is HistoryContentState.Loading }
        return screen to controller
    }

    private fun ComposeSceneHarness.restoreButtons(): List<String> =
        spokenNodes().flatMap { it.contentDescriptions() }.filter { it.endsWith("oyununu geri al") }

    private fun tableNames(stack: RealStack) =
        runBlocking {
            GameTableStore(stack.database.gameDao(), stack.database.gameCellDao(), stack.database.gameTableDao())
                .observeTable()
                .first()
                .map { it.gameName }
        }

    @Test
    fun `Geri al brings the deleted game back as the same game, cells and tasks and all`() {
        RealStack().use { stack ->
            val gameId = gameWithTask(stack, "Harmonies")
            gameWithTask(stack, "Catan")
            val cells = runBlocking { stack.database.gameCellDao().cellsOfGame(gameId) }
            val segments = runBlocking { cells.flatMap { stack.database.cellSegmentDao().segmentsOfCell(it.id) } }
            runBlocking { stack.gameSetup.deleteGame(gameId) }
            val (screen, _) = open(stack)
            screen.use {
                screen.settle("the deletion is offered back") { screen.restoreButtons() == listOf("Harmonies oyununu geri al") }

                assertTrue(screen.click("Harmonies oyununu geri al"))
                screen.settle("the game is back") { runBlocking { stack.database.gameDao().activeGameById(gameId) } != null }
                screen.settle("the notice is shown") {
                    screen.writtenText().any { it.startsWith("“Harmonies” geri geldi") }
                }

                assertEquals(listOf("Catan", "Harmonies"), tableNames(stack).sorted())
                assertEquals(cells, runBlocking { stack.database.gameCellDao().cellsOfGame(gameId) })
                assertEquals(segments, runBlocking { cells.flatMap { stack.database.cellSegmentDao().segmentsOfCell(it.id) } })
                val pool = runBlocking { activePoolTasks(PoolStore(stack.database.poolDao()).observePool(PoolType.THREE_D).first()) }
                assertEquals(listOf("Catan jeton", "Harmonies jeton"), pool.map { it.name }.sorted())
                assertEquals(
                    listOf(HistoryEventKind.GAME_DELETED, HistoryEventKind.GAME_RESTORED),
                    runBlocking {
                        stack.database
                            .historyDao()
                            .eventsOfGame(gameId)
                            .map { it.kind }
                    },
                )
                // Its own line is in the history, and nothing is offered back any more.
                screen.settle("nothing is offered back any more") { screen.restoreButtons().isEmpty() }
            }
        }
    }

    @Test
    fun `only the newest deletion of a game is offered back`() {
        RealStack().use { stack ->
            val gameId = gameWithTask(stack, "Harmonies")
            runBlocking {
                stack.gameSetup.deleteGame(gameId)
                stack.gameSetup.restoreGame(gameId)
                stack.gameSetup.deleteGame(gameId)
            }
            val (screen, _) = open(stack)
            screen.use {
                screen.settle("the history is shown") { screen.restoreButtons().isNotEmpty() }
                assertEquals(listOf("Harmonies oyununu geri al"), screen.restoreButtons(), "an older deletion is offered back too")
            }
        }
    }

    @Test
    fun `a game restored beside a namesake keeps its name, and the user is told`() {
        RealStack().use { stack ->
            val gameId = gameWithTask(stack, "Catan")
            runBlocking {
                stack.gameSetup.deleteGame(gameId)
                stack.gameSetup.createGame("Catan")
            }
            val (screen, _) = open(stack)
            screen.use {
                screen.settle("the deletion is offered back") { screen.restoreButtons().isNotEmpty() }
                assertTrue(screen.click("Catan oyununu geri al"))
                screen.settle("the notice is shown") { screen.writtenText().any { it.contains("bu adda başka bir oyun da var") } }

                assertEquals(listOf("Catan", "Catan"), tableNames(stack))
                assertEquals("Catan", runBlocking { stack.database.gameDao().activeGameById(gameId) }?.name)
            }
        }
    }
}
