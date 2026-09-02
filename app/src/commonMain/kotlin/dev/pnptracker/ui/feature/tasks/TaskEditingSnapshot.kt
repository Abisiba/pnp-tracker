package dev.pnptracker.ui.feature.tasks

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.TrackingMode

/**
 * Everything the task editor needs to open over one task, read from wherever the
 * task is being shown.
 *
 * A task is one record and is shown in two places — inside its cell in the game
 * table, and in the pool of the work it belongs to. PLAN 12.10 makes the second
 * a reflection of the first, so opening a task from either has to reach the same
 * record and offer the same form. This is what both sides hand the editor, so
 * there is one form and one transaction rather than two that could drift apart.
 *
 * Read-only and taken at the moment the menu opens. It carries the cell and the
 * piece as well as the task, because a name is part of the cell's document and
 * changing it rewrites that document — which the editing transaction needs to
 * know even when the change was started from a pool screen that shows no cells.
 *
 * Everything here comes out of the bulk read the screen already made. Fetching
 * it when a card is clicked would be a read per task, which is what a pool's
 * queries are shaped to avoid.
 */
data class TaskEditingSnapshot(
    val taskId: EntityId,
    val segmentId: EntityId,
    val cellId: EntityId,
    val gameId: EntityId,
    val columnType: CellColumnType,
    val name: String,
    /** Every colour the task is made in, in the user's own order (PLAN 5.10). */
    val colorIds: List<EntityId>,
    val requiredQuantity: Int?,
    val notes: String?,
    val trackingMode: TrackingMode,
    /** PLAN 10 and 11.7: the four marks the task carries, so the form can show them. */
    val isMissing: Boolean = false,
    val isBorrowed: Boolean = false,
    val needsInfo: Boolean = false,
    val needsClassification: Boolean = false,
)
