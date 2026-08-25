package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What a user is allowed to do to a cell's document by typing in it.
 *
 * One rule underneath all of these: the plain text is theirs and the tasks are
 * not. PLAN 5.5 makes a task piece atomic — it cannot be split like text, typed
 * over, or deleted through by the caret — and everything here is that rule seen
 * from a different keystroke.
 */
class CellDocumentTest {
    private fun plain(
        text: String,
        start: Int,
    ) = DocumentRun(segmentId = IdGenerator.Random.newId(), taskId = null, text = text, start = start)

    private fun task(
        name: String,
        start: Int,
    ) = DocumentRun(
        segmentId = IdGenerator.Random.newId(),
        taskId = IdGenerator.Random.newId(),
        text = name,
        start = start,
    )

    /** `Basılacak: ` + task `Knight` + `, token` */
    private val runs = listOf(plain("Basılacak: ", 0), task("Knight", 11), plain(", token", 17))
    private val document = runs.documentText()

    private fun planOf(after: String) = planDocumentChange(runs, document, after)

    private fun gapsOf(after: String): List<String> = assertIs<DocumentChange.Planned>(planOf(after)).plan.gapTexts

    private fun refusalOf(after: String) = assertIs<DocumentChange.Refused>(planOf(after)).reason

    @Test
    fun `the document is the runs laid end to end`() {
        assertEquals("Basılacak: Knight, token", document)
    }

    // ------------------------------------------------ what may be typed

    @Test
    fun `text before a task can be edited`() {
        assertEquals(listOf("Basılacak ve kesilecek: ", ", token"), gapsOf("Basılacak ve kesilecek: Knight, token"))
    }

    @Test
    fun `text after a task can be edited`() {
        assertEquals(listOf("Basılacak: ", ", token ×14"), gapsOf("Basılacak: Knight, token ×14"))
    }

    @Test
    fun `text can be added after a task that ends the document`() {
        // There is no piece there yet; the gap after the last task is still a
        // gap, and it is where those words belong.
        val trailing = listOf(plain("Basılacak: ", 0), task("Knight", 11))
        val change = planDocumentChange(trailing, "Basılacak: Knight", "Basılacak: Knight ve token")

        assertEquals(listOf("Basılacak: ", " ve token"), assertIs<DocumentChange.Planned>(change).plan.gapTexts)
    }

    @Test
    fun `text can be added before a task that starts the document`() {
        val leading = listOf(task("Knight", 0), plain(", token", 6))
        val change = planDocumentChange(leading, "Knight, token", "40 Knight, token")

        assertEquals(listOf("40 ", ", token"), assertIs<DocumentChange.Planned>(change).plan.gapTexts)
    }

    @Test
    fun `a stretch of text can be emptied without touching the task`() {
        assertEquals(listOf("", ", token"), gapsOf("Knight, token"))
    }

    @Test
    fun `punctuation and line endings are kept exactly`() {
        assertEquals(
            listOf("Basılacak:\r\n  ", ", token"),
            gapsOf("Basılacak:\r\n  Knight, token"),
        )
        assertEquals(
            listOf("Basılacak: ", ", token;\n  26 ağaç."),
            gapsOf("Basılacak: Knight, token;\n  26 ağaç."),
        )
    }

    @Test
    fun `a change that rewrites both sides of a task at once is refused`() {
        // One keystroke changes one place. A paste over a selection that spans
        // the task is a single change that covers it, and covering it is the one
        // thing that may not happen.
        assertEquals(
            DocumentEditRefusal.CROSSES_A_TASK,
            refusalOf("Kesilecek: Knight, 26 ağaç"),
        )
    }

    // ------------------------------------------------ what may not be typed

    @Test
    fun `a task's own characters cannot be typed over`() {
        assertEquals(DocumentEditRefusal.CROSSES_A_TASK, refusalOf("Basılacak: Knigh, token"))
        assertEquals(DocumentEditRefusal.CROSSES_A_TASK, refusalOf("Basılacak: Kn1ght, token"))
        assertEquals(DocumentEditRefusal.CROSSES_A_TASK, refusalOf("Basılacak: Knght, token"))
    }

    @Test
    fun `a character typed right after a task joins the text, not the task`() {
        // The caret is at the boundary. What is typed there is the user's own
        // word starting, and the task keeps the name it had.
        assertEquals(listOf("Basılacak: ", "t, token"), gapsOf("Basılacak: Knightt, token"))
    }

    @Test
    fun `backspace at the far edge of a task does not eat into it`() {
        // The caret sits just after the task; the character behind it is the
        // task's last one, and it is not the user's to delete.
        assertEquals(DocumentEditRefusal.CROSSES_A_TASK, refusalOf("Basılacak: Knigh, token"))
    }

    @Test
    fun `delete at the near edge of a task does not eat into it`() {
        assertEquals(DocumentEditRefusal.CROSSES_A_TASK, refusalOf("Basılacak: night, token"))
    }

    @Test
    fun `a selection that swallowed a task cannot be replaced`() {
        assertEquals(DocumentEditRefusal.CROSSES_A_TASK, refusalOf("Basılacak: hepsi silindi"))
        assertEquals(DocumentEditRefusal.CROSSES_A_TASK, refusalOf(""))
    }

    @Test
    fun `text can be typed between two tasks that had nothing between them`() {
        // The gap between them is empty, not absent. PLAN 5.5 gives a cell as
        // many stretches of text as it has room for, and this is one of them.
        val touching = listOf(task("Knight", 0), task("token", 6))
        val change = planDocumentChange(touching, "Knighttoken", "Knight token")

        assertEquals(listOf("", " ", ""), assertIs<DocumentChange.Planned>(change).plan.gapTexts)
    }

    // ------------------------------------------------ laying it out again

    @Test
    fun `the tasks keep their identities and their order through a change`() {
        val plan = assertIs<DocumentChange.Planned>(planOf("Kesilecek: Knight, token")).plan
        val after = runsFrom(runs, plan.gapTexts)

        assertEquals(runs.filter { it.isTask }.map { it.taskId }, after.filter { it.isTask }.map { it.taskId })
        assertEquals("Kesilecek: Knight, token", after.documentText())
    }

    @Test
    fun `an emptied stretch of text leaves no run behind`() {
        val plan = assertIs<DocumentChange.Planned>(planOf("Knight, token")).plan
        val after = runsFrom(runs, plan.gapTexts)

        assertEquals(listOf(true, false), after.map { it.isTask })
        assertEquals("Knight, token", after.documentText())
    }

    @Test
    fun `a stretch of text with no row of its own yet says so`() {
        val trailing = listOf(plain("Basılacak: ", 0), task("Knight", 11))
        val plan =
            assertIs<DocumentChange.Planned>(
                planDocumentChange(trailing, "Basılacak: Knight", "Basılacak: Knight ve token"),
            ).plan
        val after = runsFrom(trailing, plan.gapTexts)

        assertEquals(" ve token", after.last().text)
        assertNull(after.last().segmentId, "a run with no stored row claimed one")
    }

    @Test
    fun `every run knows where it starts`() {
        val after = runsFrom(runs, listOf("40 ", " ve token"))

        assertEquals(listOf(0, 3, 9), after.map { it.start })
        assertEquals("40 Knight ve token", after.documentText())
    }

    // --------------------------------------- what a selection may be anchored to

    private fun cellOf(vararg pieces: CellSegmentPreview) =
        CellPreview(
            columnType = dev.pnptracker.domain.model.CellColumnType.THREE_D,
            cellId = IdGenerator.Random.newId(),
            segments = pieces.toList(),
            holdsTasks = pieces.any { it.isTask },
        )

    private fun piece(
        text: String,
        isTask: Boolean = false,
    ) = CellSegmentPreview(
        segmentId = IdGenerator.Random.newId(),
        taskId = if (isTask) IdGenerator.Random.newId() else null,
        text = text,
    )

    private val gameId: EntityId = IdGenerator.Random.newId()

    @Test
    fun `a selection inside one stretch of text is anchored to it`() {
        val before = piece("Basılacak: ")
        val cell = cellOf(before, piece("Knight", isTask = true), piece(", 40 gri token"))
        val start = before.text.length + "Knight, 40 ".length

        val selection = cell.locateSelection(gameId, start, start + "gri".length)

        assertEquals(", 40 gri token", assertIs<dev.pnptracker.domain.tasks.CellTextSelection>(selection).expectedText)
        assertEquals(5, selection.startOffset)
        assertEquals(8, selection.endOffset)
    }

    @Test
    fun `a selection that runs over a task is not anchored anywhere`() {
        val before = piece("Basılacak: ")
        val cell = cellOf(before, piece("Knight", isTask = true), piece(", token"))

        assertNull(
            cell.locateSelection(gameId, before.text.length - 2, before.text.length + 8),
            "a selection covering a task was offered as a name",
        )
    }

    @Test
    fun `a selection of part of a task is not anchored anywhere`() {
        val before = piece("Basılacak: ")
        val cell = cellOf(before, piece("Knight", isTask = true), piece(", token"))

        assertNull(
            cell.locateSelection(gameId, before.text.length + 1, before.text.length + 4),
            "part of a task was offered as a name",
        )
    }
}
