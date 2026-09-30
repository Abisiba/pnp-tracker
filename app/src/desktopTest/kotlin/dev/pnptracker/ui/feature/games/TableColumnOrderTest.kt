package dev.pnptracker.ui.feature.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import dev.pnptracker.domain.games.DEFAULT_COLUMN_ORDER
import dev.pnptracker.domain.games.TableColumn
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.platform.settings.DesktopTableSizesStore
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
import dev.pnptracker.ui.WatchedTableSizesStore
import dev.pnptracker.ui.reads
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The columns of the game table, put in the order the user drags them into.
 *
 * On the real screen through the real stack, with the order kept in a real file
 * in a temporary directory, so "still there when the table is opened again" is a
 * second controller over the same file.
 */
class TableColumnOrderTest {
    private lateinit var folder: Path
    private lateinit var sizesFile: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-table-order-test")
        sizesFile = folder.resolve("table-sizes.json")
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

    /** The store of the table opened last, watched so a test waits on its writes and not on the file. */
    private lateinit var sizesStore: WatchedTableSizesStore

    private fun openTable(stack: RealStack): Pair<ComposeSceneHarness, GameTableController> {
        sizesStore = WatchedTableSizesStore(DesktopTableSizesStore(sizesFile))
        val table = stack.tableControllerWith(sizes = sizesStore)
        val screen = ComposeSceneHarness(width = 2000, height = 900) { GameTableScreen(table) }
        screen.settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        return screen to table
    }

    private fun rowsOf(table: GameTableController) = (table.state.rows as? GameTableRowsState.Content)?.rows.orEmpty()

    private fun makeHarmonies(
        screen: ComposeSceneHarness,
        table: GameTableController,
    ): EntityId {
        runBlocking {
            table.startGameComposer()
            table.editGameName("Harmonies")
            table.saveGame()
        }
        screen.settle("the game is shown") { rowsOf(table).any { it.gameName == "Harmonies" } }
        val gameId = rowsOf(table).first { it.gameName == "Harmonies" }.gameId
        table.beginEditing(gameId, CellColumnType.THREE_D)
        screen.settle("the editor opens") { table.state.work is CellWork.WritingText }
        table.editCellText("Ejderha")
        screen.render()
        runBlocking { table.saveEditing() }
        screen.settle("the editor closes") { table.state.work == null }
        return gameId
    }

    /** The headings, left to right, as they are drawn. */
    private fun ComposeSceneHarness.headings(): List<String> =
        nodes()
            .mapNotNull { node ->
                val text =
                    node
                        .reads(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
                        .firstOrNull { it in HEADINGS }
                text?.let { it to node.boundsInRoot }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, bounds) -> bounds.minBy { it.top } }
            .entries
            .sortedBy { it.value.center.x }
            .map { it.key }

    private fun ComposeSceneHarness.headingBounds(name: String): Rect =
        nodes()
            .filter { node -> node.reads(SemanticsProperties.Text).orEmpty().any { it.text == name } }
            .map { it.boundsInRoot }
            .minByOrNull { it.top } ?: fail("the heading `$name` is not drawn")

    private fun ComposeSceneHarness.cellBounds(columnName: String): Rect =
        boundsOf("$columnName hücresi boş") ?: fail("the empty `$columnName` cell is not drawn")

    /** Carries the heading [name] over the heading [onto], checking the order while still held. */
    private fun ComposeSceneHarness.carry(
        name: String,
        onto: String,
        whileHeld: () -> Unit = {},
    ) {
        val from = headingBounds(name).center
        val to = headingBounds(onto).center
        pressAt(from)
        moveTo(from, to, steps = 10)
        render()
        whileHeld()
        releaseAt(to)
        render()
    }

    private fun stored(): List<TableColumn> = runBlocking { DesktopTableSizesStore(sizesFile).read().columnOrder }

    /**
     * Waits until the table has finished writing what it shows, then reads the
     * file once. Reading the file while it is replaced makes Windows refuse the
     * write (WatchedWrites), so the file is never polled.
     */
    private fun ComposeSceneHarness.writtenOrder(table: GameTableController): List<TableColumn> {
        settle("the order is written") { sizesStore.writes.settledOn(table.state.sizes) }
        return stored()
    }

    @Test
    fun `the table opens in today's order`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                makeHarmonies(screen, table)
                assertEquals(HEADINGS, screen.headings())
                assertEquals(DEFAULT_COLUMN_ORDER, table.state.sizes.columnOrder)
            }
        }
    }

    @Test
    fun `a heading dragged over another moves its column live, and letting go remembers it`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                makeHarmonies(screen, table)

                screen.carry("Notlar", onto = "Oyun") {
                    // Still held: the order is already the new one, rows and all,
                    // and nothing has been written yet.
                    assertEquals(
                        TableColumn.NOTES,
                        table.state.sizes.columnOrder
                            .first(),
                        "the order did not follow the drag",
                    )
                    assertTrue(screen.cellBounds("Notlar").left < screen.cellBounds("Kart").left, "the rows did not follow the heading")
                    assertTrue(
                        !Files.exists(sizesFile) || stored().first() != TableColumn.NOTES,
                        "the order was written before the drag ended",
                    )
                }
                assertEquals(TableColumn.NOTES, screen.writtenOrder(table).first())

                assertEquals(listOf("Notlar") + (HEADINGS - "Notlar"), screen.headings())
                assertTrue(
                    screen.cellBounds("Notlar").right <= screen.cellBounds("Kart").left + 1f,
                    "the cells are not in the new order",
                )
                assertEquals(listOf(TableColumn.NOTES) + (DEFAULT_COLUMN_ORDER - TableColumn.NOTES), stored())
            }
        }
    }

    @Test
    fun `every column can be carried, and the order is there when the table is opened again`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                makeHarmonies(screen, table)
                // The name column to the far right, and Eksik to the far left.
                screen.carry("Oyun", onto = "Notlar")
                screen.settle("Oyun is last") {
                    table.state.sizes.columnOrder
                        .last() == TableColumn.GAME_NAME
                }
                screen.carry("Eksik", onto = "3D Baskı")
                screen.settle("Eksik moved") {
                    table.state.sizes.columnOrder
                        .first() == TableColumn.THREE_D
                }
                assertEquals(table.state.sizes.columnOrder, screen.writtenOrder(table))
            }
            val remembered = table.state.sizes.columnOrder
            assertTrue(remembered != DEFAULT_COLUMN_ORDER)

            // A new controller over the same file: what the next start does.
            val (again, reopened) = openTable(stack)
            again.use {
                again.settle("the order is read") { reopened.state.sizes.columnOrder == remembered }
                again.settle("the rows are shown") { rowsOf(reopened).isNotEmpty() }
                val names =
                    mapOf(
                        TableColumn.GAME_NAME to "Oyun",
                        TableColumn.MISSING to "Eksik",
                        TableColumn.THREE_D to "3D Baskı",
                        TableColumn.CARD to "Kart",
                        TableColumn.BOARD to "Mukavva",
                        TableColumn.SPECIAL to "Özel",
                        TableColumn.BORROWED to "Ödünç Parçalar",
                        TableColumn.NOTES to "Notlar",
                    )
                assertEquals(remembered.map(names::getValue), again.headings())
            }
        }
    }

    @Test
    fun `Varsayılan sıraya dön puts the columns back and keeps the widths`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                makeHarmonies(screen, table)
                table.resizeColumn(TableColumn.CARD, 320f)
                screen.carry("Notlar", onto = "Oyun")
                assertEquals(TableColumn.NOTES, screen.writtenOrder(table).first())

                assertTrue(screen.tabTo(MORE_ACTIONS, limit = 40), "the menu cannot be reached from the keyboard")
                screen.press(Key.Enter)
                screen.render()
                screen.render()
                assertTrue(screen.click(RESET_ORDER), "there is no way back to the default order")
                screen.settle("the default order is shown") { table.state.sizes.columnOrder == DEFAULT_COLUMN_ORDER }
                assertEquals(DEFAULT_COLUMN_ORDER, screen.writtenOrder(table))

                assertEquals(HEADINGS, screen.headings())
                assertEquals(320f, table.state.sizes.widthOf(TableColumn.CARD), "putting the order back reset a width")
            }
        }
    }

    @Test
    fun `after a move the cells still open their own editor and the edges still resize their own column`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                val gameId = makeHarmonies(screen, table)
                screen.carry("Notlar", onto = "Oyun")
                assertEquals(TableColumn.NOTES, screen.writtenOrder(table).first())

                screen.mouseDoubleClick(screen.cellBounds("Notlar").center)
                screen.settle("the notes editor opens") { table.state.work is CellWork.WritingText }
                val editing = table.state.work as CellWork.WritingText
                assertEquals(gameId, editing.gameId)
                assertEquals(CellColumnType.NOTES, editing.columnType, "the cell under the pointer is not the one that opened")
                table.cancelEditing()
                screen.render()

                val kart = screen.cellBounds("Kart")
                val edge = Offset(kart.right, screen.headingBounds("Kart").center.y)
                screen.dragFrom(edge, edge + Offset(100f, 0f))
                screen.render()
                assertEquals(kart.width + 100f, screen.cellBounds("Kart").width, 2f, "the Kart edge no longer resizes Kart")
                assertEquals(listOf(TableColumn.NOTES) + (DEFAULT_COLUMN_ORDER - TableColumn.NOTES), table.state.sizes.columnOrder)
            }
        }
    }

    @Test
    fun `moving a column changes nothing the database holds`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                val gameId = makeHarmonies(screen, table)
                val before =
                    runBlocking {
                        val cell = stack.database.cellSegmentDao().cellOfGame(gameId, CellColumnType.THREE_D)
                        Triple(
                            cell,
                            stack.database.cellSegmentDao().runsOfCell(requireNotNull(cell).id),
                            stack.database.taskDao().allTasksIncludingDeleted(),
                        )
                    }

                screen.carry("3D Baskı", onto = "Notlar")
                assertEquals(TableColumn.THREE_D, screen.writtenOrder(table).last())

                val after =
                    runBlocking {
                        val cell = stack.database.cellSegmentDao().cellOfGame(gameId, CellColumnType.THREE_D)
                        Triple(
                            cell,
                            stack.database.cellSegmentDao().runsOfCell(requireNotNull(cell).id),
                            stack.database.taskDao().allTasksIncludingDeleted(),
                        )
                    }
                assertEquals(before, after, "moving a column changed what the database holds")
            }
        }
    }

    private companion object {
        val HEADINGS = listOf("Oyun", "Eksik", "3D Baskı", "Kart", "Mukavva", "Özel", "Ödünç Parçalar", "Notlar")
        const val MORE_ACTIONS = "Diğer işlemler"
        const val RESET_ORDER = "Varsayılan sıraya dön"
    }
}
