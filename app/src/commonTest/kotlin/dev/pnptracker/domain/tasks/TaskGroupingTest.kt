package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Splitting a game's tasks into the sections the screen draws. */
class TaskGroupingTest {
    private val cellId: EntityId = IdGenerator.Random.newId()

    private fun task(
        name: String,
        poolType: PoolType,
    ) = TaskSummary(
        id = IdGenerator.Random.newId(),
        cellId = cellId,
        columnType = CellColumnType.THREE_D,
        poolType = poolType,
        trackingMode =
            when (poolType) {
                PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
                PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
                PoolType.SPECIAL -> TrackingMode.CHECKLIST
            },
        name = name,
    )

    @Test
    fun `nothing at all makes no sections`() {
        assertEquals(emptyList(), groupByPool(emptyList()))
    }

    @Test
    fun `the sections come in the order the pools are listed in`() {
        val tasks =
            listOf(
                task("Ayraç", PoolType.SPECIAL),
                task("Olay kartı", PoolType.CARD),
                task("Gri token", PoolType.THREE_D),
                task("Ana tahta", PoolType.BOARD),
            )

        assertEquals(
            listOf(PoolType.THREE_D, PoolType.CARD, PoolType.BOARD, PoolType.SPECIAL),
            groupByPool(tasks).map { it.poolType },
        )
    }

    @Test
    fun `a pool with nothing in it is left out rather than drawn empty`() {
        val tasks = listOf(task("Gri token", PoolType.THREE_D), task("Olay kartı", PoolType.CARD))

        assertEquals(listOf(PoolType.THREE_D, PoolType.CARD), groupByPool(tasks).map { it.poolType })
    }

    @Test
    fun `every task lands in its own pool and nowhere else`() {
        val tasks =
            listOf(
                task("Gri token", PoolType.THREE_D),
                task("Olay kartı", PoolType.CARD),
                task("Mavi token", PoolType.THREE_D),
            )

        val groups = groupByPool(tasks).associateBy { it.poolType }
        assertEquals(listOf("Gri token", "Mavi token"), groups.getValue(PoolType.THREE_D).tasks.map { it.name })
        assertEquals(listOf("Olay kartı"), groups.getValue(PoolType.CARD).tasks.map { it.name })
        assertEquals(tasks.size, groups.values.sumOf { it.tasks.size }, "a task was dropped or counted twice")
    }

    @Test
    fun `the order tasks arrived in is kept inside a section`() {
        val tasks =
            listOf(
                task("Zeytin token", PoolType.THREE_D),
                task("Ada token", PoolType.THREE_D),
                task("Mavi token", PoolType.THREE_D),
            )

        assertEquals(
            listOf("Zeytin token", "Ada token", "Mavi token"),
            groupByPool(tasks).single().tasks.map { it.name },
            "the grouping reordered rows the query had already ordered",
        )
    }

    @Test
    fun `grouping the same tasks twice gives the same answer`() {
        val tasks = listOf(task("Ayraç", PoolType.SPECIAL), task("Gri token", PoolType.THREE_D))

        assertEquals(groupByPool(tasks), groupByPool(tasks))
    }
}
