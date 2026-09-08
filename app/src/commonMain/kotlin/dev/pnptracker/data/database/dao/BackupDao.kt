package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.ColorAliasEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.entity.DraftTaskColorEntity
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.entity.HistoryEventEntity
import dev.pnptracker.data.database.entity.ImportBatchCellEntity
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.ProgressEventEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.entity.TaskStageEntity

/**
 * The fifteen reads a whole backup is built from.
 *
 * Fifteen, whatever the database holds. One read per table and none per row, so
 * a library of a thousand tasks costs what one task costs and PLAN 16's shape
 * rule holds; more to the point, a file assembled from many separate readings
 * would describe several different moments and belong to none of them.
 *
 * They all run inside one transaction, so what comes back is a single reading of
 * the database: a task finished while the backup is being taken is either in it
 * or not, and never half in — its row present but its history line missing, or
 * the other way round.
 *
 * Every query orders explicitly, by the key PLAN 14.4.2 names for its table. SQL
 * has no row order of its own worth relying on, and the format is required to be
 * the same bytes for the same data however the rows happen to sit on disk. Four
 * of the keys are not the table's primary key but its unique business key —
 * `cell_segments` by position in the cell, `task_colors` and `draft_task_colors`
 * by slot, `task_stages` by step. Each of those is backed by a unique index, so
 * each is still a total order and still settles every pair of rows; PLAN names
 * them because they are the orders those tables mean something in.
 *
 * Nothing here writes. A backup is a question, and a question that changed the
 * answer would be a strange thing to ask.
 */
@Dao
abstract class BackupDao {
    @Query("SELECT * FROM colors ORDER BY id")
    abstract suspend fun colors(): List<ColorEntity>

    @Query("SELECT * FROM color_aliases ORDER BY color_id, normalized_alias")
    abstract suspend fun colorAliases(): List<ColorAliasEntity>

    @Query("SELECT * FROM import_batches ORDER BY id")
    abstract suspend fun importBatches(): List<ImportBatchEntity>

    @Query("SELECT * FROM games ORDER BY id")
    abstract suspend fun games(): List<GameEntity>

    @Query("SELECT * FROM game_cells ORDER BY id")
    abstract suspend fun gameCells(): List<GameCellEntity>

    @Query("SELECT * FROM raw_import_blocks ORDER BY id")
    abstract suspend fun rawImportBlocks(): List<RawImportBlockEntity>

    @Query("SELECT * FROM tasks ORDER BY id")
    abstract suspend fun tasks(): List<TaskEntity>

    @Query("SELECT * FROM cell_segments ORDER BY cell_id, order_index")
    abstract suspend fun cellSegments(): List<CellSegmentEntity>

    @Query("SELECT * FROM task_colors ORDER BY task_id, slot_index")
    abstract suspend fun taskColors(): List<TaskColorEntity>

    @Query("SELECT * FROM task_stages ORDER BY task_id, order_index")
    abstract suspend fun taskStages(): List<TaskStageEntity>

    @Query("SELECT * FROM progress_events ORDER BY id")
    abstract suspend fun progressEvents(): List<ProgressEventEntity>

    @Query("SELECT * FROM history_events ORDER BY id")
    abstract suspend fun historyEvents(): List<HistoryEventEntity>

    @Query("SELECT * FROM import_batch_cells ORDER BY import_batch_id, cell_id")
    abstract suspend fun importBatchCells(): List<ImportBatchCellEntity>

    @Query("SELECT * FROM draft_tasks ORDER BY id")
    abstract suspend fun draftTasks(): List<DraftTaskEntity>

    @Query("SELECT * FROM draft_task_colors ORDER BY draft_task_id, slot_index")
    abstract suspend fun draftTaskColors(): List<DraftTaskColorEntity>

    /** All fifteen, from one reading of the database. */
    @Transaction
    open suspend fun snapshot(): DatabaseRows =
        DatabaseRows(
            colors = colors(),
            colorAliases = colorAliases(),
            importBatches = importBatches(),
            games = games(),
            gameCells = gameCells(),
            rawImportBlocks = rawImportBlocks(),
            tasks = tasks(),
            cellSegments = cellSegments(),
            taskColors = taskColors(),
            taskStages = taskStages(),
            progressEvents = progressEvents(),
            historyEvents = historyEvents(),
            importBatchCells = importBatchCells(),
            draftTasks = draftTasks(),
            draftTaskColors = draftTaskColors(),
        )
}

/**
 * Everything one transaction handed back, before anything has translated it.
 *
 * Immutable lists of immutable rows, so what a caller holds cannot change under
 * it while the document is being written; a later write to the database produces
 * new rows and leaves these alone.
 */
data class DatabaseRows(
    val colors: List<ColorEntity>,
    val colorAliases: List<ColorAliasEntity>,
    val importBatches: List<ImportBatchEntity>,
    val games: List<GameEntity>,
    val gameCells: List<GameCellEntity>,
    val rawImportBlocks: List<RawImportBlockEntity>,
    val tasks: List<TaskEntity>,
    val cellSegments: List<CellSegmentEntity>,
    val taskColors: List<TaskColorEntity>,
    val taskStages: List<TaskStageEntity>,
    val progressEvents: List<ProgressEventEntity>,
    val historyEvents: List<HistoryEventEntity>,
    val importBatchCells: List<ImportBatchCellEntity>,
    val draftTasks: List<DraftTaskEntity>,
    val draftTaskColors: List<DraftTaskColorEntity>,
)
