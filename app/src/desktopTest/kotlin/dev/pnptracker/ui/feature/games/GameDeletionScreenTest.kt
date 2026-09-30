package dev.pnptracker.ui.feature.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.activePoolTasks
import dev.pnptracker.data.database.insertGameCellAndTask
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.domain.games.GameArrangement
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.platform.settings.DesktopGameOrderStore
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.reads
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A game's menu on its handle, and deleting the game from it (PLAN 12.18, 5.2).
 *
 * On the real screen through the real stack, with the order in a real file, so
 * "a click moves nothing" is checked where it would show: in the order, in the
 * file and in the database.
 */
class GameDeletionScreenTest {
    private lateinit var folder: Path
    private lateinit var orderFile: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-game-deletion-test")
        orderFile = folder.resolve("game-order.json")
    }

    @AfterTest
    fun deleteFolder() {
        val absolute = folder.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

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

    private fun rowsOf(table: GameTableController) = (table.state.rows as? GameTableRowsState.Content)?.rows.orEmpty()

    private fun shown(table: GameTableController) = rowsOf(table).map { "${table.state.numbers[it.gameId]} ${it.gameName}" }

    private fun idOf(
        table: GameTableController,
        name: String,
    ): EntityId = rowsOf(table).first { it.gameName == name }.gameId

    /** Harmonies, Catan and Sky Team, each with one 3D task, numbered by name. */
    private fun open(stack: RealStack): Pair<ComposeSceneHarness, GameTableController> {
        runBlocking {
            listOf("Harmonies", "Catan", "Sky Team").forEach { name ->
                insertGameCellAndTask(stack.database, gameName = name) { aTask(poolType = PoolType.THREE_D, name = "$name jeton") }
            }
        }
        val table = stack.tableControllerWith(order = DesktopGameOrderStore(orderFile))
        val screen = ComposeSceneHarness(width = 1500, height = 900) { GameTableScreen(table) }
        screen.settle("the games are shown") { rowsOf(table).size == 3 }
        return screen to table
    }

    private fun ComposeSceneHarness.handleOf(name: String): Rect =
        nodes().firstOrNull { node -> node.contentDescriptions().any { it.contains("sıradaki $name oyunu") } }?.boundsInRoot
            ?: fail("$name has no handle")

    private fun ComposeSceneHarness.focusHandleOf(name: String) {
        val handle =
            nodes().firstOrNull { node -> node.contentDescriptions().any { it.contains("sıradaki $name oyunu") } }
                ?: fail("$name has no handle the keyboard can reach")
        handle.reads(SemanticsActions.RequestFocus)?.action?.invoke()
        render()
    }

    private fun ComposeSceneHarness.menuIsShown(): Boolean = boundsOf(DELETE_GAME) != null

    private fun storedGame(
        stack: RealStack,
        gameId: EntityId,
    ) = runBlocking { stack.database.gameDao().gameByIdIncludingDeleted(gameId) }

    private fun historyKinds(
        stack: RealStack,
        gameId: EntityId,
    ) = runBlocking {
        stack.database
            .historyDao()
            .eventsOfGame(gameId)
            .map { it.kind }
    }

    private fun poolTasks(stack: RealStack) =
        runBlocking { activePoolTasks(PoolStore(stack.database.poolDao()).observePool(PoolType.THREE_D).first()).map { it.name } }

    // ------------------------------------------------------ click or drag

    @Test
    fun `a short click on the handle opens the menu, and a small wobble moves nothing`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                val before = shown(table)
                val handle = screen.handleOf("Harmonies")
                // Pressed, shaken by three pixels, and let go: a click, not a carry.
                screen.pressAt(handle.center)
                screen.moveTo(handle.center, handle.center + Offset(0f, 3f), steps = 3)
                screen.releaseAt(handle.center + Offset(0f, 3f))
                screen.settle("the menu opens") { screen.menuIsShown() }

                assertEquals(idOf(table, "Harmonies"), table.state.gameMenuOf(idOf(table, "Harmonies"))?.gameId)
                assertEquals(before, shown(table), "a click moved a game")
                assertFalse(Files.exists(orderFile), "a click wrote an order")
            }
        }
    }

    @Test
    fun `a drag from the handle still moves the game live, and opens no menu`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                assertEquals(listOf("1 Catan", "2 Harmonies", "3 Sky Team"), shown(table))
                val from = screen.handleOf("Sky Team").center
                val onto = screen.handleOf("Catan").center
                screen.pressAt(from)
                screen.moveTo(from, Offset(from.x, onto.y), steps = 10)
                // Still held: the rows have already made way.
                assertEquals("1 Sky Team", shown(table).first(), "the order did not follow the drag")
                screen.releaseAt(Offset(from.x, onto.y))
                screen.settle("the order is written") { Files.exists(orderFile) }

                assertEquals(listOf("1 Sky Team", "2 Catan", "3 Harmonies"), shown(table))
                assertNull(table.state.rowWork, "a drag opened the menu")
                assertFalse(screen.menuIsShown())
            }
        }
    }

    @Test
    fun `Enter and Space on the focused handle open the menu, and the arrows still move`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                screen.focusHandleOf("Harmonies")
                screen.press(Key.Enter)
                screen.settle("Enter opens the menu") { screen.menuIsShown() }
                table.closeGameMenu()
                screen.settle("the menu closes") { !screen.menuIsShown() }

                screen.focusHandleOf("Harmonies")
                screen.press(Key.Spacebar)
                screen.settle("Space opens the menu") { screen.menuIsShown() }
                table.closeGameMenu()
                screen.settle("the menu closes") { !screen.menuIsShown() }

                screen.focusHandleOf("Harmonies")
                screen.press(Key.DirectionUp)
                screen.settle("Harmonies moved up") { shown(table).first() == "1 Harmonies" }
            }
        }
    }

    @Test
    fun `in the A–Z layout the number is no handle, but it still opens the menu`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                table.arrange(GameArrangement.ALPHABETICAL)
                screen.render()
                val number =
                    screen.nodes().firstOrNull { node -> node.contentDescriptions().any { it.contains("sıradaki Catan oyununun menüsü") } }
                        ?: fail("the number opens nothing in the A–Z layout")
                screen.mouseClick(number.boundsInRoot.center)
                screen.settle("the menu opens") { screen.menuIsShown() }
                assertEquals(listOf("1 Catan", "2 Harmonies", "3 Sky Team"), shown(table))
            }
        }
    }

    // ------------------------------------------------------ deleting

    @Test
    fun `Oyunu sil asks with the game's name, and Vazgeç changes nothing`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                val gameId = idOf(table, "Harmonies")
                val stored = storedGame(stack, gameId)
                screen.mouseClick(screen.handleOf("Harmonies").center)
                screen.settle("the menu opens") { screen.menuIsShown() }

                assertTrue(screen.click(DELETE_GAME))
                screen.settle("the question is asked") { screen.writtenText().contains("“Harmonies” silinsin mi?") }
                assertEquals(3, rowsOf(table).size, "asking deleted something")

                assertTrue(screen.click("Vazgeç"))
                screen.settle("the question closes") { table.state.rowWork == null }

                assertEquals(listOf("1 Catan", "2 Harmonies", "3 Sky Team"), shown(table))
                assertEquals(stored, storedGame(stack, gameId), "Vazgeç wrote to the game")
                assertEquals(emptyList(), historyKinds(stack, gameId), "Vazgeç wrote a history line")
                assertEquals(3, poolTasks(stack).size)
            }
        }
    }

    @Test
    fun `Escape and clicking away close the question the way Vazgeç does`() {
        // A popup can be given neither the keyboard nor a click outside it in
        // the test scene, so this reads
        // the question itself, as GameTableLayoutTest reads the completion one:
        // Escape and every other way of dismissing it end in the same call as
        // Vazgeç, which the tests above show writes nothing.
        val source = Path.of("src/commonMain/kotlin/dev/pnptracker/ui/feature/games/GameTableScreen.kt").let(Files::readString)
        val popover = source.substringAfter("private fun GameDeletionPopover(").substringBefore("\n}\n")
        assertTrue("onDismissRequest = controller::cancelGameDeletion" in popover, "clicking away does not give up")
        assertTrue("event.key == Key.Escape" in popover, "Escape does not close the question")
        assertTrue("controller.cancelGameDeletion()" in popover, "Escape does something other than Vazgeç")
    }

    @Test
    fun `confirming takes the game out of the table and the pools through its tombstone`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                val gameId = idOf(table, "Harmonies")
                screen.mouseClick(screen.handleOf("Harmonies").center)
                screen.settle("the menu opens") { screen.menuIsShown() }
                assertTrue(screen.click(DELETE_GAME))
                screen.settle("the question is asked") { table.state.confirmingDeletionOf(gameId) != null }

                assertTrue(screen.click("Evet, sil"))
                screen.settle("the row leaves") { rowsOf(table).none { it.gameId == gameId } }

                // Numbered again without it, with nothing left open behind it.
                assertEquals(listOf("1 Catan", "2 Sky Team"), shown(table))
                assertNull(table.state.rowWork)
                // Not erased: the row is still there, tombstoned, and its task too.
                val stored = assertNotNull(storedGame(stack, gameId), "the game was physically deleted")
                assertNotNull(stored.deletedAt)
                assertTrue(runBlocking { stack.database.taskDao().allTasksIncludingDeleted() }.any { it.name == "Harmonies jeton" })
                assertEquals(listOf("Catan jeton", "Sky Team jeton"), poolTasks(stack).sorted())
                assertEquals(listOf(HistoryEventKind.GAME_DELETED), historyKinds(stack, gameId))
            }
        }
    }

    private companion object {
        const val DELETE_GAME = "Oyunu sil"
    }
}
