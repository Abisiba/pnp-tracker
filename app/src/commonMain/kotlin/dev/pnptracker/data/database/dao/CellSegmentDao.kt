package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.SegmentKind
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reads and writes the pieces a cell is made of.
 *
 * The rules that span a whole cell live in [savePlainText] rather than in the
 * individual queries, and the queries that would let a caller break them are not
 * reachable from outside: writing a piece, deleting one and opening a cell are
 * all `protected`, so the only way to change a cell is the one transaction that
 * knows what a cell is allowed to look like afterwards.
 */
@Dao
abstract class CellSegmentDao {
    // ------------------------------------------------------------- reading

    /**
     * One cell's pieces, in reading order.
     *
     * Everything in the cell, including a piece naming a task the user deleted:
     * this is what is *stored*, and the guards below have to see all of it. What
     * the table shows is a different question, answered by the table's own read.
     */
    @Query("SELECT * FROM cell_segments WHERE cell_id = :cellId ORDER BY order_index")
    abstract suspend fun segmentsOfCell(cellId: EntityId): List<CellSegmentEntity>

    /**
     * One cell's pieces, with any naming a deleted task left out.
     *
     * Named for what it leaves out rather than left to be assumed. A piece whose
     * task has been deleted must never become editable text: the task is gone
     * from every active view, and turning its piece into words would put a
     * deleted task's name back into the document as something the user typed.
     */
    @Query(
        """
        SELECT cell_segments.* FROM cell_segments
        LEFT JOIN tasks ON tasks.id = cell_segments.task_id
        WHERE cell_segments.cell_id = :cellId
          AND (cell_segments.task_id IS NULL OR tasks.deleted_at IS NULL)
        ORDER BY cell_segments.order_index
        """,
    )
    abstract suspend fun segmentsOfCellWithoutDeletedTasks(cellId: EntityId): List<CellSegmentEntity>

    /** One cell's pieces as they change, in reading order. */
    @Query("SELECT * FROM cell_segments WHERE cell_id = :cellId ORDER BY order_index")
    abstract fun observeSegmentsOfCell(cellId: EntityId): Flow<List<CellSegmentEntity>>

    /**
     * The piece standing for one task, whether or not the task is still active.
     *
     * A deleted task keeps its piece — deletion is soft — so this answers where
     * the task sits rather than whether it should be drawn.
     */
    @Query("SELECT * FROM cell_segments WHERE task_id = :taskId")
    abstract suspend fun segmentOfTaskIncludingDeleted(taskId: EntityId): CellSegmentEntity?

    /** The piece standing for one task, only while the task is still active. */
    @Query(
        """
        SELECT cell_segments.* FROM cell_segments
        INNER JOIN tasks ON tasks.id = cell_segments.task_id
        WHERE cell_segments.task_id = :taskId AND tasks.deleted_at IS NULL
        """,
    )
    abstract suspend fun activeSegmentOfTask(taskId: EntityId): CellSegmentEntity?

    /** Where the next piece of a cell goes: after everything already in it. */
    @Query("SELECT COALESCE(MAX(order_index), -1) + 1 FROM cell_segments WHERE cell_id = :cellId")
    abstract suspend fun nextOrderIndex(cellId: EntityId): Int

    @Query("SELECT COUNT(*) FROM cell_segments WHERE cell_id = :cellId")
    abstract suspend fun segmentCountOfCell(cellId: EntityId): Int

    @Query("SELECT COUNT(*) FROM games WHERE id = :gameId AND deleted_at IS NULL")
    abstract suspend fun activeGameCount(gameId: EntityId): Int

    @Query("SELECT * FROM game_cells WHERE game_id = :gameId AND column_type = :columnType")
    abstract suspend fun cellOfGame(
        gameId: EntityId,
        columnType: CellColumnType,
    ): GameCellEntity?

    // ------------------------------------------------------------- writing

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSegment(segment: CellSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCell(cell: GameCellEntity)

    @Query("DELETE FROM cell_segments WHERE id = :segmentId")
    protected abstract suspend fun deleteSegment(segmentId: EntityId): Int

    @Query("UPDATE cell_segments SET text = :text, order_index = 0, updated_at = :updatedAt WHERE id = :segmentId")
    protected abstract suspend fun writeSegmentText(
        segmentId: EntityId,
        text: String,
        updatedAt: Instant,
    ): Int

    @Query("UPDATE game_cells SET updated_at = :updatedAt WHERE id = :cellId")
    protected abstract suspend fun touchCell(
        cellId: EntityId,
        updatedAt: Instant,
    ): Int

    // -------------------------------------------------------- transactions

    /**
     * Makes a cell say exactly [exactText], and nothing else.
     *
     * The text is stored as it arrived. It is not trimmed, its doubled spaces are
     * not collapsed, its line breaks are not rewritten and its punctuation is not
     * spaced out: a cell is the user's own note, and every character in it is
     * theirs. Nor is anything normalised on the way in — a carriage return that
     * came with a paste is stored, because dropping it would be the one lossy
     * thing this method did.
     *
     * The cell is opened only if there is something to put in it. Reading the
     * table shows five columns for every game whether or not their cells exist,
     * so a cell created merely by being looked at, or by an empty save, would be
     * a row saying something nobody said. When the cell already exists and the
     * text is cleared, the pieces go and **the cell stays**: a draft or an import
     * may be aiming at its identity, and PLAN 11.4.1 has that identity be the
     * game's column rather than its contents.
     *
     * Afterwards the cell holds at most one piece of text. PLAN 5.5 and 16 both
     * say adjacent text is merged and no cell accumulates needless pieces, so a
     * cell that arrives with several is left with one — keeping the first piece's
     * identity, since it is the same piece of writing whatever has been typed
     * into it since.
     *
     * A cell holding a task is refused rather than flattened. There is no whole
     * text form of it that could be written back without destroying the task.
     *
     * Saving what the cell already says does nothing at all: no write, no new
     * identity, and the clock is not even read, so a repeated save cannot move a
     * timestamp.
     *
     * @return true when this call changed something.
     * @throws CellTextException if the game is gone or the cell holds a task.
     */
    @Transaction
    open suspend fun savePlainText(
        gameId: EntityId,
        columnType: CellColumnType,
        exactText: String,
        clock: Clock,
        idGenerator: IdGenerator,
    ): Boolean {
        if (activeGameCount(gameId) != 1) refuse(CellTextFailure.GAME_NOT_AVAILABLE)
        val cell = cellOfGame(gameId, columnType)

        if (cell == null) {
            // Nothing there and nothing to put there: the column stays unopened.
            if (exactText.isEmpty()) return false
            val moment = clock.now()
            val cellId = idGenerator.newId()
            // The cell and its first piece are written together, so a failure
            // partway cannot leave an empty cell nobody asked for.
            insertCell(
                GameCellEntity(
                    id = cellId,
                    gameId = gameId,
                    columnType = columnType,
                    createdAt = moment,
                    updatedAt = moment,
                ),
            )
            insertSegment(
                CellSegmentEntity.plainText(
                    id = idGenerator.newId(),
                    cellId = cellId,
                    orderIndex = 0,
                    text = exactText,
                    moment = moment,
                ),
            )
            return true
        }

        val segments = segmentsOfCell(cell.id)
        if (segments.any { it.kind == SegmentKind.TASK }) refuse(CellTextFailure.CELL_CONTAINS_TASKS)
        // Straight concatenation: the pieces carry their own spacing, so joining
        // them with anything would read back text the user never wrote.
        val current = segments.joinToString(separator = "") { it.text.orEmpty() }
        if (current == exactText) return false

        val moment = clock.now()
        if (exactText.isEmpty()) {
            segments.forEach { deleteSegment(it.id) }
        } else {
            val kept = segments.firstOrNull()
            if (kept == null) {
                insertSegment(
                    CellSegmentEntity.plainText(
                        id = idGenerator.newId(),
                        cellId = cell.id,
                        orderIndex = 0,
                        text = exactText,
                        moment = moment,
                    ),
                )
            } else {
                writeSegmentText(kept.id, exactText, moment)
                segments.drop(1).forEach { deleteSegment(it.id) }
            }
        }
        touchCell(cell.id, moment)
        return true
    }

    private fun refuse(failure: CellTextFailure): Nothing = throw CellTextException(failure)
}
