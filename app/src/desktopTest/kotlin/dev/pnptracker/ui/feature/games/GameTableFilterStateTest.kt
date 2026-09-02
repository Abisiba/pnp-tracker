package dev.pnptracker.ui.feature.games

import dev.pnptracker.data.repository.GameTableSource
import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.games.TaskColorPreview
import dev.pnptracker.domain.games.documentText
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.ui.NoCells
import dev.pnptracker.ui.NoColors
import dev.pnptracker.ui.NoEditing
import dev.pnptracker.ui.NoProgress
import dev.pnptracker.ui.NoSetup
import dev.pnptracker.ui.NoTaskCreation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the game table lists once the user has narrowed it, and what it keeps.
 *
 * Two rules are worth more than the rest. The three global views are about
 * games and the new filters are about the work inside them, so neither may
 * quietly become the other. And a row that is listed is listed whole: PLAN 5.5
 * makes a cell the pieces of its document in order, so a filter that reached
 * into the cells would leave one reading something nobody typed.
 */
class GameTableFilterStateTest {
    private val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#C62828")
    private val grey = TaskColorPreview(IdGenerator.Random.newId(), "Gri", "#808080")

    private fun taskPiece(
        name: String,
        poolType: PoolType = PoolType.THREE_D,
        colors: List<TaskColorPreview> = listOf(red),
    ) = CellSegmentPreview(
        segmentId = IdGenerator.Random.newId(),
        taskId = IdGenerator.Random.newId(),
        text = name,
        requiredQuantity = 12,
        colors = colors,
        poolType = poolType,
    )

    private fun textPiece(text: String) = CellSegmentPreview(segmentId = IdGenerator.Random.newId(), taskId = null, text = text)

    private fun row(
        gameName: String,
        isCompleted: Boolean = false,
        threeD: List<CellSegmentPreview> = emptyList(),
        card: List<CellSegmentPreview> = emptyList(),
    ) = GameTableRow(
        gameId = IdGenerator.Random.newId(),
        gameName = gameName,
        isCompleted = isCompleted,
        cells =
            CellColumnType.entries.map { column ->
                CellPreview(
                    columnType = column,
                    cellId = IdGenerator.Random.newId(),
                    segments =
                        when (column) {
                            CellColumnType.THREE_D -> threeD
                            CellColumnType.CARD -> card
                            else -> emptyList()
                        },
                )
            },
    )

    private class FixedTable(
        rows: List<GameTableRow>,
    ) : GameTableSource {
        val rows = MutableStateFlow(rows)

        override fun observeTable(): Flow<List<GameTableRow>> = rows
    }

    /** Runs [block] over a controller that has collected one whole reading. */
    private suspend fun withTable(
        scope: CoroutineScope,
        rows: List<GameTableRow>,
        block: suspend (GameTableController, FixedTable) -> Unit,
    ) {
        val table = FixedTable(rows)
        val controller =
            GameTableController(
                table = table,
                setup = NoSetup(),
                cells = NoCells(),
                colors = NoColors(),
                taskCreation = NoTaskCreation(),
                taskEditing = NoEditing(),
                taskProgress = NoProgress(),
            )
        val collecting = scope.launch { controller.observeTable() }
        try {
            withTimeout(10_000) {
                while (controller.state.rows is GameTableRowsState.Loading) delay(2)
            }
            block(controller, table)
        } finally {
            // Never ends on its own; a `runBlocking` waiting for it would hang.
            collecting.cancel()
        }
    }

    private fun GameTableController.namesShown(): List<String> =
        (state.rows as? GameTableRowsState.Content)?.rows?.map { it.gameName }.orEmpty()

    // ------------------------------------------------------------- searching

    @Test
    fun `the table is narrowed by a game name and by a task name`() =
        runBlocking<Unit> {
            val rows =
                listOf(
                    row("Harmonies", threeD = listOf(taskPiece("Kırmızı ev"))),
                    row("Wingspan", threeD = listOf(taskPiece("Mavi çatı"))),
                )
            withTable(this, rows) { controller, _ ->
                controller.search("wing")
                assertEquals(listOf("Wingspan"), controller.namesShown())

                controller.search("kırmızı ev")
                assertEquals(listOf("Harmonies"), controller.namesShown())
            }
        }

    @Test
    fun `a fresh list does not empty the search box or undo a filter`() =
        runBlocking<Unit> {
            val rows = listOf(row("Harmonies", threeD = listOf(taskPiece("Kırmızı ev"))), row("Wingspan"))
            withTable(this, rows) { controller, table ->
                controller.search("harmonies")
                controller.togglePool(PoolType.THREE_D)
                assertEquals(listOf("Harmonies"), controller.namesShown())

                table.rows.value = rows + row("Everdell", threeD = listOf(taskPiece("Yeşil ağaç")))
                withTimeout(10_000) { while (controller.namesShown() != listOf("Harmonies")) delay(2) }

                assertEquals("harmonies", controller.state.searchText)
                assertEquals(setOf(PoolType.THREE_D), controller.state.filter.poolTypes)
            }
        }

    // ------------------------------------------------- the views stay apart

    @Test
    fun `the global view and the task filters do not become one another`() =
        runBlocking<Unit> {
            val rows =
                listOf(
                    row("Devam eden", threeD = listOf(taskPiece("Kırmızı ev"))),
                    row("Bitmiş oyun", isCompleted = true, threeD = listOf(taskPiece("Kırmızı ev"))),
                )
            withTable(this, rows) { controller, _ ->
                // The filter narrows inside whichever view is open, and changes
                // neither the view nor which games belong to it.
                controller.togglePool(PoolType.THREE_D)
                assertEquals(GameTableView.ONGOING, controller.state.view)
                assertEquals(listOf("Devam eden"), controller.namesShown())

                controller.showView(GameTableView.COMPLETED)
                assertEquals(setOf(PoolType.THREE_D), controller.state.filter.poolTypes, "moving view dropped the filter")
                assertEquals(listOf("Bitmiş oyun"), controller.namesShown())

                controller.showView(GameTableView.ALL)
                assertEquals(listOf("Devam eden", "Bitmiş oyun"), controller.namesShown())
            }
        }

    // ---------------------------------------------- the document is untouched

    @Test
    fun `a row that matches keeps every piece of its document`() =
        runBlocking<Unit> {
            val prose = textPiece("Kutu ölçüsü 30×30 ")
            val first = taskPiece("Kırmızı ev", colors = listOf(red))
            val space = textPiece(" ")
            val second = taskPiece("Gri zar", colors = listOf(grey))
            val subject = row("Harmonies", threeD = listOf(prose, first, space, second))

            withTable(this, listOf(subject, row("Wingspan"))) { controller, _ ->
                // Found by one of its two tasks and by one of its two colours.
                controller.search("kırmızı ev")
                controller.toggleColor(red.colorId)

                val shown = assertIs<GameTableRowsState.Content>(controller.state.rows).rows.single()
                val cell = shown.cell(CellColumnType.THREE_D)
                assertEquals(listOf(prose, first, space, second), cell.segments, "the filter reached into the cell")
                assertEquals("Kutu ölçüsü 30×30 Kırmızı ev Gri zar", cell.text)
                assertEquals(cell.text, cell.runs.documentText())
                assertEquals(2, cell.segments.count { it.isTask })
                // Every column is still there, in table order.
                assertEquals(CellColumnType.entries, shown.cells.map { it.columnType })
            }
        }

    // ------------------------------------------------ emptiness, told apart

    @Test
    fun `an empty library is not the same as a filter that found nothing`() =
        runBlocking<Unit> {
            withTable(this, emptyList()) { controller, _ ->
                val empty = assertIs<GameTableRowsState.Empty>(controller.state.rows)
                assertFalse(empty.hasGamesInOtherViews)
                assertFalse(empty.hiddenByFilter, "an empty library was blamed on the filter")
            }

            withTable(this, listOf(row("Harmonies"))) { controller, _ ->
                controller.search("bulunamaz")
                val empty = assertIs<GameTableRowsState.Empty>(controller.state.rows)
                assertTrue(empty.hiddenByFilter, "a filter that hid every row was not said to have")
            }
        }

    // ------------------------------------------------------------- clearing

    @Test
    fun `clearing the search leaves the pool and colour choices alone`() =
        runBlocking<Unit> {
            withTable(this, listOf(row("Harmonies", threeD = listOf(taskPiece("Kırmızı ev"))))) { controller, _ ->
                controller.search("harm")
                controller.togglePool(PoolType.THREE_D)
                controller.toggleColor(red.colorId)

                controller.clearSearch()

                assertEquals("", controller.state.searchText)
                assertEquals(setOf(PoolType.THREE_D), controller.state.filter.poolTypes)
                assertEquals(setOf(red.colorId), controller.state.filter.colorIds)
            }
        }

    @Test
    fun `clearing everything puts the table back the way it opened`() =
        runBlocking<Unit> {
            val rows = listOf(row("Harmonies", threeD = listOf(taskPiece("Kırmızı ev"))), row("Wingspan"))
            withTable(this, rows) { controller, _ ->
                controller.search("harm")
                controller.togglePool(PoolType.THREE_D)
                controller.toggleAwaitingColor()

                controller.clearFilters()

                assertEquals("", controller.state.searchText)
                assertFalse(controller.state.isNarrowed)
                assertEquals(0, controller.state.chosenFilterCount)
                assertEquals(listOf("Harmonies", "Wingspan"), controller.namesShown())
            }
        }

    // -------------------------------------------------------- the surfaces

    @Test
    fun `Escape closes the filter panel and leaves an open cell alone`() =
        runBlocking<Unit> {
            val subject = row("Harmonies", threeD = listOf(textPiece("Kutu")))
            withTable(this, listOf(subject)) { controller, _ ->
                controller.beginEditing(subject.gameId, CellColumnType.THREE_D)
                assertTrue(controller.state.work is CellWork.WritingText)
                controller.openFilters()

                controller.closeInnermost()

                assertEquals(TableFilterSurface.CLOSED, controller.state.filterSurface)
                assertTrue(controller.state.work is CellWork.WritingText, "closing the panel closed the cell editor too")
            }
        }

    @Test
    fun `a filter is not refused while a cell is open`() =
        runBlocking<Unit> {
            val subject = row("Harmonies", threeD = listOf(textPiece("Kutu")))
            withTable(this, listOf(subject, row("Wingspan"))) { controller, _ ->
                controller.beginEditing(subject.gameId, CellColumnType.THREE_D)

                controller.search("wing")

                // The row being typed in is kept whatever the filter says, so
                // nothing is carried off the screen and nothing is refused.
                assertFalse(controller.state.blockedByEditor)
                assertEquals(listOf("Harmonies", "Wingspan"), controller.namesShown())
                assertTrue(controller.state.work is CellWork.WritingText)
            }
        }

    @Test
    fun `closing the filter panel calls the keyboard back`() =
        runBlocking<Unit> {
            withTable(this, listOf(row("Harmonies"))) { controller, _ ->
                controller.openFilters()
                val before = controller.state.focusRecall

                controller.closeFilters()

                assertTrue(controller.state.focusRecall > before)
            }
        }
}
