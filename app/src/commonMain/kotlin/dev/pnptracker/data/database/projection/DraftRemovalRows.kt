package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo

/**
 * Everything a removal of one draft needs to know about it, in one read.
 *
 * The first five numbers are the rows PLAN 11.4.5 lets a removal take: the
 * batch itself, its raw cells, their drafts, those drafts' colours, and the cell
 * snapshots a damaged draft could be carrying. The last two are the real
 * records that would stop it — asked for by name, so a refusal never has to be
 * worked out from the wording of a foreign key error.
 */
data class DraftRemovalFacts(
    @ColumnInfo(name = "batch_count")
    val batchCount: Int,
    @ColumnInfo(name = "raw_block_count")
    val rawBlockCount: Int,
    @ColumnInfo(name = "draft_task_count")
    val draftTaskCount: Int,
    @ColumnInfo(name = "draft_color_count")
    val draftColorCount: Int,
    @ColumnInfo(name = "cell_snapshot_count")
    val cellSnapshotCount: Int,
    @ColumnInfo(name = "holding_task_count")
    val holdingTaskCount: Int,
    @ColumnInfo(name = "holding_game_count")
    val holdingGameCount: Int,
) {
    /** Nothing of the batch is left: the row and every row that hangs from it. */
    val isEmpty: Boolean
        get() = batchCount == 0 && rawBlockCount == 0 && draftTaskCount == 0 && draftColorCount == 0 && cellSnapshotCount == 0
}

/**
 * How many rows each of the fifteen tables holds, in one read.
 *
 * Taken before and after a removal, so the transaction itself can say that the
 * only rows that left are the ones it counted as the draft's own, and that not
 * one row of any other table went with them.
 */
data class TableCounts(
    @ColumnInfo(name = "colors")
    val colors: Int,
    @ColumnInfo(name = "color_aliases")
    val colorAliases: Int,
    @ColumnInfo(name = "games")
    val games: Int,
    @ColumnInfo(name = "game_cells")
    val gameCells: Int,
    @ColumnInfo(name = "tasks")
    val tasks: Int,
    @ColumnInfo(name = "cell_segments")
    val cellSegments: Int,
    @ColumnInfo(name = "task_colors")
    val taskColors: Int,
    @ColumnInfo(name = "task_stages")
    val taskStages: Int,
    @ColumnInfo(name = "progress_events")
    val progressEvents: Int,
    @ColumnInfo(name = "history_events")
    val historyEvents: Int,
    @ColumnInfo(name = "import_batches")
    val importBatches: Int,
    @ColumnInfo(name = "raw_import_blocks")
    val rawImportBlocks: Int,
    @ColumnInfo(name = "draft_tasks")
    val draftTasks: Int,
    @ColumnInfo(name = "draft_task_colors")
    val draftTaskColors: Int,
    @ColumnInfo(name = "import_batch_cells")
    val importBatchCells: Int,
)
