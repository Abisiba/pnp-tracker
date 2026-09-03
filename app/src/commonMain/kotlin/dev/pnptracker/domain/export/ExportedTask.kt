package dev.pnptracker.domain.export

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.PoolType

/**
 * The three words the `status` column may hold.
 *
 * These are values in a file rather than words on a screen, so they are fixed
 * here and not looked up in the string catalogue: a file whose contents changed
 * with the interface language would be a file nothing could read back.
 *
 * The order is the order they are decided in, and it is the same order PLAN 13's
 * filter uses (see [dev.pnptracker.domain.search.TaskStateFilter]): a task that
 * is still waiting on information is not finished work and not active work
 * either, whatever else is true of it.
 */
enum class TaskExportStatus(
    val word: String,
) {
    NEEDS_INFO("bilgi eksik"),
    COMPLETED("tamamlandı"),
    OPEN("açık"),
}

/**
 * One task, reduced to the eight things PLAN 11.8 writes about it.
 *
 * One task is one row, whatever it is made of. A task in three colours is not
 * three rows and a task with a three-stage pipeline is not three rows: PLAN 5.7
 * makes the task the thing, and the colours and the stages are things about it.
 *
 * [columnType] and [poolType] are both kept, and both are read from where they
 * are actually stored — the column from the cell the task's segment is in, the
 * pool from the task itself. They agree in every record this application writes,
 * and the export refuses to guess if one is ever found that disagrees.
 */
data class ExportedTask(
    val gameName: String,
    val columnType: CellColumnType,
    val taskName: String,
    val poolType: PoolType,
    /** In the user's own slot order (PLAN 5.10); empty when no colour was chosen. */
    val colorNames: List<String>,
    /** Null for a task whose count is still unknown (PLAN 11.7). */
    val requiredQuantity: Int?,
    val status: TaskExportStatus,
    /** Exactly as the user wrote it, or null when they wrote none. */
    val notes: String?,
)
