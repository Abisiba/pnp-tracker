package dev.pnptracker.ui.feature.games

import dev.pnptracker.domain.tasks.CellPoolChoice
import dev.pnptracker.domain.tasks.TaskPoolGroup
import dev.pnptracker.domain.tasks.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the task read shapes are allowed to carry.
 *
 * These sets are pinned rather than merely searched, so adding a field has to be
 * a decision someone writes down instead of something that slips in. The screens
 * that once drew these live in the cells of the game table now, but the shapes
 * themselves are what every later step reads a task through.
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
    fun `the target and the pool are kept together`() {
        // The cell and the pool are one answer rather than two: CellPoolChoice
        // holds them so nothing can carry a pair the database would refuse.
        assertEquals(
            setOf("cellId", "columnType", "poolType", "trackingMode"),
            fieldNamesOf(CellPoolChoice::class.java),
        )
    }
}
