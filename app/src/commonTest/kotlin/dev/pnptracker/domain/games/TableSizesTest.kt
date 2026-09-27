package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sizes the table is drawn at, and the document that remembers them.
 *
 * PLAN 12.17 makes the column widths and the row heights the user's own
 * measurements. What that section promises is arithmetic and a file format, so
 * all of it is decided here, with no screen and no disk: what a size means, what
 * it may not go below, what has no bound at all, and what happens to a file that
 * cannot be understood.
 */
class TableSizesTest {
    private val harmonies: EntityId = IdGenerator.Random.newId()
    private val wingspan: EntityId = IdGenerator.Random.newId()

    @Test
    fun `a table nobody has resized is the table as it has always been drawn`() {
        val sizes = TableSizes.Default

        assertEquals(DEFAULT_GAME_COLUMN_WIDTH_DP, sizes.widthOf(TableColumn.GAME_NAME))
        CellColumnType.entries.forEach { columnType ->
            assertEquals(
                DEFAULT_CELL_COLUMN_WIDTH_DP,
                sizes.widthOf(TableColumn.of(columnType)),
                "$columnType is not drawn at the width the table has always given a cell",
            )
        }
        assertNull(sizes.heightOf(harmonies), "a row nobody resized has a height of its own")
        assertTrue(sizes.isDefault)
        assertNull(sizes.problem)
    }

    @Test
    fun `a width the user chose is the width that is used, and only for that column`() {
        val sizes = TableSizes.Default.withColumn(TableColumn.THREE_D, 320f)

        assertEquals(320f, sizes.widthOf(TableColumn.THREE_D))
        assertEquals(DEFAULT_CELL_COLUMN_WIDTH_DP, sizes.widthOf(TableColumn.CARD), "another column moved with it")
        assertEquals(DEFAULT_GAME_COLUMN_WIDTH_DP, sizes.widthOf(TableColumn.GAME_NAME), "the name column moved with it")
        assertFalse(sizes.isDefault)
    }

    @Test
    fun `a column cannot be dragged below the width that keeps it readable`() {
        val sizes = TableSizes.Default.withColumn(TableColumn.CARD, 4f)

        assertEquals(MINIMUM_COLUMN_WIDTH_DP, sizes.widthOf(TableColumn.CARD))
    }

    @Test
    fun `a column has no largest width, because the table scrolls sideways`() {
        // PLAN 12.17: the bound belongs to the automatic width alone. Somebody
        // who wants one enormous Notes column is allowed one.
        val sizes = TableSizes.Default.withColumn(TableColumn.NOTES, 4_000f)

        assertEquals(4_000f, sizes.widthOf(TableColumn.NOTES))
    }

    @Test
    fun `a height belongs to the row it was given to and to no other`() {
        val sizes = TableSizes.Default.withRow(harmonies, 220f)

        assertEquals(220f, sizes.heightOf(harmonies))
        assertNull(sizes.heightOf(wingspan), "the height reached a row nobody resized")
    }

    @Test
    fun `a row cannot be dragged below the height the table has always given one`() {
        val sizes = TableSizes.Default.withRow(harmonies, 1f)

        assertEquals(MINIMUM_ROW_HEIGHT_DP, sizes.heightOf(harmonies))
    }

    @Test
    fun `a column put back where it started is forgotten rather than written down`() {
        val sizes = TableSizes.Default.withColumn(TableColumn.CARD, 320f).withColumn(TableColumn.CARD, DEFAULT_CELL_COLUMN_WIDTH_DP)

        assertTrue(sizes.isDefault, "the default was written down as if it were a choice")
        assertEquals(emptyMap(), sizes.columnWidths)
    }

    @Test
    fun `the rows of games that are no longer there are pruned`() {
        val sizes = TableSizes.Default.withRow(harmonies, 220f).withRow(wingspan, 180f)

        val pruned = sizes.prunedTo(setOf(harmonies))

        assertEquals(220f, pruned.heightOf(harmonies))
        assertNull(pruned.heightOf(wingspan), "a game that is gone left its height behind")
        assertEquals(1, pruned.rowHeights.size)
    }

    @Test
    fun `fitting a column to its content is held between both bounds`() {
        assertEquals(MINIMUM_COLUMN_WIDTH_DP, automaticWidthFor(2f), "a nearly empty column collapsed")
        assertEquals(AUTOMATIC_WIDTH_LIMIT_DP, automaticWidthFor(5_000f), "one long cell pushed the column off the screen")
        assertEquals(300f, automaticWidthFor(300f), "a reasonable measurement was not used as it was")
    }

    @Test
    fun `what is written reads back as the same sizes`() {
        val sizes =
            TableSizes.Default
                .withColumn(TableColumn.GAME_NAME, 300f)
                .withColumn(TableColumn.NOTES, 420f)
                .withRow(harmonies, 220f)

        val read = tableSizesIn(tableSizesDocumentFor(sizes))

        assertEquals(sizes, read)
        assertNull(read.problem)
    }

    @Test
    fun `a document that is not the one this writes leaves the defaults and says why`() {
        listOf(
            "" to TableSizesProblem.NOT_THE_EXPECTED_SHAPE,
            "not json at all" to TableSizesProblem.NOT_THE_EXPECTED_SHAPE,
            """{"formatVersion":99,"columnWidths":{},"rowHeights":{}}""" to TableSizesProblem.VERSION_NOT_SUPPORTED,
            """{"formatVersion":1,"columnWidths":{"NOT_A_COLUMN":200.0},"rowHeights":{}}""" to
                TableSizesProblem.NOT_THE_EXPECTED_SHAPE,
            """{"formatVersion":1,"columnWidths":{},"rowHeights":{"not-an-id":200.0}}""" to
                TableSizesProblem.NOT_THE_EXPECTED_SHAPE,
        ).forEach { (text, expected) ->
            val read = tableSizesIn(text)

            assertEquals(expected, read.problem, "the wrong reason for: $text")
            assertTrue(read.isDefault, "an unusable document changed what the table is drawn at: $text")
        }
    }

    @Test
    fun `a size in the file that is out of bounds is brought back inside it`() {
        // A hand edited file is left alone on disk (PLAN 12.17), and nothing
        // about leaving it alone means drawing a column four pixels wide.
        val read = tableSizesIn("""{"formatVersion":1,"columnWidths":{"CARD":1.0},"rowHeights":{"$harmonies":1.0}}""")

        assertEquals(MINIMUM_COLUMN_WIDTH_DP, read.widthOf(TableColumn.CARD))
        assertEquals(MINIMUM_ROW_HEIGHT_DP, read.heightOf(harmonies))
    }
}
