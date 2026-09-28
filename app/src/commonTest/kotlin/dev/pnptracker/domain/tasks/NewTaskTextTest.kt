package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which words count as a name typed a moment ago (PLAN 12.6). */
class NewTaskTextTest {
    private val game = IdGenerator.Random.newId()

    private fun typed(
        original: String,
        draft: String,
    ) = NewTaskText.typedInto(game, CellColumnType.THREE_D, original, draft)

    @Test
    fun `a name typed into an empty cell is the name`() {
        val text = typed("", "Ejderha")!!

        assertEquals("Ejderha", text.name)
        assertEquals(0, text.startOffset)
        assertEquals("Ejderha", text.newDocument)
        assertEquals("", text.expectedDocument)
    }

    @Test
    fun `a name on a new line after saved text leaves the saved text alone`() {
        val text = typed("Kule, sur", "Kule, sur\nEjderha")!!

        assertEquals("Ejderha", text.name)
        assertEquals("Kule, sur\n".length, text.startOffset)
        assertEquals("Kule, sur\nEjderha".length, text.endOffset)
    }

    @Test
    fun `a name put between saved words is found where it was put`() {
        val text = typed("Kule ve sur", "Kule ve Ejderha ve sur")!!

        assertEquals("Ejderha ve", text.name)
    }

    @Test
    fun `the spaces around a name are not part of it`() {
        assertEquals("Ejderha", typed("Kule", "Kule   Ejderha  ")!!.name)
    }

    @Test
    fun `nothing typed, only spaces, or something taken away is no new name`() {
        assertNull(typed("Kule", "Kule"))
        assertNull(typed("Kule", "Kule   "))
        assertNull(typed("Kule ve sur", "Kule sur Ejderha"))
        assertNull(typed("Kule ve sur", "Kule"))
    }

    @Test
    fun `a name running over two lines is no new name`() {
        assertNull(typed("", "Ejderha\nKule"))
    }
}
