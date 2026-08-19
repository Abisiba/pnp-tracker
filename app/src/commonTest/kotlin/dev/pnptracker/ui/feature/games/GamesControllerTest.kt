package dev.pnptracker.ui.feature.games

import dev.pnptracker.data.repository.GameSetup
import dev.pnptracker.domain.games.GameSetupException
import dev.pnptracker.domain.games.GameSetupFailure
import dev.pnptracker.domain.games.GameSummary
import dev.pnptracker.domain.games.ItemSummary
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
    val itemsByGame = mutableMapOf<EntityId, MutableStateFlow<List<ItemSummary>>>()
    var failWith: GameSetupFailure? = null
    val createdGames = mutableListOf<String>()
    val createdItems = mutableListOf<Pair<EntityId, String>>()
    val completionCalls = mutableListOf<Pair<EntityId, Boolean>>()

    fun itemsFlow(gameId: EntityId) = itemsByGame.getOrPut(gameId) { MutableStateFlow(emptyList()) }

    override fun observeGames(): Flow<List<GameSummary>> = games

    override fun observeItems(gameId: EntityId): Flow<List<ItemSummary>> = itemsFlow(gameId)

    override suspend fun createGame(name: String): EntityId {
        failWith?.let { throw GameSetupException(it) }
        createdGames += name
        val id = IdGenerator.Random.newId()
        games.value = games.value + GameSummary(id, name.trim())
        return id
    }

    override suspend fun createItem(
        gameId: EntityId,
        name: String,
    ): EntityId {
        failWith?.let { throw GameSetupException(it) }
        createdItems += gameId to name
        val id = IdGenerator.Random.newId()
        val flow = itemsFlow(gameId)
        flow.value = flow.value + ItemSummary(id, gameId, name.trim())
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
    fun `opening a game with no items shows the empty detail`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            val items = launch { controller.observeItems(gameId) }
            yield()

            val detail = assertIs<GameDetailState.Empty>(controller.state.detail)
            assertEquals("Harmonies", detail.game.name)
            items.cancelAndJoin()
        }
    }

    @Test
    fun `an open game shows only its own items`() {
        val first = IdGenerator.Random.newId()
        val second = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(first, "Harmonies"), GameSummary(second, "Root"))
        setup.itemsFlow(first).value = listOf(ItemSummary(IdGenerator.Random.newId(), first, "Token"))
        setup.itemsFlow(second).value = listOf(ItemSummary(IdGenerator.Random.newId(), second, "Meeple"))
        withGames(setup) { controller ->
            controller.openGame(first)
            val items = launch { controller.observeItems(first) }
            yield()

            val detail = assertIs<GameDetailState.Content>(controller.state.detail)
            assertEquals(listOf("Token"), detail.items.map { it.name })
            items.cancelAndJoin()
        }
    }

    @Test
    fun `a game that disappears while open reports itself unavailable`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            val items = launch { controller.observeItems(gameId) }
            yield()
            setup.games.value = emptyList()
            yield()

            assertIs<GameDetailState.Unavailable>(controller.state.detail)
            items.cancelAndJoin()
        }
    }

    @Test
    fun `a blank item name cannot be saved and writes nothing`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            controller.startItemComposer()
            controller.editItemName("  ")
            controller.saveItem()

            assertEquals(emptyList(), setup.createdItems)
            assertNotNull(controller.state.itemComposer)
        }
    }

    @Test
    fun `changing one's mind about an item writes nothing`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            controller.startItemComposer()
            controller.editItemName("Token")
            controller.cancelItemComposer()

            assertNull(controller.state.itemComposer)
            assertEquals(emptyList(), setup.createdItems)
        }
    }

    @Test
    fun `saving an item sends it to the open game`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            controller.startItemComposer()
            controller.editItemName("Token")
            controller.saveItem()
            yield()

            assertEquals(listOf(gameId to "Token"), setup.createdItems)
            assertNull(controller.state.itemComposer)
        }
    }

    @Test
    fun `an item cannot be saved with no game open`() {
        val setup = FakeSetup()
        withGames(setup) { controller ->
            controller.startItemComposer()
            controller.editItemName("Token")
            controller.saveItem()

            assertEquals(emptyList(), setup.createdItems)
        }
    }

    @Test
    fun `a game missing under an item is reported as such`() {
        val gameId = IdGenerator.Random.newId()
        val setup = FakeSetup()
        setup.games.value = listOf(GameSummary(gameId, "Harmonies"))
        withGames(setup) { controller ->
            controller.openGame(gameId)
            controller.startItemComposer()
            controller.editItemName("Token")
            setup.failWith = GameSetupFailure.GAME_NOT_AVAILABLE
            controller.saveItem()

            assertEquals(GameSetupFailure.GAME_NOT_AVAILABLE, controller.state.failure)
            assertEquals("Token", assertNotNull(controller.state.itemComposer).name)
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
