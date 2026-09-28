package dev.pnptracker.ui.feature.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import dev.pnptracker.domain.games.DEFAULT_CELL_COLUMN_WIDTH_DP
import dev.pnptracker.domain.games.MINIMUM_COLUMN_WIDTH_DP
import dev.pnptracker.domain.games.MINIMUM_ROW_HEIGHT_DP
import dev.pnptracker.domain.games.TableColumn
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.platform.settings.DesktopTableSizesStore
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.RealStack
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
 * The table drawn at the sizes the user chose (PLAN 12.17).
 *
 * Measured on the real screen through the real stack, because every claim here is
 * about where something is drawn and how wide it is. The sizes go to a real file
 * in a temporary directory, so "it is still there when the screen is opened
 * again" means what it says: a second controller over the same file.
 */
class TableResizeTest {
    private lateinit var folder: Path
    private lateinit var sizesFile: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-table-resize-test")
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
                // Two more frames: a row that has only just arrived is placed from
                // the layout of the frame after the one that learned about it.
                repeat(2) { render() }
                return
            }
            Thread.sleep(10)
        }
        fail("never happened: $what")
    }

    /** Harmonies, with a word in its 3D cell, on a screen that remembers its sizes. */
    private fun openTable(stack: RealStack): Pair<ComposeSceneHarness, GameTableController> {
        val table = stack.tableControllerWith(sizes = DesktopTableSizesStore(sizesFile))
        val screen = ComposeSceneHarness(width = 1500, height = 900) { GameTableScreen(table) }
        screen.settle("the table is read") { table.state.rows !is GameTableRowsState.Loading }
        return screen to table
    }

    private fun makeHarmonies(
        screen: ComposeSceneHarness,
        table: GameTableController,
        stack: RealStack,
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
        table.editCellText("Ejderha\nKalkan\nKule\nSurlar")
        screen.render()
        runBlocking { table.saveEditing() }
        screen.settle("the word is stored") { piecesOf(table, gameId).any { it.text.startsWith("Ejderha") } }
        screen.settle("the editor closes") { table.state.work == null }
        return gameId
    }

    private fun rowsOf(table: GameTableController) = (table.state.rows as? GameTableRowsState.Content)?.rows.orEmpty()

    private fun piecesOf(
        table: GameTableController,
        gameId: EntityId,
    ) = rowsOf(table)
        .firstOrNull { it.gameId == gameId }
        ?.cell(CellColumnType.THREE_D)
        ?.segments
        .orEmpty()

    /** Where a heading is drawn, which is how wide its column is. */
    private fun ComposeSceneHarness.headingBounds(name: String): Rect {
        val headings =
            nodes()
                .filter { node -> node.reads(SemanticsProperties.Text).orEmpty().any { it.text == name } }
                .map { it.boundsInRoot }
        return headings.minByOrNull { it.top } ?: fail("the heading `$name` is not drawn")
    }

    private fun ComposeSceneHarness.cellBounds(columnName: String): Rect =
        boundsOf("$columnName hücresi boş") ?: fail("the empty `$columnName` cell is not drawn")

    /**
     * How tall a row is drawn: its cells fill it, so a cell's height is the row's.
     *
     * The name cell's own description belongs to the tick beside the name and is
     * the size of a character, which is not what a row measures.
     */
    private fun ComposeSceneHarness.rowHeight(columnName: String): Float = cellBounds(columnName).height

    /** Where the boundary of a column is: its cell's right edge, at the headings' height. */
    private fun ComposeSceneHarness.columnBoundary(columnName: String): Offset =
        Offset(cellBounds(columnName).right, headingBounds(columnName).center.y)

    /**
     * Where the first row's bottom edge is, taken just inside it so the grip is hit.
     *
     * Every cell of a row is as tall as the row, so any of them will do; the first
     * row is the one whose cells start highest up.
     */
    private fun ComposeSceneHarness.rowBottom(columnName: String): Offset {
        val cell = cellBounds(columnName)
        return Offset(cell.center.x, cell.bottom - 2f)
    }

    @Test
    fun `dragging the boundary beside a heading widens that column and leaves the others`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                makeHarmonies(screen, table, stack)
                val cardBefore = screen.cellBounds("Kart").width
                val boardBefore = screen.cellBounds("Mukavva").width
                val boundary = screen.columnBoundary("Kart")

                screen.dragFrom(from = boundary, to = boundary + Offset(120f, 0f))
                screen.render()

                assertEquals(cardBefore + 120f, screen.cellBounds("Kart").width, TOLERANCE, "the Kart column did not follow the drag")
                assertEquals(boardBefore, screen.cellBounds("Mukavva").width, TOLERANCE, "another column moved with it")
                assertEquals(320f, table.state.sizes.widthOf(TableColumn.CARD), TOLERANCE)
            }
        }
    }

    @Test
    fun `a column cannot be dragged below the width that keeps it readable`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                makeHarmonies(screen, table, stack)
                val boundary = screen.columnBoundary("Kart")

                screen.dragFrom(from = boundary, to = boundary - Offset(400f, 0f))
                screen.render()

                assertEquals(MINIMUM_COLUMN_WIDTH_DP, screen.cellBounds("Kart").width, TOLERANCE)
            }
        }
    }

    @Test
    fun `dragging a row's bottom edge makes that row taller and nobody else`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                val gameId = makeHarmonies(screen, table, stack)
                runBlocking {
                    table.startGameComposer()
                    table.editGameName("Wingspan")
                    table.saveGame()
                }
                screen.settle("both games are shown") { rowsOf(table).size == 2 }
                val wingspan = rowsOf(table).first { it.gameName == "Wingspan" }.gameId
                // Harmonies has writing in it and Wingspan has none, so their cells
                // are told apart by what is in them.
                val harmoniesBefore = screen.cellBounds("Kart")
                val tallBefore = harmoniesBefore.height

                screen.dragFrom(from = screen.rowBottom("Kart"), to = screen.rowBottom("Kart") + Offset(0f, 90f))
                screen.render()

                assertEquals(tallBefore + 90f, screen.cellBounds("Kart").height, TOLERANCE, "the row did not follow the drag")
                assertEquals(tallBefore + 90f, table.state.sizes.heightOf(gameId) ?: 0f, TOLERANCE)
                assertEquals(1, table.state.sizes.rowHeights.size, "the drag reached more than one row")
                assertEquals(null, table.state.sizes.heightOf(wingspan), "the other row was given a height")
            }
        }
    }

    @Test
    fun `a row dragged shorter than its writing still shows all of it`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                val gameId = makeHarmonies(screen, table, stack)
                val natural = screen.rowHeight("Kart")

                screen.dragFrom(from = screen.rowBottom("Kart"), to = screen.rowBottom("Kart") - Offset(0f, 400f))
                screen.render()

                // The smallest height is what was asked for and what is kept...
                assertEquals(MINIMUM_ROW_HEIGHT_DP, table.state.sizes.heightOf(gameId) ?: 0f, TOLERANCE)
                // ...and the row is still as tall as its four lines need (PLAN 12.17).
                assertEquals(natural, screen.rowHeight("Kart"), TOLERANCE, "a small height cut the writing")
            }
        }
    }

    @Test
    fun `what was dragged is still there when the table is opened again`() {
        RealStack().use { stack ->
            val gameId: EntityId
            val (first, table) = openTable(stack)
            first.use {
                gameId = makeHarmonies(first, table, stack)
                val boundary = first.columnBoundary("Kart")
                first.dragFrom(boundary, boundary + Offset(120f, 0f))
                first.dragFrom(first.rowBottom("Kart"), first.rowBottom("Kart") + Offset(0f, 90f))
                first.render()
            }

            // A new controller over the same file, which is what a restart is.
            val (second, reopened) = openTable(stack)
            second.use {
                second.settle("the sizes are read") { !reopened.state.sizes.isDefault }

                assertEquals(320f, second.cellBounds("Kart").width, TOLERANCE, "the column width was forgotten")
                assertTrue(
                    (reopened.state.sizes.heightOf(gameId) ?: 0f) > MINIMUM_ROW_HEIGHT_DP,
                    "the row height was forgotten: ${reopened.state.sizes}",
                )
            }
        }
    }

    @Test
    fun `fitting a column to its content is offered from the keyboard and holds the bounds`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                makeHarmonies(screen, table, stack)

                assertTrue(screen.tabTo(FIT_COLUMN, limit = 40), "the fit action cannot be reached from the keyboard")
                assertTrue(screen.click(FIT_COLUMN), "the fit action does not answer")
                screen.render()
                assertTrue(screen.click(THREE_D_HEADING), "the fit menu does not offer the 3D column")
                screen.render()

                val fitted = table.state.sizes.widthOf(TableColumn.THREE_D)
                assertTrue(fitted >= MINIMUM_COLUMN_WIDTH_DP, "a fit went under the smallest width: $fitted")
                assertTrue(fitted <= AUTOMATIC_LIMIT, "a fit went over the bound: $fitted")
                assertTrue(fitted != DEFAULT_CELL_COLUMN_WIDTH_DP, "the fit changed nothing at all")
            }
        }
    }

    @Test
    fun `resetting from the keyboard puts every column and row back`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                val gameId = makeHarmonies(screen, table, stack)
                val boundary = screen.columnBoundary("Kart")
                screen.dragFrom(boundary, boundary + Offset(120f, 0f))
                screen.dragFrom(screen.rowBottom("Kart"), screen.rowBottom("Kart") + Offset(0f, 90f))
                screen.render()

                assertTrue(screen.tabTo(RESET_SIZES, limit = 40), "the reset action cannot be reached from the keyboard")
                assertTrue(screen.click(RESET_SIZES), "the reset action does not answer")
                screen.settle("the sizes are back to the defaults") { table.state.sizes.isDefault }

                assertEquals(DEFAULT_CELL_COLUMN_WIDTH_DP, screen.cellBounds("Kart").width, TOLERANCE)
                assertEquals(null, table.state.sizes.heightOf(gameId))
            }
        }
    }

    @Test
    fun `resizing writes nothing to the database`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                val gameId = makeHarmonies(screen, table, stack)
                val before = piecesOf(table, gameId).map { it.segmentId to it.text }
                val storedBefore =
                    runBlocking {
                        val cell = stack.database.cellSegmentDao().cellOfGame(gameId, CellColumnType.THREE_D)
                        cell to stack.database.cellSegmentDao().runsOfCell(requireNotNull(cell).id)
                    }

                val boundary = screen.columnBoundary("Kart")
                screen.dragFrom(boundary, boundary + Offset(120f, 0f))
                screen.dragFrom(screen.rowBottom("Kart"), screen.rowBottom("Kart") + Offset(0f, 90f))
                screen.render()

                assertEquals(before, piecesOf(table, gameId).map { it.segmentId to it.text }, "a drag changed the cell")
                val storedAfter =
                    runBlocking {
                        val cell = stack.database.cellSegmentDao().cellOfGame(gameId, CellColumnType.THREE_D)
                        cell to stack.database.cellSegmentDao().runsOfCell(requireNotNull(cell).id)
                    }
                assertEquals(storedBefore, storedAfter, "a drag changed what the database holds")
            }
        }
    }

    @Test
    fun `a drag does not open an editor or take the keyboard`() {
        RealStack().use { stack ->
            val (screen, table) = openTable(stack)
            screen.use {
                makeHarmonies(screen, table, stack)
                val boundary = screen.columnBoundary("Kart")

                screen.dragFrom(boundary, boundary + Offset(60f, 0f))
                screen.render()

                assertEquals(null, table.state.work, "a drag opened work in a cell")
                assertEquals(null, table.state.rowWork, "a drag opened work on a row")
            }
        }
    }

    private companion object {
        const val TOLERANCE = 2f
        const val AUTOMATIC_LIMIT = 480f
        const val FIT_COLUMN = "Sütunu içeriğe göre ayarla"
        const val RESET_SIZES = "Hücre boyutlarını sıfırla"
        const val THREE_D_HEADING = "3D Baskı"
    }
}
