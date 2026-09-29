package dev.pnptracker.ui.feature.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import dev.pnptracker.domain.games.GameArrangement
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.platform.settings.DesktopGameOrderStore
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Putting the games in order, on the real screen, through the real stack, into a
 * real file (PLAN 12.18).
 *
 * "It is still there when the application is opened again" means what it says
 * here: a second controller over the same database and the same file.
 */
class GameOrderScreenTest {
    private lateinit var folder: Path
    private lateinit var orderFile: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-game-order-screen-test")
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

    /** What the table shows, top to bottom, as `number name`. */
    private fun shown(table: GameTableController) = rowsOf(table).map { "${table.state.numbers[it.gameId]} ${it.gameName}" }

    private fun open(stack: RealStack): Pair<ComposeSceneHarness, GameTableController> {
        val table = stack.tableControllerWith(order = DesktopGameOrderStore(orderFile))
        val screen = ComposeSceneHarness(width = 1500, height = 900) { GameTableScreen(table) }
        screen.settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        return screen to table
    }

    private fun addGames(
        screen: ComposeSceneHarness,
        table: GameTableController,
        vararg names: String,
    ) {
        names.forEach { name ->
            runBlocking {
                table.startGameComposer()
                table.editGameName(name)
                table.saveGame()
            }
            screen.settle("$name is shown") { rowsOf(table).any { it.gameName == name } }
        }
    }

    private fun idOf(
        table: GameTableController,
        name: String,
    ): EntityId = rowsOf(table).first { it.gameName == name }.gameId

    /** Harmonies, Catan and Sky Team, arranged as 1 Harmonies, 2 Catan, 3 Sky Team. */
    private fun theExample(
        screen: ComposeSceneHarness,
        table: GameTableController,
    ) {
        addGames(screen, table, "Harmonies", "Catan", "Sky Team")
        runBlocking { table.moveGame(idOf(table, "Harmonies"), idOf(table, "Catan")) }
        screen.settle("Harmonies is first") { shown(table).firstOrNull() == "1 Harmonies" }
    }

    @Test
    fun `the example from the request, in both layouts and back again`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                theExample(screen, table)
                assertEquals(listOf("1 Harmonies", "2 Catan", "3 Sky Team"), shown(table))

                table.arrange(GameArrangement.ALPHABETICAL)
                screen.render()
                // The rows move; the numbers do not.
                assertEquals(listOf("2 Catan", "1 Harmonies", "3 Sky Team"), shown(table))

                table.arrange(GameArrangement.MINE)
                screen.render()
                assertEquals(listOf("1 Harmonies", "2 Catan", "3 Sky Team"), shown(table))
            }
        }
    }

    @Test
    fun `the order is still there when the application is opened again`() {
        RealStack().use { stack ->
            val (first, firstTable) = open(stack)
            first.use { theExample(first, firstTable) }

            val (again, table) = open(stack)
            again.use {
                again.settle("the order is read") { shown(table).firstOrNull() == "1 Harmonies" }
                assertEquals(listOf("1 Harmonies", "2 Catan", "3 Sky Team"), shown(table))
            }
        }
    }

    @Test
    fun `moving games changes no name, no cell and nothing in the database`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                addGames(screen, table, "Harmonies", "Catan", "Sky Team")
                val before = rowsOf(table).associateBy { it.gameId }
                val stored = runBlocking { stack.database.gameDao().allGamesIncludingDeleted() }

                runBlocking {
                    table.moveGame(idOf(table, "Sky Team"), idOf(table, "Catan"))
                    table.moveGameDown(idOf(table, "Catan"))
                }
                screen.render()

                // The same rows with the same names and the same cells, only elsewhere.
                assertEquals(before, rowsOf(table).associateBy { it.gameId })
                assertEquals(stored, runBlocking { stack.database.gameDao().allGamesIncludingDeleted() }, "a move wrote to the games")
            }
        }
    }

    @Test
    fun `the arrow keys on a game's number move it one place`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                theExample(screen, table)
                val handle =
                    screen.nodes().firstOrNull { node ->
                        node.contentDescriptions().any { it.startsWith("3. sıradaki Sky Team") }
                    } ?: fail("Sky Team's number is not something the keyboard can reach")
                handle.reads(SemanticsActions.RequestFocus)?.action?.invoke()
                screen.render()

                screen.press(Key.DirectionUp)
                screen.settle("Sky Team moved up") { shown(table).getOrNull(1) == "2 Sky Team" }

                assertEquals(listOf("1 Harmonies", "2 Sky Team", "3 Catan"), shown(table))
            }
        }
    }

    @Test
    fun `a game can be carried by its number and dropped on another row`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                theExample(screen, table)
                val handle =
                    screen.nodes().first { node -> node.contentDescriptions().any { it.startsWith("3. sıradaki Sky Team") } }.boundsInRoot
                val onto =
                    screen.nodes().first { node -> node.contentDescriptions().any { it.startsWith("1. sıradaki Harmonies") } }.boundsInRoot

                screen.dragFrom(from = handle.center, to = Offset(handle.center.x, onto.center.y))
                screen.settle("Sky Team is first") { shown(table).firstOrNull() == "1 Sky Team" }

                assertEquals(listOf("1 Sky Team", "2 Harmonies", "3 Catan"), shown(table))
            }
        }
    }

    @Test
    fun `while a game is carried the others make way, and the order is kept when it is let go`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                theExample(screen, table)
                screen.settle("the example is on disk") { Files.exists(orderFile) }
                val onDisk = Files.readAllBytes(orderFile)
                val handle = handleOf(screen, "3. sıradaki Sky Team")
                val onto = handleOf(screen, "1. sıradaki Harmonies")

                screen.pressAt(handle.center)
                screen.moveTo(from = handle.center, to = Offset(handle.center.x, onto.center.y - 10f), steps = 8)

                // Still held: the order a drop would make is already on the screen,
                // numbers and all, and nothing has been written.
                assertEquals(listOf("1 Sky Team", "2 Harmonies", "3 Catan"), shown(table))
                assertTrue(onDisk.contentEquals(Files.readAllBytes(orderFile)), "the order was written before the row was let go")

                screen.releaseAt(Offset(handle.center.x, onto.center.y - 10f))
                screen.settle("the order is on disk") { !onDisk.contentEquals(Files.readAllBytes(orderFile)) }
                assertEquals(listOf("1 Sky Team", "2 Harmonies", "3 Catan"), shown(table))
                val kept = runBlocking { DesktopGameOrderStore(orderFile).read() }
                assertEquals(listOf("Sky Team", "Harmonies", "Catan").map { idOf(table, it) }, kept.gameIds)
            }
        }
    }

    @Test
    fun `pulling the first game a little past the second moves it one place and no further`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                addGames(screen, table, *TEN)
                val names = shown(table).map { it.substringAfter(' ') }
                val first = handleOf(screen, "1. sıradaki ${names[0]}")
                val second = handleOf(screen, "2. sıradaki ${names[1]}")
                val rowStep = second.top - first.top

                // Just past the middle of the second row, slowly, as a hand does.
                screen.pressAt(first.center)
                val to = Offset(first.center.x, first.center.y + rowStep * 0.7f)
                screen.moveTo(from = first.center, to = to, steps = 12)
                screen.render()

                val during = shown(table)
                assertEquals("2 ${names[0]}", during[1], "while held the first game went to the wrong place: $during")
                assertEquals("1 ${names[1]}", during[0], "while held: $during")

                screen.releaseAt(to)
                screen.settle("the drop is kept") { Files.exists(orderFile) }
                val after = shown(table)
                assertEquals(listOf(names[1], names[0]) + names.drop(2), after.map { it.substringAfter(' ') }, "after the drop: $after")
            }
        }
    }

    @Test
    fun `a slow pull down two rows and a bit moves the game two places, one boundary at a time`() {
        // The reported fault: the first game, pulled a little way down, landed
        // in eighth place. Every step of the pull is looked at, not only the end.
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                addGames(screen, table, *TEN)
                val names = shown(table).map { it.substringAfter(' ') }
                val first = handleOf(screen, "1. sıradaki ${names[0]}")
                val rowStep = handleOf(screen, "2. sıradaki ${names[1]}").top - first.top

                screen.pressAt(first.center)
                var at = first.center
                var place = 0
                (1..48).forEach { step ->
                    val next = Offset(first.center.x, first.center.y + rowStep * 2.4f * step / 48f)
                    screen.moveTo(from = at, to = next, steps = 1)
                    at = next
                    val now = rowsOf(table).indexOfFirst { it.gameName == names[0] }
                    assertTrue(now == place || now == place + 1, "step $step jumped from place ${place + 1} to ${now + 1}: ${shown(table)}")
                    place = now
                }
                screen.releaseAt(at)
                screen.render()

                assertEquals(listOf(names[1], names[2], names[0]) + names.drop(3), shown(table).map { it.substringAfter(' ') })
                assertEquals((1..10).toList(), shown(table).map { it.substringBefore(' ').toInt() }, "the numbers are not 1 to 10 in order")
            }
        }
    }

    @Test
    fun `a small pull that does not reach the next row moves nothing`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                addGames(screen, table, *TEN)
                val before = shown(table)
                val first = handleOf(screen, before[0].replace(" ", ". sıradaki "))
                val second = handleOf(screen, before[1].replace(" ", ". sıradaki "))
                val to = Offset(first.center.x, first.center.y + (second.top - first.top) * 0.3f)

                screen.pressAt(first.center)
                screen.moveTo(from = first.center, to = to, steps = 6)
                assertEquals(before, shown(table), "a small pull moved a game")
                screen.releaseAt(to)
                screen.render()
                assertEquals(before, shown(table), "a small pull moved a game once let go")
            }
        }
    }

    @Test
    fun `the handle is big enough to take hold of without aiming`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                theExample(screen, table)
                val handle = handleOf(screen, "1. sıradaki Harmonies")

                assertTrue(handle.width >= 48f - 1f, "the handle is too narrow: $handle")
                assertTrue(handle.height >= 40f - 1f, "the handle is too short: $handle")
            }
        }
    }

    private fun handleOf(
        screen: ComposeSceneHarness,
        spoken: String,
    ) = screen.nodes().first { node -> node.contentDescriptions().any { it.startsWith(spoken) } }.boundsInRoot

    @Test
    fun `in the alphabetical layout there is nothing to take hold of`() {
        RealStack().use { stack ->
            val (screen, table) = open(stack)
            screen.use {
                theExample(screen, table)
                table.arrange(GameArrangement.ALPHABETICAL)
                screen.render()

                assertFalse(
                    screen.nodes().any { node -> node.contentDescriptions().any { "sıradaki" in it } },
                    "a handle is still offered where a place is the name's",
                )
                assertTrue(screen.writtenText().any { "Benim sıram'a dönün" in it }, "the reason is not said")

                // And asking anyway moves nothing.
                runBlocking { table.moveGame(idOf(table, "Sky Team"), idOf(table, "Catan")) }
                table.arrange(GameArrangement.MINE)
                screen.render()
                assertEquals(listOf("1 Harmonies", "2 Catan", "3 Sky Team"), shown(table))
            }
        }
    }

    private companion object {
        val TEN = arrayOf("Azul", "Brass", "Catan", "Dune", "Everdell", "Food Chain", "Gloomhaven", "Hanabi", "Inis", "Jaipur")
    }
}
