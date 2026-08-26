package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.entity.TaskStageEntity
import dev.pnptracker.data.database.entity.stageRowsFor
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.SplitPlainText
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import dev.pnptracker.domain.tasks.splitForTaskName
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One piece of a cell as it will stand once the cut has been made.
 *
 * Planned in memory before anything is written, so the whole shape of the cell —
 * every position, every identity, every merge — is decided once and checked
 * once, instead of being discovered halfway through a sequence of writes.
 */
private sealed interface PlannedPiece {
    /** The row this piece already has, or null when it needs a new one. */
    val existingId: EntityId?
}

private data class PlannedText(
    override val existingId: EntityId?,
    val text: String,
) : PlannedPiece

private data class PlannedTask(
    override val existingId: EntityId?,
    val taskId: EntityId,
) : PlannedPiece

/**
 * Turning a stretch of a cell's own words into a task.
 *
 * The whole act is one transaction because none of its parts means anything
 * alone: a task nothing points at is unreachable, a segment naming no task
 * cannot be drawn, a card task with no pipeline is a pipeline half described,
 * and a cell whose pieces were renumbered but not rewritten is a document with
 * two pieces claiming the same place. Every guard is made inside the same
 * transaction as the writes, so nothing can be taken away between the check and
 * the write that relies on it.
 *
 * The reads are public and the writes are not. The only way to change a cell
 * from here is [createTasksFromSelection], which is the one place that knows what
 * a cell is allowed to look like afterwards.
 */
@Dao
abstract class TaskFromTextDao {
    // ------------------------------------------------------------- reading

    @Query("SELECT COUNT(*) FROM games WHERE id = :gameId AND deleted_at IS NULL")
    abstract suspend fun activeGameCount(gameId: EntityId): Int

    @Query("SELECT * FROM game_cells WHERE id = :cellId")
    abstract suspend fun cellById(cellId: EntityId): GameCellEntity?

    @Query("SELECT * FROM cell_segments WHERE id = :segmentId")
    abstract suspend fun segmentById(segmentId: EntityId): CellSegmentEntity?

    /** One cell's pieces, in reading order; everything stored, nothing filtered. */
    @Query("SELECT * FROM cell_segments WHERE cell_id = :cellId ORDER BY order_index")
    abstract suspend fun segmentsOfCell(cellId: EntityId): List<CellSegmentEntity>

    /** 1 while the colour is still in the catalogue; colour deletion is physical. */
    @Query("SELECT COUNT(*) FROM colors WHERE id = :colorId")
    abstract suspend fun colorCount(colorId: EntityId): Int

    // ------------------------------------------------------------- writing

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTaskColor(taskColor: TaskColorEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertStage(stage: TaskStageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSegment(segment: CellSegmentEntity)

    @Query("UPDATE cell_segments SET order_index = :orderIndex WHERE id = :segmentId")
    protected abstract suspend fun moveSegment(
        segmentId: EntityId,
        orderIndex: Int,
    ): Int

    @Query(
        "UPDATE cell_segments SET text = :text, order_index = :orderIndex, updated_at = :updatedAt WHERE id = :segmentId",
    )
    protected abstract suspend fun writeSegment(
        segmentId: EntityId,
        text: String,
        orderIndex: Int,
        updatedAt: Instant,
    ): Int

    @Query("DELETE FROM cell_segments WHERE id = :segmentId")
    protected abstract suspend fun deleteSegment(segmentId: EntityId): Int

    @Query("UPDATE game_cells SET updated_at = :updatedAt WHERE id = :cellId")
    protected abstract suspend fun touchCell(
        cellId: EntityId,
        updatedAt: Instant,
    ): Int

    // -------------------------------------------------------- the transaction

    /**
     * Cuts the selected words out of a cell and puts tasks in their place.
     *
     * One draft or several: the same act either way, and deliberately one
     * transaction rather than two code paths that could drift apart on what a
     * cell is allowed to look like afterwards.
     *
     * The document is not changed by this, only its shape. What the cell reads as
     * before and after is the same string to the character: PLAN 5.5 splits the
     * piece into the text before the selection, the tasks, and the text after it,
     * and writes no empty piece on either side. Nothing is trimmed into nowhere,
     * no space is invented between the parts — not even between two tasks, whose
     * names sit end to end in the document exactly as one name would — and the
     * `×14` the cell will show beside each task is drawn from its own
     * [TaskEntity.requiredQuantity] rather than written into anybody's words.
     *
     * Several drafts make several **independent** tasks (PLAN 12.7): one identity,
     * one colour, one quantity, one note, one pipeline and one place in the cell
     * each, in the order the drafts were given. Nothing is written that ties them
     * together — no group, no parent, no shared counter — so afterwards the
     * database cannot tell they were made in the same breath, and nothing about
     * one of them can reach another.
     *
     * Two drafts may not name the same colour. Folding them into one would make
     * fewer tasks than the user described and throw away a quantity they typed.
     *
     * A finished game is worked in like any other. PLAN 5.3 keeps it visible and
     * editable, and reopening it on a new task is not something this decides —
     * PLAN 12.9 gives the game's own state its own transaction.
     *
     * Everything is written at one [Instant], read once, so a task and the piece
     * standing for it cannot be created at two different moments of one act.
     *
     * @return the identities of the tasks that were created, in the order of
     *   [drafts].
     * @throws TaskFromTextException for any of the recognised refusals; nothing
     *   is written in those cases.
     * @throws IllegalArgumentException if a draft's tracking mode is not one the
     *   cell's pool allows, which is a programming mistake rather than a user's.
     */
    @Transaction
    open suspend fun createTasksFromSelection(
        selection: CellTextSelection,
        drafts: List<TaskDraft>,
        clock: Clock,
        idGenerator: IdGenerator,
    ): List<EntityId> {
        if (activeGameCount(selection.gameId) != 1) refuse(TaskFromTextFailure.GAME_NOT_AVAILABLE)

        val cell = cellById(selection.cellId) ?: refuse(TaskFromTextFailure.CELL_NOT_AVAILABLE)
        // A cell of some other game is as good as absent: the caller was aiming
        // at a row that is not the one holding this cell.
        if (cell.gameId != selection.gameId) refuse(TaskFromTextFailure.CELL_NOT_AVAILABLE)
        val poolType = cell.columnType.poolType ?: refuse(TaskFromTextFailure.CELL_DOES_NOT_HOLD_TASKS)

        val segment = segmentById(selection.segmentId) ?: refuse(TaskFromTextFailure.SEGMENT_NOT_AVAILABLE)
        if (segment.cellId != cell.id) refuse(TaskFromTextFailure.SEGMENT_NOT_AVAILABLE)
        if (segment.kind != SegmentKind.PLAIN_TEXT) refuse(TaskFromTextFailure.SEGMENT_IS_NOT_PLAIN_TEXT)
        val storedText = segment.text ?: refuse(TaskFromTextFailure.SEGMENT_IS_NOT_PLAIN_TEXT)
        // The proof the selection carried. Text edited since it was made would
        // put the offsets somewhere the user never pointed at.
        if (storedText != selection.expectedText) refuse(TaskFromTextFailure.STALE_TEXT_SELECTION)

        val split = splitForTaskName(storedText, selection.startOffset, selection.endOffset)
        if (drafts.isEmpty()) refuse(TaskFromTextFailure.NO_TASK_DESCRIBED)
        if (drafts.distinctBy { it.colorId }.size != drafts.size) refuse(TaskFromTextFailure.DUPLICATE_COLOR)
        // Every draft is checked before any of them is written: a batch that
        // failed on its third row after writing the first two would leave the
        // user with tasks they did not finish describing.
        drafts.forEach { draft ->
            if (colorCount(draft.colorId) != 1) refuse(TaskFromTextFailure.COLOR_NOT_AVAILABLE)
            if (draft.requiredQuantity <= 0) refuse(TaskFromTextFailure.INVALID_REQUIRED_QUANTITY)
        }

        val moment = clock.now()
        // Built before anything is written: the entity refuses a tracking mode
        // this pool does not allow, and that must reach the caller as the
        // programming mistake it is rather than as a saving problem.
        val tasks =
            drafts.map { draft ->
                TaskEntity(
                    id = idGenerator.newId(),
                    poolType = poolType,
                    trackingMode = draft.trackingMode,
                    name = split.name,
                    requiredQuantity = draft.requiredQuantity,
                    // Stored as the user left it. A note is their own words, and
                    // the spaces in it are theirs too.
                    notes = draft.notes,
                    createdAt = moment,
                    updatedAt = moment,
                    // Chosen out of the user's own text, so no imported cell is behind it.
                    sourceRawImportBlockId = null,
                )
            }

        val ordered = segmentsOfCell(cell.id)
        val plan = planOf(ordered, segment.id, split, tasks.map { it.id })

        // Every existing row is lifted out of the way first. Renumbering in place
        // would collide with the unique index the moment one piece moved onto a
        // place another still held; the negative range is never a resting state
        // and only exists between the two halves of this transaction.
        ordered.forEachIndexed { index, row -> moveSegment(row.id, -(index + 1)) }

        val kept = plan.mapNotNull { it.existingId }.toSet()
        ordered.filter { it.id !in kept }.forEach { deleteSegment(it.id) }

        // The task before the piece that names it: the segment's foreign key
        // would have nothing to point at the other way round.
        tasks.forEachIndexed { index, task ->
            insertTask(task)
            insertTaskColor(TaskColorEntity(taskId = task.id, colorId = drafts[index].colorId, slotIndex = 0))
            // PLAN 7.2 and 8 fix the stages by pool, so a card or board task gets
            // its whole pipeline here — its own rows, counting only its own work.
            // A pool without a pipeline gets no rows and needs no case.
            stageRowsFor(task.id, poolType, moment).forEach { insertStage(it) }
        }

        plan.forEachIndexed { orderIndex, piece ->
            when (piece) {
                is PlannedText ->
                    if (piece.existingId == null) {
                        insertSegment(
                            CellSegmentEntity.plainText(
                                id = idGenerator.newId(),
                                cellId = cell.id,
                                orderIndex = orderIndex,
                                text = piece.text,
                                moment = moment,
                            ),
                        )
                    } else {
                        writeSegment(piece.existingId, piece.text, orderIndex, moment)
                    }

                is PlannedTask ->
                    if (piece.existingId == null) {
                        insertSegment(
                            CellSegmentEntity.task(
                                id = idGenerator.newId(),
                                cellId = cell.id,
                                orderIndex = orderIndex,
                                taskId = piece.taskId,
                                moment = moment,
                            ),
                        )
                    } else {
                        moveSegment(piece.existingId, orderIndex)
                    }
            }
        }
        touchCell(cell.id, moment)
        return tasks.map { it.id }
    }

    /**
     * What the cell will be made of once the cut is made.
     *
     * The piece being cut becomes up to three parts: the words before the
     * selection, the tasks, and the words after it. An empty side is not written
     * at all — PLAN 5.5 — and the existing row is reused for whichever side there
     * is, so a piece of writing keeps its identity through being split.
     *
     * The tasks go in one after another with nothing between them. PLAN 5.5
     * writes no empty piece, and a space invented to separate two of them would
     * be a character the user never typed appearing in their own note; the room
     * the cell shows between them is drawn beside their counts (PLAN 12.7) and is
     * no part of the document.
     *
     * Afterwards adjacent stretches of text are merged, which is PLAN 5.5 and 16
     * both: a cell never accumulates needless pieces. That can only reduce the
     * number of rows, and it changes nothing about what the cell says, because
     * the merge is exact concatenation.
     */
    private fun planOf(
        ordered: List<CellSegmentEntity>,
        targetId: EntityId,
        split: SplitPlainText,
        taskIds: List<EntityId>,
    ): List<PlannedPiece> {
        val cut =
            ordered.flatMap { row ->
                if (row.id != targetId) {
                    listOf(existingPiece(row))
                } else {
                    buildList {
                        if (split.prefix.isNotEmpty()) add(PlannedText(row.id, split.prefix))
                        taskIds.forEach { add(PlannedTask(existingId = null, taskId = it)) }
                        if (split.suffix.isNotEmpty()) {
                            // The row goes to whichever side is there; when both
                            // are, the second side is a new piece of writing.
                            add(PlannedText(row.id.takeIf { split.prefix.isEmpty() }, split.suffix))
                        }
                    }
                }
            }
        return mergedText(cut)
    }

    private fun existingPiece(row: CellSegmentEntity): PlannedPiece =
        when (row.kind) {
            SegmentKind.PLAIN_TEXT -> PlannedText(row.id, requireNotNull(row.text))
            SegmentKind.TASK -> PlannedTask(row.id, requireNotNull(row.taskId))
        }

    /** Joins neighbouring stretches of text, keeping the first one's identity. */
    private fun mergedText(pieces: List<PlannedPiece>): List<PlannedPiece> =
        pieces.fold(mutableListOf()) { merged, piece ->
            val previous = merged.lastOrNull()
            if (piece is PlannedText && previous is PlannedText) {
                merged[merged.lastIndex] =
                    PlannedText(
                        existingId = previous.existingId ?: piece.existingId,
                        text = previous.text + piece.text,
                    )
            } else {
                merged += piece
            }
            merged
        }

    private fun refuse(failure: TaskFromTextFailure): Nothing = throw TaskFromTextException(failure)
}
