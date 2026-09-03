package dev.pnptracker.domain.export

import dev.pnptracker.data.database.projection.ExportColorRow
import dev.pnptracker.data.database.projection.ExportTaskRow
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.search.TaskStateFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What one reading of the database becomes, and what it refuses to become.
 *
 * Everything the export decides is here, and it is all pure: rows in, tasks out.
 * The two halves worth watching are the ones nobody sees until they go wrong —
 * a record that breaks a guarantee, and a task the user thought they had deleted.
 */
class TaskExportSnapshotTest {
    private fun row(
        taskId: EntityId = IdGenerator.Random.newId(),
        taskName: String = "Kırmızı ev",
        poolType: PoolType = PoolType.THREE_D,
        requiredQuantity: Int? = 12,
        notes: String? = null,
        isCompleted: Boolean = false,
        needsInfo: Boolean = false,
        gameName: String? = "Harmonies",
        gameIsDeleted: Boolean = false,
        columnType: CellColumnType? = CellColumnType.THREE_D,
        segmentId: EntityId? = IdGenerator.Random.newId(),
    ) = ExportTaskRow(
        taskId = taskId,
        taskName = taskName,
        poolType = poolType,
        requiredQuantity = requiredQuantity,
        notes = notes,
        isCompleted = isCompleted,
        needsInfo = needsInfo,
        gameId = if (gameName == null) null else IdGenerator.Random.newId(),
        gameName = gameName,
        gameIsDeleted = gameIsDeleted,
        columnType = columnType,
        segmentId = segmentId,
    )

    private fun color(
        taskId: EntityId,
        slotIndex: Int,
        canonicalName: String,
        colorId: EntityId = IdGenerator.Random.newId(),
    ) = ExportColorRow(taskId = taskId, colorId = colorId, slotIndex = slotIndex, canonicalName = canonicalName)

    private fun exported(
        rows: List<ExportTaskRow>,
        colors: List<ExportColorRow> = emptyList(),
    ) = exportedTasksOf(rows, colors)

    private fun refusalOf(
        rows: List<ExportTaskRow>,
        colors: List<ExportColorRow> = emptyList(),
    ): TaskExportException = assertFailsWith { exportedTasksOf(rows, colors) }

    // ------------------------------------------------------------- the states

    @Test
    fun `an unfinished task is open`() {
        assertEquals(TaskExportStatus.OPEN, exported(listOf(row())).single().status)
    }

    @Test
    fun `a finished task is completed, and is still written out`() {
        val tasks = exported(listOf(row(isCompleted = true)))

        assertEquals(TaskExportStatus.COMPLETED, tasks.single().status)
    }

    @Test
    fun `a task waiting on information says so, finished or not`() {
        assertEquals(TaskExportStatus.NEEDS_INFO, exported(listOf(row(needsInfo = true))).single().status)
        assertEquals(
            TaskExportStatus.NEEDS_INFO,
            exported(listOf(row(needsInfo = true, isCompleted = true))).single().status,
            "a task nobody can start is not finished work",
        )
    }

    @Test
    fun `the file and PLAN 13's filter agree about every task`() {
        // The two are written separately and must not drift: a task the pool
        // screen calls `Tamamlandı` cannot be `açık` in the file.
        val pairs =
            listOf(
                (false to false) to TaskStateFilter.ACTIVE,
                (false to true) to TaskStateFilter.COMPLETED,
                (true to false) to TaskStateFilter.NEEDS_INFO,
                (true to true) to TaskStateFilter.NEEDS_INFO,
            )
        pairs.forEach { (flags, filterState) ->
            val (needsInfo, isCompleted) = flags
            val fileStatus = statusOf(needsInfo = needsInfo, isCompleted = isCompleted)
            val poolTask =
                PoolTask(
                    taskId = IdGenerator.Random.newId(),
                    segmentId = IdGenerator.Random.newId(),
                    cellId = IdGenerator.Random.newId(),
                    gameId = IdGenerator.Random.newId(),
                    gameName = "Harmonies",
                    name = "Kırmızı ev",
                    requiredQuantity = 12,
                    notes = null,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    primaryBatchCompleted = false,
                    currentMissingQuantity = 0,
                    needsInfo = needsInfo,
                    isCompleted = isCompleted,
                )
            val expected =
                when (TaskStateFilter.of(poolTask)) {
                    TaskStateFilter.ACTIVE -> TaskExportStatus.OPEN
                    TaskStateFilter.COMPLETED -> TaskExportStatus.COMPLETED
                    TaskStateFilter.NEEDS_INFO -> TaskExportStatus.NEEDS_INFO
                }
            assertEquals(expected, fileStatus, "needsInfo=$needsInfo isCompleted=$isCompleted")
            assertEquals(filterState, TaskStateFilter.of(poolTask))
        }
    }

    @Test
    fun `all three kinds of task are written out together`() {
        val tasks =
            exported(
                listOf(
                    row(taskName = "Açık"),
                    row(taskName = "Biten", isCompleted = true),
                    row(taskName = "Eksik bilgi", needsInfo = true),
                ),
            )

        assertEquals(
            listOf(TaskExportStatus.OPEN, TaskExportStatus.COMPLETED, TaskExportStatus.NEEDS_INFO),
            tasks.map { it.status },
        )
    }

    // ------------------------------------------------------------- the colours

    @Test
    fun `colours come out in the slots the user gave them`() {
        val taskId = IdGenerator.Random.newId()
        val colors =
            listOf(
                color(taskId, 0, "Kırmızı"),
                color(taskId, 1, "Sarı"),
                color(taskId, 2, "Siyah"),
            )

        val task = exported(listOf(row(taskId = taskId, taskName = "Yarasa")), colors).single()

        assertEquals(listOf("Kırmızı", "Sarı", "Siyah"), task.colorNames)
    }

    @Test
    fun `a task with no colour has none, and is not an error`() {
        assertEquals(emptyList(), exported(listOf(row())).single().colorNames)
    }

    @Test
    fun `the same colour attached twice stops the export`() {
        val taskId = IdGenerator.Random.newId()
        val colorId = IdGenerator.Random.newId()
        val colors = listOf(color(taskId, 0, "Kırmızı", colorId), color(taskId, 1, "Kırmızı", colorId))

        val refused = refusalOf(listOf(row(taskId = taskId)), colors)

        assertEquals(ExportFailure.BROKEN_DATA, refused.failure)
        assertEquals(ExportInvariant.DUPLICATE_TASK_COLOR, refused.invariant)
    }

    @Test
    fun `a gap in the slots stops the export rather than being renumbered`() {
        val taskId = IdGenerator.Random.newId()
        val colors = listOf(color(taskId, 0, "Kırmızı"), color(taskId, 2, "Siyah"))

        val refused = refusalOf(listOf(row(taskId = taskId)), colors)

        assertEquals(ExportInvariant.BROKEN_COLOR_SLOTS, refused.invariant)
    }

    @Test
    fun `two colours in one slot stop the export`() {
        val taskId = IdGenerator.Random.newId()
        val colors = listOf(color(taskId, 0, "Kırmızı"), color(taskId, 0, "Siyah"))

        assertEquals(ExportInvariant.BROKEN_COLOR_SLOTS, refusalOf(listOf(row(taskId = taskId)), colors).invariant)
    }

    // --------------------------------------------------------- what is hidden

    @Test
    fun `a task in a deleted game is not written out`() {
        val tasks = exported(listOf(row(taskName = "Görünür"), row(taskName = "Silinmiş oyunda", gameIsDeleted = true)))

        assertEquals(listOf("Görünür"), tasks.map { it.taskName })
    }

    @Test
    fun `a deleted game's broken leftovers cannot fail an export`() {
        val hidden = IdGenerator.Random.newId()
        val rows =
            listOf(
                row(taskName = "Görünür"),
                row(taskId = hidden, taskName = "Silinmiş", gameIsDeleted = true, segmentId = null, columnType = null),
            )
        val colors = listOf(color(hidden, 3, "Kırmızı"))

        assertEquals(listOf("Görünür"), exported(rows, colors).map { it.taskName })
    }

    // ----------------------------------------------------- what is refused

    @Test
    fun `a task that is in no cell stops the export`() {
        val refused = refusalOf(listOf(row(segmentId = null, gameName = null, columnType = null)))

        assertEquals(ExportFailure.BROKEN_DATA, refused.failure)
        assertEquals(ExportInvariant.TASK_WITHOUT_SEGMENT, refused.invariant)
    }

    @Test
    fun `a task written in two places at once stops the export`() {
        val taskId = IdGenerator.Random.newId()

        val refused = refusalOf(listOf(row(taskId = taskId), row(taskId = taskId, gameName = "Wingspan")))

        assertEquals(ExportInvariant.TASK_WITH_SEVERAL_SEGMENTS, refused.invariant)
    }

    @Test
    fun `a task in a column that does not feed its pool stops the export`() {
        val refused = refusalOf(listOf(row(poolType = PoolType.CARD, columnType = CellColumnType.THREE_D)))

        assertEquals(ExportInvariant.TASK_IN_THE_WRONG_CELL, refused.invariant)
    }

    @Test
    fun `a task in the notes column stops the export`() {
        val refused = refusalOf(listOf(row(columnType = CellColumnType.NOTES)))

        assertEquals(ExportInvariant.TASK_IN_THE_WRONG_CELL, refused.invariant)
    }

    @Test
    fun `a refusal carries nothing a user would be shown`() {
        val refused = refusalOf(listOf(row(segmentId = null, gameName = null, columnType = null)))

        val message = refused.message.orEmpty()
        listOf("Harmonies", "Kırmızı ev", "SELECT", "/home/").forEach { forbidden ->
            assertEquals(false, forbidden in message, "`$forbidden` was in a developer message")
        }
    }

    @Test
    fun `nothing to write is said plainly rather than written as an empty file`() {
        val refused = refusalOf(emptyList())

        assertEquals(ExportFailure.NOTHING_TO_EXPORT, refused.failure)
        assertEquals(null, refused.invariant)
    }

    @Test
    fun `a library of nothing but deleted games has nothing to write`() {
        assertEquals(
            ExportFailure.NOTHING_TO_EXPORT,
            refusalOf(listOf(row(gameIsDeleted = true))).failure,
        )
    }

    // ---------------------------------------------------------- the order

    @Test
    fun `the order the rows arrive in is the order they come out in`() {
        val rows =
            listOf(
                row(gameName = "Bir", taskName = "a"),
                row(gameName = "Bir", taskName = "b"),
                row(gameName = "İki", taskName = "c"),
            )

        assertEquals(listOf("a", "b", "c"), exported(rows).map { it.taskName })
    }

    @Test
    fun `the column and the pool are both kept, and neither is derived from the other`() {
        val task = exported(listOf(row(poolType = PoolType.CARD, columnType = CellColumnType.CARD))).single()

        assertEquals(CellColumnType.CARD, task.columnType)
        assertEquals(PoolType.CARD, task.poolType)
    }
}
