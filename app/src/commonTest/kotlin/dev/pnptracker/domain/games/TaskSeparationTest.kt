package dev.pnptracker.domain.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one rule behind the space an import leaves between two tasks.
 *
 * Kept as a function of its own, and tested on its own, because everything else
 * about it — which transaction writes it, what row it becomes — can only be
 * asked of a database, and this part cannot go wrong there in a way that shows
 * up as anything but a missing or doubled space in somebody's cell.
 */
class TaskSeparationTest {
    @Test
    fun `the separator is one ordinary space and nothing else`() {
        assertEquals(" ", TASK_SEPARATOR)
        assertEquals(1, TASK_SEPARATOR.length)
    }

    @Test
    fun `nothing at all needs no separator in front of it`() {
        assertFalse(taskNeedsSeparatorAfter(""), "a task was given a space to stand behind nothing")
    }

    @Test
    fun `writing that ends in a word needs one`() {
        assertTrue(taskNeedsSeparatorAfter("Kutu ölçüsü 30×30"))
        assertTrue(taskNeedsSeparatorAfter("Zar"))
        assertTrue(taskNeedsSeparatorAfter("Not:"))
        assertTrue(taskNeedsSeparatorAfter("bkz. şablon…"))
    }

    @Test
    fun `writing that already ends in a boundary needs none`() {
        listOf("Kutu ", "Not:\t", "Not:\n", "Not:\r\n", "Kutu ").forEach { text ->
            assertFalse(taskNeedsSeparatorAfter(text), "a second boundary was added after `$text`")
        }
    }

    @Test
    fun `a name of its own decides nothing but its last character`() {
        // The question is only ever about what comes *before* the task, so a name
        // full of spaces of its own changes nothing about the answer.
        assertTrue(taskNeedsSeparatorAfter("Kırmızı ev"))
        assertFalse(taskNeedsSeparatorAfter("Kırmızı ev "))
    }
}
