package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.projection.CellRunRow
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.games.DocumentChange
import dev.pnptracker.domain.games.DocumentEditRefusal
import dev.pnptracker.domain.games.DocumentRun
import dev.pnptracker.domain.games.planDocumentChange
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
 * The rules that span a whole cell live in [saveDocumentText] rather than in
 * the individual queries, and the queries that would let a caller break them are
 * not reachable from outside: writing a piece, moving one, deleting one and
 * opening a cell are all `protected`, so the only way to change a cell is the
 * one transaction that knows what a cell is allowed to look like afterwards.
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

    /**
     * One cell's pieces with the name of any task they stand for, in order.
     *
     * This is what the document is built from on the writing side. A piece
     * naming a task the user deleted comes back with no name, which is right:
     * nobody can see it, so it is not part of what the document says — but the
     * piece is still here, still a task piece, and still keeps its place.
     */
    @Query(
        """
        SELECT cell_segments.id AS segment_id,
               cell_segments.order_index AS order_index,
               cell_segments.kind AS kind,
               cell_segments.text AS text,
               cell_segments.task_id AS task_id,
               tasks.name AS task_name
        FROM cell_segments
        LEFT JOIN tasks ON tasks.id = cell_segments.task_id AND tasks.deleted_at IS NULL
        WHERE cell_segments.cell_id = :cellId
        ORDER BY cell_segments.order_index
        """,
    )
    abstract suspend fun runsOfCell(cellId: EntityId): List<CellRunRow>

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

    @Query(
        "UPDATE cell_segments SET text = :text, order_index = :orderIndex, updated_at = :updatedAt WHERE id = :segmentId",
    )
    protected abstract suspend fun writeSegment(
        segmentId: EntityId,
        text: String,
        orderIndex: Int,
        updatedAt: Instant,
    ): Int

    @Query("UPDATE cell_segments SET order_index = :orderIndex WHERE id = :segmentId")
    protected abstract suspend fun moveSegment(
        segmentId: EntityId,
        orderIndex: Int,
    ): Int

    @Query("UPDATE game_cells SET updated_at = :updatedAt WHERE id = :cellId")
    protected abstract suspend fun touchCell(
        cellId: EntityId,
        updatedAt: Instant,
    ): Int

    // -------------------------------------------------------- transactions

    /**
     * Makes a cell's document say exactly [newDocumentText].
     *
     * The document is the cell's pieces laid end to end: plain text as itself, a
     * task as its name (PLAN 5.5). What the user typed over is compared against
     * what is stored, and the region that actually differs has to fall inside a
     * stretch of plain text. A change that reached into a task is refused —
     * PLAN 5.5 makes a task piece atomic, and rewriting it as characters would
     * cost it the colours, pipeline and history that hang off its identity.
     *
     * Text is stored as it arrived: not trimmed, doubled spaces not collapsed,
     * line breaks not rewritten, punctuation not spaced out. Nothing is
     * normalised on the way in either — a carriage return that came with a paste
     * is stored, because dropping it would be the one lossy thing this does.
     *
     * Afterwards the cell is in its canonical shape without that having to be a
     * separate step: the change is planned as the text of each *gap* between
     * tasks, so neighbouring stretches of text cannot exist separately, an empty
     * gap writes no piece at all, and the reading order is `0..N-1` with nothing
     * missing. Tasks keep their identities and their places among the text.
     *
     * The cell is opened only if there is something to put in it, and a cleared
     * cell keeps its row: a draft or an import may be aiming at its identity, and
     * PLAN 11.4.1 has that identity be the game's column rather than its
     * contents.
     *
     * Saving what the cell already says does nothing at all: no write, no new
     * identity, and the clock is not read, so a repeated save cannot move a
     * timestamp.
     *
     * @param expectedDocumentText what the cell said when the editor opened.
     * @return true when this call changed something.
     * @throws CellTextException if the game or cell is gone, the cell has since
     *   changed, or the change reached a task; nothing is written in those cases.
     */
    @Transaction
    open suspend fun saveDocumentText(
        gameId: EntityId,
        columnType: CellColumnType,
        expectedDocumentText: String,
        newDocumentText: String,
        clock: Clock,
        idGenerator: IdGenerator,
    ): Boolean {
        if (activeGameCount(gameId) != 1) refuse(CellTextFailure.GAME_NOT_AVAILABLE)
        val cell = cellOfGame(gameId, columnType)

        if (cell == null) {
            // Nothing there yet. There is no document to have changed under the
            // user, so the only thing that can be stale is a claim that it said
            // something, and the only thing to write is text.
            if (expectedDocumentText.isNotEmpty()) refuse(CellTextFailure.STALE_DOCUMENT)
            if (newDocumentText.isEmpty()) return false
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
                    text = newDocumentText,
                    moment = moment,
                ),
            )
            return true
        }

        val rows = runsOfCell(cell.id)
        val runs = runsOf(rows)
        // Straight concatenation: the pieces carry their own spacing, so joining
        // them with anything would read back text the user never wrote.
        val stored = runs.joinToString(separator = "") { it.text }
        if (stored != expectedDocumentText) refuse(CellTextFailure.STALE_DOCUMENT)
        if (stored == newDocumentText) return false

        val plan =
            when (val change = planDocumentChange(runs, stored, newDocumentText)) {
                is DocumentChange.Planned -> change.plan
                is DocumentChange.Refused ->
                    refuse(
                        when (change.reason) {
                            DocumentEditRefusal.CROSSES_A_TASK -> CellTextFailure.CHANGE_CROSSES_A_TASK
                        },
                    )
            }

        val moment = clock.now()
        rewrite(cell.id, rows, plan.gapTexts, moment, idGenerator)
        touchCell(cell.id, moment)
        return true
    }

    /**
     * Lays the cell out again from the planned text of each gap.
     *
     * Every existing piece is lifted out of the way first. Renumbering in place
     * would collide with the unique index the moment one piece moved onto a
     * place another still held; the negative range is never a resting state and
     * exists only between the two halves of this transaction.
     *
     * A gap reuses the identity of the first piece of writing that was in it, so
     * text keeps its row through being edited, joined or split. Pieces no gap
     * kept are gone: an emptied stretch leaves no row behind.
     */
    private suspend fun rewrite(
        cellId: EntityId,
        rows: List<CellRunRow>,
        gapTexts: List<String>,
        moment: Instant,
        idGenerator: IdGenerator,
    ) {
        val tasks = rows.filter { it.kind == SegmentKind.TASK }
        val plains = rows.filter { it.kind == SegmentKind.PLAIN_TEXT }
        // The pieces of writing that were in each gap, in order, so a gap can
        // take back the identity of the first of them.
        val plainsByGap = plains.groupBy { row -> tasks.count { it.orderIndex < row.orderIndex } }

        rows.forEachIndexed { index, row -> moveSegment(row.segmentId, -(index + 1)) }

        val kept = mutableSetOf<EntityId>()
        val writes = mutableListOf<suspend (Int) -> Unit>()
        gapTexts.forEachIndexed { gap, text ->
            if (text.isNotEmpty()) {
                val existing = plainsByGap[gap]?.firstOrNull()
                if (existing == null) {
                    writes += { order ->
                        insertSegment(
                            CellSegmentEntity.plainText(
                                id = idGenerator.newId(),
                                cellId = cellId,
                                orderIndex = order,
                                text = text,
                                moment = moment,
                            ),
                        )
                    }
                } else {
                    kept += existing.segmentId
                    writes += { order -> writeSegment(existing.segmentId, text, order, moment) }
                }
            }
            tasks.getOrNull(gap)?.let { task ->
                kept += task.segmentId
                writes += { order -> moveSegment(task.segmentId, order) }
            }
        }

        rows.filterNot { it.segmentId in kept }.forEach { deleteSegment(it.segmentId) }
        writes.forEachIndexed { order, write -> write(order) }
    }

    /** The document as runs, with a deleted task's piece taking up no room. */
    private fun runsOf(rows: List<CellRunRow>): List<DocumentRun> {
        var at = 0
        return rows.map { row ->
            val text = if (row.kind == SegmentKind.TASK) row.taskName.orEmpty() else row.text.orEmpty()
            DocumentRun(
                segmentId = row.segmentId,
                taskId = row.taskId,
                text = text,
                start = at,
            ).also { at = it.end }
        }
    }

    private fun refuse(failure: CellTextFailure): Nothing = throw CellTextException(failure)
}
