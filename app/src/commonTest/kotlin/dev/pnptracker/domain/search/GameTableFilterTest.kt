package dev.pnptracker.domain.search

import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.games.TaskColorPreview
import dev.pnptracker.domain.games.documentText
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which game rows the table lists once the user has narrowed it.
 *
 * The table's filter answers one question — is this row one of the ones being
 * looked for — and the row is then drawn whole. PLAN 5.5 makes a cell the pieces
 * of its document in order, so hiding the pieces that did not match would leave
 * a cell reading something nobody typed. Several of these exist only to hold
 * that line.
 */
class GameTableFilterTest {
    private val red = TaskColorPreview(IdGenerator.Random.newId(), "Kırmızı", "#C62828")
    private val grey = TaskColorPreview(IdGenerator.Random.newId(), "Gri", "#808080")
    private val green = TaskColorPreview(IdGenerator.Random.newId(), "Yeşil", "#2E7D32")

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
        gameName: String = "Harmonies",
        threeD: List<CellSegmentPreview> = emptyList(),
        card: List<CellSegmentPreview> = emptyList(),
        gameId: EntityId = IdGenerator.Random.newId(),
    ) = GameTableRow(
        gameId = gameId,
        gameName = gameName,
        isCompleted = false,
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

    private fun kept(
        rows: List<GameTableRow>,
        filter: GameTableFilter,
    ): List<String> = rows.filter(filter::matches).map { it.gameName }

    // ------------------------------------------------------------- searching

    @Test
    fun `a row is found by the name of its game`() {
        val rows = listOf(row(gameName = "Harmonies"), row(gameName = "Wingspan"))

        assertEquals(listOf("Wingspan"), kept(rows, GameTableFilter(query = SearchQuery("wing"))))
    }

    @Test
    fun `a row is found by the name of a task written in it`() {
        val rows =
            listOf(
                row(gameName = "Harmonies", threeD = listOf(taskPiece("Kırmızı ev"))),
                row(gameName = "Wingspan", threeD = listOf(taskPiece("Mavi çatı"))),
            )

        assertEquals(listOf("Harmonies"), kept(rows, GameTableFilter(query = SearchQuery("kırmızı ev"))))
    }

    @Test
    fun `the plain text of a cell is not searched`() {
        val rows =
            listOf(
                row(
                    gameName = "Harmonies",
                    threeD = listOf(textPiece("Kutu ölçüsü 30×30 "), taskPiece("Kırmızı ev")),
                ),
            )

        assertEquals(emptyList(), kept(rows, GameTableFilter(query = SearchQuery("kutu"))), "the cell's prose was searched")
        assertEquals(listOf("Harmonies"), kept(rows, GameTableFilter(query = SearchQuery("kırmızı"))))
    }

    @Test
    fun `an empty query keeps every row`() {
        val rows = listOf(row(gameName = "Harmonies"), row(gameName = "Wingspan"))

        assertEquals(listOf("Harmonies", "Wingspan"), kept(rows, GameTableFilter.NONE))
    }

    @Test
    fun `the Turkish alphabet is folded the same way it is everywhere else`() {
        val rows = listOf(row(gameName = "Işıklı Şehir"), row(gameName = "İsimsiz"))

        assertEquals(listOf("Işıklı Şehir"), kept(rows, GameTableFilter(query = SearchQuery("IŞIK"))))
        assertEquals(listOf("İsimsiz"), kept(rows, GameTableFilter(query = SearchQuery("İSİM"))))
        assertEquals(emptyList(), kept(rows, GameTableFilter(query = SearchQuery("isik"))))
    }

    // ---------------------------------------------------------- pools

    @Test
    fun `a pool keeps the rows that hold work in it`() {
        val rows =
            listOf(
                row(gameName = "Sadece 3D", threeD = listOf(taskPiece("Ev", PoolType.THREE_D))),
                row(gameName = "Sadece kart", card = listOf(taskPiece("Deste", PoolType.CARD))),
            )

        assertEquals(listOf("Sadece 3D"), kept(rows, GameTableFilter(poolTypes = setOf(PoolType.THREE_D))))
        assertEquals(listOf("Sadece kart"), kept(rows, GameTableFilter(poolTypes = setOf(PoolType.CARD))))
    }

    @Test
    fun `two pools keep the rows holding work in either`() {
        val rows =
            listOf(
                row(gameName = "Sadece 3D", threeD = listOf(taskPiece("Ev", PoolType.THREE_D))),
                row(gameName = "Sadece kart", card = listOf(taskPiece("Deste", PoolType.CARD))),
                row(gameName = "Boş"),
            )

        assertEquals(
            listOf("Sadece 3D", "Sadece kart"),
            kept(rows, GameTableFilter(poolTypes = setOf(PoolType.THREE_D, PoolType.CARD))),
        )
    }

    // --------------------------------------------------------------- colours

    @Test
    fun `a colour keeps the rows holding work made in it`() {
        val rows =
            listOf(
                row(gameName = "Kırmızılı", threeD = listOf(taskPiece("Ev", colors = listOf(red)))),
                row(gameName = "Grili", threeD = listOf(taskPiece("Zar", colors = listOf(grey)))),
            )

        assertEquals(listOf("Kırmızılı"), kept(rows, GameTableFilter(colorIds = setOf(red.colorId))))
    }

    @Test
    fun `a row holding work of several colours passes on any one of them`() {
        val rows = listOf(row(gameName = "Çok renkli", threeD = listOf(taskPiece("Ev", colors = listOf(grey, green)))))

        assertEquals(listOf("Çok renkli"), kept(rows, GameTableFilter(colorIds = setOf(green.colorId))))
        assertEquals(listOf("Çok renkli"), kept(rows, GameTableFilter(colorIds = setOf(grey.colorId))))
    }

    @Test
    fun `work with no colour is found only by asking for it`() {
        val rows =
            listOf(
                row(gameName = "Renksiz", threeD = listOf(taskPiece("Ev", colors = emptyList()))),
                row(gameName = "Kırmızılı", threeD = listOf(taskPiece("Ev", colors = listOf(red)))),
            )

        assertEquals(listOf("Renksiz"), kept(rows, GameTableFilter(awaitingColor = true)))
    }

    @Test
    fun `plain text is not work with no colour`() {
        // A piece of prose carries no colours either, and counting it would put
        // every row with any writing in it under `Renk seçilecek`.
        val rows = listOf(row(gameName = "Sadece yazı", threeD = listOf(textPiece("Kutu ölçüsü 30×30"))))

        assertEquals(emptyList(), kept(rows, GameTableFilter(awaitingColor = true)))
    }

    // ------------------------------------------------- how they fit together

    @Test
    fun `different kinds of choice narrow together`() {
        val wanted = row(gameName = "Harmonies", threeD = listOf(taskPiece("Kırmızı ev", colors = listOf(red))))
        val rows =
            listOf(
                wanted,
                row(gameName = "Harmonies 2", threeD = listOf(taskPiece("Gri ev", colors = listOf(grey)))),
                row(gameName = "Wingspan", threeD = listOf(taskPiece("Kırmızı ev", colors = listOf(red)))),
            )

        val filter =
            GameTableFilter(
                query = SearchQuery("harmonies"),
                poolTypes = setOf(PoolType.THREE_D),
                colorIds = setOf(red.colorId),
            )
        assertEquals(listOf("Harmonies"), kept(rows, filter))
    }

    @Test
    fun `the ordinary table asks for nothing and counts nothing`() {
        assertFalse(GameTableFilter.NONE.isNarrowed)
        assertEquals(0, GameTableFilter.NONE.chosenCount)
        assertEquals(
            3,
            GameTableFilter(poolTypes = setOf(PoolType.CARD), colorIds = setOf(red.colorId), awaitingColor = true).chosenCount,
        )
    }

    @Test
    fun `a row that matches keeps every piece of every cell it holds`() {
        val prose = textPiece("Kutu ölçüsü 30×30 ")
        val first = taskPiece("Kırmızı ev", colors = listOf(red))
        val space = textPiece(" ")
        val second = taskPiece("Gri zar", colors = listOf(grey))
        val subject = row(gameName = "Harmonies", threeD = listOf(prose, first, space, second))

        // Found by one of its two tasks and by one of its two colours.
        assertTrue(GameTableFilter(query = SearchQuery("kırmızı ev"), colorIds = setOf(red.colorId)).matches(subject))

        // And it is still the same row: every piece, in order, spelling the same
        // document. The filter picked the row; it did not touch what is in it.
        val cell = subject.cell(CellColumnType.THREE_D)
        assertEquals(listOf(prose, first, space, second), cell.segments)
        assertEquals("Kutu ölçüsü 30×30 Kırmızı ev Gri zar", cell.text)
        assertEquals(cell.text, cell.runs.documentText())
        assertEquals(2, cell.segments.count { it.isTask })
    }
}
