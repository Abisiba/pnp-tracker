package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo

/**
 * How much of the catalogue one colour is holding, counted in one read.
 *
 * Every number is a `COUNT(DISTINCT …)` because the query reaches through the
 * segment and the cell to find the game: a task written in two places would
 * otherwise be counted twice and the user would be shown a number that is not
 * true.
 */
data class ColorUsageCounts(
    @ColumnInfo(name = "task_count")
    val taskCount: Int,
    @ColumnInfo(name = "unfinished_task_count")
    val unfinishedTaskCount: Int,
    @ColumnInfo(name = "game_count")
    val gameCount: Int,
    @ColumnInfo(name = "last_color_task_count")
    val tasksLosingTheirLastColor: Int,
)

/** One task a colour is used by, with the game it is written in. */
data class ColorUsageSampleRow(
    @ColumnInfo(name = "task_name")
    val taskName: String,
    @ColumnInfo(name = "game_name")
    val gameName: String?,
)
