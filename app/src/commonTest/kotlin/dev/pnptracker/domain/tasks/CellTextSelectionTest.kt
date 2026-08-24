package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.games.CellPreview
import dev.pnptracker.domain.games.CellSegmentPreview
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Cutting a task's name out of somebody's own words.
 *
 * The rule every one of these is really about is the same: the three parts put
 * back together are the text that was there, to the character. Whatever the user
 * dragged over, nothing of theirs is lost, moved or invented.
 */
class CellTextSelectionTest {
    private fun splitOf(
        text: String,
        start: Int,
        end: Int,
    ) = splitForTaskName(text, start, end)

    private fun failureOf(
        text: String,
        start: Int,
        end: Int,
    ) = assertFailsWith<TaskFromTextException> { splitForTaskName(text, start, end) }.failure

    private fun assertKeepsEveryCharacter(
        text: String,
        split: SplitPlainText,
    ) = assertEquals(text, split.documentText, "the cut lost or invented characters")

    // ------------------------------------------------------ cutting the text

    @Test
    fun `a word in the middle leaves the text before it and the text after it`() {
        val text = "Basılacak: Knight, token ×14"
        val split = splitOf(text, text.indexOf("Knight"), text.indexOf("Knight") + "Knight".length)

        assertEquals("Basılacak: ", split.prefix)
        assertEquals("Knight", split.name)
        assertEquals(", token ×14", split.suffix)
        assertKeepsEveryCharacter(text, split)
    }

    @Test
    fun `a word at the start leaves nothing before it`() {
        val text = "Knight, token"
        val split = splitOf(text, 0, "Knight".length)

        assertEquals("", split.prefix)
        assertEquals("Knight", split.name)
        assertEquals(", token", split.suffix)
        assertKeepsEveryCharacter(text, split)
    }

    @Test
    fun `a word at the end leaves nothing after it`() {
        val text = "40 gri token"
        val split = splitOf(text, text.length - "token".length, text.length)

        assertEquals("40 gri ", split.prefix)
        assertEquals("token", split.name)
        assertEquals("", split.suffix)
        assertKeepsEveryCharacter(text, split)
    }

    @Test
    fun `selecting the whole piece leaves nothing on either side`() {
        val text = "Knight"
        val split = splitOf(text, 0, text.length)

        assertEquals("", split.prefix)
        assertEquals("Knight", split.name)
        assertEquals("", split.suffix)
        assertKeepsEveryCharacter(text, split)
    }

    @Test
    fun `whitespace at the edges of a selection stays in the text`() {
        // The user dragged a little wide. The spaces are theirs and belong to the
        // document; only the words become the task's name.
        val text = "40   token   ×14"
        val split = splitOf(text, text.indexOf("40") + 2, text.indexOf("×"))

        assertEquals("40   ", split.prefix)
        assertEquals("token", split.name)
        assertEquals("   ×14", split.suffix)
        assertKeepsEveryCharacter(text, split)
    }

    @Test
    fun `punctuation and line endings around the selection are kept exactly`() {
        val text = "Basılacak:\r\n  40 gri token;\n  26 ağaç."
        val split = splitOf(text, text.indexOf("token"), text.indexOf("token") + "token".length)

        assertEquals("token", split.name)
        assertEquals("Basılacak:\r\n  40 gri ", split.prefix)
        assertEquals(";\n  26 ağaç.", split.suffix)
        assertKeepsEveryCharacter(text, split)
    }

    @Test
    fun `several words can be one name as long as they stay on one line`() {
        val text = "40 kahverengi ağaç"
        val split = splitOf(text, text.indexOf("kahverengi"), text.length)

        assertEquals("kahverengi ağaç", split.name)
        assertKeepsEveryCharacter(text, split)
    }

    // ---------------------------------------------------------- what is refused

    @Test
    fun `a selection that picked nothing is refused`() {
        assertEquals(TaskFromTextFailure.INVALID_SELECTION, failureOf("40 token", 3, 3))
    }

    @Test
    fun `a selection of nothing but whitespace has no name in it`() {
        assertEquals(TaskFromTextFailure.TASK_NAME_EMPTY, failureOf("40   token", 2, 5))
    }

    @Test
    fun `a selection running across a line ending is refused`() {
        // Two lines of somebody's notes are not the name of one thing to make.
        val text = "40 gri token\n26 kahverengi ağaç"
        assertEquals(TaskFromTextFailure.SELECTION_CONTAINS_LINE_BREAK, failureOf(text, 3, text.length))
    }

    @Test
    fun `a line ending at the very edge of a selection is only whitespace`() {
        val text = "token\n26 ağaç"
        val split = splitOf(text, 0, 6)

        assertEquals("token", split.name)
        assertEquals("\n26 ağaç", split.suffix)
        assertKeepsEveryCharacter(text, split)
    }

    @Test
    fun `offsets outside the text are refused`() {
        assertEquals(TaskFromTextFailure.INVALID_SELECTION, failureOf("token", -1, 3))
        assertEquals(TaskFromTextFailure.INVALID_SELECTION, failureOf("token", 0, 99))
        assertEquals(TaskFromTextFailure.INVALID_SELECTION, failureOf("token", 4, 2))
    }

    @Test
    fun `a cut inside a surrogate pair is refused rather than made`() {
        // Half of a character is not a character. The emoji is two UTF-16 units,
        // and cutting between them would store a broken name nothing could mend.
        val text = "40 🧩 parça"
        val emoji = text.indexOf('\uD83E')

        assertEquals(TaskFromTextFailure.INVALID_SELECTION, failureOf(text, emoji + 1, text.length))
        assertEquals(TaskFromTextFailure.INVALID_SELECTION, failureOf(text, 0, emoji + 1))
    }

    @Test
    fun `a whole surrogate pair can be a name`() {
        val text = "40 🧩 parça"
        val emoji = text.indexOf('\uD83E')
        val split = splitOf(text, emoji, emoji + 2)

        assertEquals("🧩", split.name)
        assertKeepsEveryCharacter(text, split)
    }

    // ------------------------------------------- finding the piece to cut in

    private fun piece(
        text: String,
        isTask: Boolean = false,
    ) = CellSegmentPreview(
        segmentId = IdGenerator.Random.newId(),
        taskId = if (isTask) IdGenerator.Random.newId() else null,
        text = text,
    )

    private fun cellOf(vararg pieces: CellSegmentPreview) =
        CellPreview(
            columnType = CellColumnType.THREE_D,
            cellId = IdGenerator.Random.newId(),
            segments = pieces.toList(),
            holdsTasks = pieces.any { it.isTask },
        )

    private val gameId: EntityId = IdGenerator.Random.newId()

    @Test
    fun `a selection is anchored to the one piece that holds it`() {
        // The offsets arrive counted over the whole cell. What matters is that
        // they come back counted over the piece that will actually be cut.
        val first = piece("Basılacak: ")
        val second = piece("40 gri token")
        val cell = cellOf(first, second)
        val start = first.text.length + "40 ".length

        val selection = cell.locateSelection(gameId, start, start + "gri".length)

        val located = requireNotNull(selection)
        assertEquals(second.segmentId, located.segmentId)
        assertEquals("40 gri token", located.expectedText)
        assertEquals(3, located.startOffset)
        assertEquals(6, located.endOffset)
    }

    @Test
    fun `a selection spanning two pieces is not guessed at`() {
        val first = piece("Basılacak: ")
        val cell = cellOf(first, piece("40 gri token"))

        assertNull(
            cell.locateSelection(gameId, first.text.length - 2, first.text.length + 4),
            "a selection across two pieces was cut in one of them anyway",
        )
    }

    @Test
    fun `a selection landing on a task is not cut`() {
        val text = piece("Basılacak: ")
        val task = piece("Knight", isTask = true)
        val cell = cellOf(text, task)

        assertNull(
            cell.locateSelection(gameId, text.text.length, text.text.length + 3),
            "a task was treated as text to cut",
        )
    }

    @Test
    fun `a cell nobody has written in has nothing to select`() {
        val empty = CellPreview(columnType = CellColumnType.THREE_D)

        assertNull(empty.locateSelection(gameId, 0, 3))
    }
}
