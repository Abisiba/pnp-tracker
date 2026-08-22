package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.tasks.TaskPoolGroup
import dev.pnptracker.domain.tasks.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the tasks section is allowed to carry.
 *
 * PLAN 6.4 says a task is finished because its pool's counters and events say so,
 * and none of those exist yet. Until they do, a completion field anywhere on this
 * path would be a second source of truth, and a screen that showed one would be
 * showing something nothing could keep right.
 *
 * These sets are pinned rather than merely searched, so adding a field here has
 * to be a decision someone writes down instead of something that slips in.
 */
class TaskSurfaceTest {
    /**
     * The fields the class was written with.
     *
     * The Compose compiler adds a `$stable` field of its own to every class it
     * sees; it is not part of what anyone declared, so it is left out here.
     */
    private fun fieldNamesOf(type: Class<*>): Set<String> =
        type.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()

    @Test
    fun `a task row carries what it is and nothing about how far along it is`() {
        assertEquals(
            setOf(
                "id",
                "cellId",
                "columnType",
                "poolType",
                "trackingMode",
                "name",
                "requiredQuantity",
                "notes",
                "isFromImport",
            ),
            fieldNamesOf(TaskSummary::class.java),
        )
    }

    @Test
    fun `a pool section carries its pool and its tasks`() {
        assertEquals(setOf("poolType", "tasks"), fieldNamesOf(TaskPoolGroup::class.java))
    }

    @Test
    fun `the form carries only what the user fills in`() {
        assertEquals(
            setOf("cellId", "name", "poolType", "trackingMode", "quantity", "notes"),
            fieldNamesOf(TaskComposer::class.java),
        )
    }

    @Test
    fun `the section state carries the list, the form and what did not save`() {
        assertEquals(setOf("tasks", "composer", "failure"), fieldNamesOf(GameTasksScreenState::class.java))
    }

    @Test
    fun `nothing on this path offers to finish a task`() {
        val words = listOf("complete", "completed", "done", "finish", "progress", "status")
        val surfaces =
            listOf(
                TaskSummary::class.java,
                TaskPoolGroup::class.java,
                TaskComposer::class.java,
                GameTasksScreenState::class.java,
                GameTasksController::class.java,
            )

        surfaces.forEach { type ->
            val named =
                (type.declaredFields.map { it.name } + type.declaredMethods.map { it.name })
                    .filterNot { it.startsWith("$") }
                    .filter { member -> words.any { word -> member.lowercase().contains(word) } }
            assertTrue(named.isEmpty(), "${type.simpleName} offers completion before there is anything to derive it from: $named")
        }
    }
}
