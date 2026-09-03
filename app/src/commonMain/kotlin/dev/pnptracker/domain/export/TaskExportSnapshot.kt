package dev.pnptracker.domain.export

import dev.pnptracker.data.database.projection.ExportColorRow
import dev.pnptracker.data.database.projection.ExportTaskRow
import dev.pnptracker.domain.model.EntityId

/**
 * Turns one reading of the database into the tasks that will be written out.
 *
 * Pure, and the whole of the export's judgement. The rows arrive in the order
 * the file is written in — game, then column, then position in the cell, then
 * task identity — and that order is kept rather than re-derived, so the file and
 * the game table agree about what comes before what.
 *
 * Nothing here is guessed at. A record that breaks one of the guarantees the
 * rest of the application maintains stops the export where it stands: writing a
 * plausible row for it would put a task under a game it is not in, or in a pool
 * nobody chose, and a file like that is worse than no file at all.
 *
 * @throws TaskExportException with [ExportFailure.BROKEN_DATA] for a record that
 *   cannot be read, or [ExportFailure.NOTHING_TO_EXPORT] when there is nothing
 *   to write.
 */
fun exportedTasksOf(
    rows: List<ExportTaskRow>,
    colors: List<ExportColorRow>,
): List<ExportedTask> {
    // A task the user has hidden by deleting its game is not written out, and is
    // not an error either. Everything is dropped before anything is checked, its
    // colours included, so a deleted game's leftovers can never fail an export.
    val byTask = rows.filterNot { it.gameIsDeleted }.groupBy { it.taskId }
    val colorsByTask = colorNamesByTask(colors.filter { it.taskId in byTask })

    val exported =
        byTask.entries.map { (taskId, rowsOfTask) ->
            val row = rowsOfTask.first()
            if (rowsOfTask.size > 1) {
                throw broken(ExportInvariant.TASK_WITH_SEVERAL_SEGMENTS, taskId)
            }
            if (row.segmentId == null || row.gameName == null || row.columnType == null) {
                throw broken(ExportInvariant.TASK_WITHOUT_SEGMENT, taskId)
            }
            // The column and the pool are two facts, read from two places. They
            // agree in everything this application writes, and a record where
            // they do not is one nobody can file correctly.
            if (row.columnType.poolType != row.poolType) {
                throw broken(ExportInvariant.TASK_IN_THE_WRONG_CELL, taskId)
            }
            ExportedTask(
                gameName = row.gameName,
                columnType = row.columnType,
                taskName = row.taskName,
                poolType = row.poolType,
                colorNames = colorsByTask[taskId].orEmpty(),
                requiredQuantity = row.requiredQuantity,
                status = statusOf(needsInfo = row.needsInfo, isCompleted = row.isCompleted),
                notes = row.notes,
            )
        }

    // Asked after the reading rather than before it: "is there anything to
    // export" is a question about what came back, and asking the database
    // separately would be a second answer that could disagree with the first.
    if (exported.isEmpty()) throw TaskExportException(ExportFailure.NOTHING_TO_EXPORT)
    return exported
}

/**
 * Which of the three words a task's `status` is.
 *
 * The same precedence PLAN 13's filter uses, and for the same reason: a task
 * still waiting on information is neither finished nor ready to start. A mark
 * saying the user chose the pool by hand is not a kind of missing information
 * and does not reach this at all.
 */
fun statusOf(
    needsInfo: Boolean,
    isCompleted: Boolean,
): TaskExportStatus =
    when {
        needsInfo -> TaskExportStatus.NEEDS_INFO
        isCompleted -> TaskExportStatus.COMPLETED
        else -> TaskExportStatus.OPEN
    }

/**
 * Every task's colours, in slot order, checked on the way past.
 *
 * The read already orders by slot, so nothing is sorted here; what is done is
 * to make sure the slots really are `0…N-1` with nothing missing and nothing
 * twice (PLAN 5.10). Quietly renumbering them would hide the moment a colour
 * went missing, and quietly folding a repeat away would lose the fact that the
 * record disagrees with itself.
 */
private fun colorNamesByTask(colors: List<ExportColorRow>): Map<EntityId, List<String>> =
    colors.groupBy { it.taskId }.mapValues { (taskId, rows) ->
        // Asked first, because a colour attached twice is a more particular
        // fault than the numbering it also breaks.
        if (rows.map { it.colorId }.toSet().size != rows.size) {
            throw broken(ExportInvariant.DUPLICATE_TASK_COLOR, taskId)
        }
        if (rows.map { it.slotIndex } != rows.indices.toList()) {
            throw broken(ExportInvariant.BROKEN_COLOR_SLOTS, taskId)
        }
        rows.map { it.canonicalName }
    }

private fun broken(
    invariant: ExportInvariant,
    taskId: EntityId,
) = TaskExportException(ExportFailure.BROKEN_DATA, invariant, taskId.toString())
