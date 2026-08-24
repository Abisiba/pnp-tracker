package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.EntityId

/**
 * One colour of one task, with the colour's own name and value already joined.
 *
 * Every colour of every task in the whole table arrives as a flat list of these
 * and is grouped by task in Kotlin. That is what keeps the table's cost fixed:
 * a read per task, or per cell, would turn a library of two hundred games into
 * hundreds of round trips to draw one screen.
 *
 * The rows come back in the order the user picked the colours in, so grouping
 * them preserves PLAN 5.10's slot order without anything having to sort.
 */
data class TaskColorRow(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "color_id")
    val colorId: EntityId,
    @ColumnInfo(name = "canonical_name")
    val canonicalName: String,
    @ColumnInfo(name = "hex")
    val hex: String,
)
