package dev.pnptracker.ui.feature.games

import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.domain.games.CellSummary
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Storage a test can push new lists through, so the controller can be watched. */
private class FakeSetup : GameSetup {
    val games = MutableStateFlow<List<GameSummary>>(emptyList())
    val cellsByGame = mutableMapOf<EntityId, MutableStateFlow<List<CellSummary>>>()
    var failWith: GameSetupFailure? = null
    val createdGames = mutableListOf<String>()
    val openedCells = mutableListOf<Pair<EntityId, CellColumnType>>()
    val completionCalls = mutableListOf<Pair<EntityId, Boolean>>()

    fun cellsFlow(gameId: EntityId) = cellsByGame.getOrPut(gameId) { MutableStateFlow(emptyList()) }

    override fun observeGames(): Flow<List<GameSummary>> = games

    override fun observeCells(gameId: EntityId): Flow<List<CellSummary>> = cellsFlow(gameId)

    override suspend fun createGame(name: String): EntityId {
        failWith?.let { throw GameSetupException(it) }
        createdGames += name
        val id = IdGenerator.Random.newId()
        games.value = games.value + GameSummary(id, name.trim())
        return id
    }

    override suspend fun openCell(
        gameId: EntityId,
        columnType: CellColumnType,
    ): EntityId {
        failWith?.let { throw GameSetupException(it) }
        val flow = cellsFlow(gameId)
        flow.value.firstOrNull { it.columnType == columnType }?.let { return it.id }
        openedCells += gameId to columnType
        val id = IdGenerator.Random.newId()
        flow.value = flow.value + CellSummary(id, gameId, columnType)
        return id
    }

    override suspend fun setGameCompleted(
        gameId: EntityId,
        isCompleted: Boolean,
    ) {
        failWith?.let { throw GameSetupException(it) }
        completionCalls += gameId to isCompleted
        games.value = games.value.map { if (it.id == gameId) it.copy(isManuallyCompleted = isCompleted) else it }
    }
}

class GamesControllerTest {
    private fun withGames(
        setup: FakeSetup,
        body: suspend CoroutineScope.(GamesController) -> Unit,
    ) = runBlocking {
        val controller = GamesController(setup)
        val job = launch { controller.observeGames() }
        yield()
        try {
            body(controller)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `it starts out loading`() {
        val controller = GamesController(FakeSetup())

        assertIs<GamesListState.Loading>(controller.state.list)
    }

    @Test
    fun `no games at all is its own state`() {
        withGames(FakeSetup()) { controller ->
            assertIs<GamesListState.Empty>(controller.state.list)
        }
    }

    @Test
    fun `games arrive in the order storage gave them`() {
        val setup = FakeSetup()
        setup.games.value =
            listOf(
                GameSummary(IdGenerator.Random.newId(), "Harmonies"),
                GameSummary(IdGenerator.Random.newId(), "Root"),
            )
        withGames(setup) { controller ->
            val list = assertIs<GamesListState.Content>(controller.state.list)
            assertEquals(listOf("Harmonies", "Root"), list.games.map { it.name })
        }
    }

    @Test
    fun `a blank game name cannot be saved and writes nothing`() {
        val setup = FakeSetup()
        withGames(setup) { controller ->
            controller.startGameComposer()
            controller.editGameName("   ")

            assertTrue(!assertNotNull(controller.state.gameComposer).canSave)
            controller.saveGame()

            assertEquals(emptyList(), setup.createdGames)
            assertNotNull(controller.state.gameComposer, "the form stays open so the name can be fixed")
        }
    }

    @Test
    fun `changing one's mind about a game writes nothing`() {
        val setup = FakeSetup()
        withGames(setup) { controller ->
            controller.startGameComposer()
            controller.editGameName("Harmonies")
            controller.cancelGameComposer()

            assertNull(controller.state.gameComposer)
            assertEquals(emptyList(), setup.createdGames)
        }
    }

    @Test
    fun `saving a game closes the form and shows it in the list`() {
        val setup = FakeSetup()
        withGames(setup) { controller ->
            controller.startGameComposer()
            controller.editGameName("Harmonies")
            controller.saveGame()
            yield()

            assertEquals(listOf("Harmonies"), setup.createdGames)
            assertNull(controller.state.gameComposer)
            assertEquals(listOf("Harmonies"), assertIs<GamesListState.Content>(controller.state.list).games.map { it.name })
        }
    }

    @Test
    fun `a game that could not be saved keeps the form open and says so`() {
        val setup = FakeSetup()
        setup.failWith = GameSetupFailure.COULD_NOT_SAVE
        withGames(setup) { controller ->
            controller.startGameComposer()
            controller.editGameName("Harmonies")
            controller.saveGame()

            assertEquals("Harmonies", assertNotNull(controller.state.gameComposer).name)
            assertEquals(GameSetupFailure.COULD_NOT_SAVE, controller.state.failure)
            assertIs<GamesListState.Empty>(controller.state.list)
        }
    }

    @Test
    fun `an unexpected failure is not turned into a save problem`() {
        val setup =
            object : GameSetup by FakeSetup() {
                override suspend fun createGame(name: String): EntityId =
                    throw IllegalStateException("an invariant nobody expected to break")
            }
        val controller = GamesController(setup)

        val thrown =
            runBlocking {
                controller.startGameComposer()
                controller.editGameName("Harmonies")
                runCatching { controller.saveGame() }.exceptionOrNull()
            }

        assertIs<IllegalStateException>(thrown, "a defect must travel out as it is")
    }

    @Test
    fun `opening a game with no cells shows the empty detail`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            val cells = launch { controller.observeCells(gameId) }
            yield()

            val detail = assertIs<GameDetailState.Empty>(controller.state.detail)
            assertEquals("Harmonies", detail.game.name)
            cells.cancelAndJoin()
        }
    }

    @Test
    fun `an open game shows only its own cells`() {
        val first = IdGenerator.Random.newId()
        val second = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(first, "Harmonies"), GameSummary(second, "Root"))
        setup.cellsFlow(first).value = listOf(CellSummary(IdGenerator.Random.newId(), first, CellColumnType.THREE_D))
        setup.cellsFlow(second).value = listOf(CellSummary(IdGenerator.Random.newId(), second, CellColumnType.CARD))
        withGames(setup) { controller ->
            controller.openGame(first)
            val cells = launch { controller.observeCells(first) }
            yield()

            val detail = assertIs<GameDetailState.Content>(controller.state.detail)
            assertEquals(listOf(CellColumnType.THREE_D), detail.cells.map { it.columnType })
            cells.cancelAndJoin()
        }
    }

    @Test
    fun `a game that disappears while open reports itself unavailable`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            val cells = launch { controller.observeCells(gameId) }
            yield()
            setup.games.value = emptyList()
            yield()

            assertIs<GameDetailState.Unavailable>(controller.state.detail)
            cells.cancelAndJoin()
        }
    }

    @Test
    fun `opening a column sends it to the open game`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            val cells = launch { controller.observeCells(gameId) }
            yield()

            controller.openCell(CellColumnType.CARD)
            yield()

            assertEquals(listOf(gameId to CellColumnType.CARD), setup.openedCells)
            val detail = assertIs<GameDetailState.Content>(controller.state.detail)
            assertEquals(listOf(CellColumnType.CARD), detail.cells.map { it.columnType })
            cells.cancelAndJoin()
        }
    }

    @Test
    fun `opening the same column twice opens one cell`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            val cells = launch { controller.observeCells(gameId) }
            yield()

            controller.openCell(CellColumnType.CARD)
            yield()
            controller.openCell(CellColumnType.CARD)
            yield()

            assertEquals(1, setup.openedCells.size, "the second call opened a second cell")
            cells.cancelAndJoin()
        }
    }

    @Test
    fun `a column cannot be opened with no game open`() {
        val setup = FakeSetup()
        withGames(setup) { controller ->
            controller.openCell(CellColumnType.THREE_D)

            assertEquals(emptyList(), setup.openedCells)
            assertNull(controller.state.failure)
        }
    }

    @Test
    fun `a game missing under a cell is reported as such`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        setup.failWith = GameSetupFailure.GAME_NOT_AVAILABLE
        withGames(setup) { controller ->
            controller.openGame(gameId)

            controller.openCell(CellColumnType.THREE_D)

            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, controller.state.failure)
        }
    }

    @Test
    fun `finishing a game and reopening it both reach storage`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.setCompleted(gameId, true)
            yield()
            assertTrue(assertIs<GamesListState.Content>(controller.state.list).games.single().isManuallyCompleted)

            controller.setCompleted(gameId, false)
            yield()
            assertTrue(!assertIs<GamesListState.Content>(controller.state.list).games.single().isManuallyCompleted)

            assertEquals(listOf(gameId to true, gameId to false), setup.completionCalls)
        }
    }

    @Test
    fun `finishing one game asks about that game only`() {
        val first = IdGenerator.Random.newId()
        val second = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(first, "Harmonies"), GameSummary(second, "Root"))
        withGames(setup) { controller ->
            controller.setCompleted(first, true)
            yield()

            assertEquals(listOf(first to true), setup.completionCalls)
            assertEquals(
                listOf(true, false),
                assertIs<GamesListState.Content>(controller.state.list).games.map { it.isManuallyCompleted },
            )
        }
    }

    @Test
    fun `a completion that could not be saved is reported without losing the list`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        setup.failWith = GameSetupFailure.COULD_NOT_SAVE
        withGames(setup) { controller ->
            controller.setCompleted(gameId, true)

            assertEquals(GameSetupFailure.COULD_NOT_SAVE, controller.state.failure)
            val list = assertIs<GamesListState.Content>(controller.state.list)
            assertEquals(listOf("Harmonies"), list.games.map { it.name })
            assertTrue(!list.games.single().isManuallyCompleted)
        }
    }
}
