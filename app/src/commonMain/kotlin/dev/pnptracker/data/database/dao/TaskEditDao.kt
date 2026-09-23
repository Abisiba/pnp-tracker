package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.HistoryEventEntity
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.projection.CellRunRow
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.requireAllowedTrackingMode
import dev.pnptracker.domain.rules.requireColorsAllowed
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
import dev.pnptracker.domain.tasks.TaskFlags
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Changing a task that already exists, and turning one back into text.
 *
 * Both are whole-task acts rather than field-by-field ones: a name, a colour, a
 * total and a note are saved together or not at all, because a screen shows them
 * together and a half-applied change is a state the user never asked for.
 *
 * Nothing here touches progress. Stages and events are read to find out whether
 * a change is allowed and are never written by an edit — PLAN 6.4 has the
 * counters and the finished mark move together, in the transactions that own
 * them, and an edit that quietly adjusted a stage would be inventing progress.
 */
@Dao
abstract class TaskEditDao {
    // ------------------------------------------------------------- reading

    /** The task, if it can still be worked on: not deleted, and its game alive. */
    @Query(
        """
        SELECT tasks.* FROM tasks
        INNER JOIN cell_segments ON cell_segments.task_id = tasks.id
        INNER JOIN game_cells ON game_cells.id = cell_segments.cell_id
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE tasks.id = :taskId
          AND tasks.deleted_at IS NULL AND games.deleted_at IS NULL
        """,
    )
    abstract suspend fun workableTaskById(taskId: EntityId): TaskEntity?

    @Query("SELECT * FROM cell_segments WHERE task_id = :taskId")
    abstract suspend fun segmentOfTask(taskId: EntityId): CellSegmentEntity?

    @Query("SELECT * FROM task_colors WHERE task_id = :taskId ORDER BY slot_index")
    abstract suspend fun colorsOfTask(taskId: EntityId): List<TaskColorEntity>

    /**
     * Every colour the catalogue holds, as identities and nothing else.
     *
     * One read whatever the task carries. A task made in several colours would
     * otherwise cost a query per colour on every save, and PLAN puts no ceiling
     * on how many colours a thing comes in. There is no filter to apply either:
     * PLAN 5.2 makes colour the one exception to the tombstone rule, so a
     * deleted colour is physically gone and every row here still exists.
     */
    @Query("SELECT id FROM colors")
    abstract suspend fun allColorIds(): List<EntityId>

    @Query("SELECT COALESCE(MAX(completed_quantity), 0) FROM task_stages WHERE task_id = :taskId")
    abstract suspend fun furthestStageOf(taskId: EntityId): Int

    @Query("SELECT COUNT(*) FROM task_stages WHERE task_id = :taskId")
    abstract suspend fun stageCountOf(taskId: EntityId): Int

    @Query(
        """
        SELECT cell_segments.id AS segment_id,
               cell_segments.order_index AS order_index,
               cell_segments.kind AS kind,
               cell_segments.text AS text,
               cell_segments.task_id AS task_id,
               tasks.name AS task_name
        FROM cell_segments
        LEFT JOIN tasks ON tasks.id = cell_segments.task_id
        WHERE cell_segments.cell_id = :cellId
        ORDER BY cell_segments.order_index
        """,
    )
    abstract suspend fun runsOfCell(cellId: EntityId): List<CellRunRow>

    // ------------------------------------------------------------- writing

    @Query(
        """
        UPDATE tasks SET name = :name, required_quantity = :requiredQuantity,
                         notes = :notes, tracking_mode = :trackingMode,
                         is_missing = :isMissing, is_borrowed = :isBorrowed,
                         needs_info = :needsInfo, needs_classification = :needsClassification,
                         updated_at = :updatedAt
        WHERE id = :taskId
        """,
    )
    protected abstract suspend fun writeTask(
        taskId: EntityId,
        name: String,
        requiredQuantity: Int?,
        notes: String?,
        trackingMode: TrackingMode,
        isMissing: Boolean,
        isBorrowed: Boolean,
        needsInfo: Boolean,
        needsClassification: Boolean,
        updatedAt: Instant,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTaskColor(taskColor: TaskColorEntity)

    @Query("DELETE FROM task_colors WHERE task_id = :taskId")
    protected abstract suspend fun removeColorsOfTask(taskId: EntityId): Int

    /**
     * Takes a task out of view without taking it out of the database.
     *
     * The same row and the same pair of columns [dev.pnptracker.data.database.dao.TaskDao.softDelete]
     * writes, and written from here because converting a task to text has to
     * happen in one transaction with the cell it rewrites. `updated_at` moves
     * with `deleted_at` because a deletion is the record's last change.
     */
    @Query(
        "UPDATE tasks SET deleted_at = :deletedAt, updated_at = :deletedAt " +
            "WHERE id = :taskId AND deleted_at IS NULL",
    )
    protected abstract suspend fun softDeleteTask(
        taskId: EntityId,
        deletedAt: Instant,
    ): Int

    /** The game a cell belongs to, for the history line the conversion writes. */
    @Query("SELECT game_id FROM game_cells WHERE id = :cellId")
    protected abstract suspend fun gameIdOfCell(cellId: EntityId): EntityId?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertHistoryEvent(event: HistoryEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSegment(segment: CellSegmentEntity)

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
     * Saves what the user changed about a task, all of it together.
     *
     * The name is the task's part of the cell's document, so changing it changes
     * what the cell reads as — deliberately, and by exactly the name. Nothing
     * around it moves: the punctuation and spacing on either side belong to their
     * own pieces and are not touched here.
     *
     * The name is trimmed at both ends, because a space either side of a word is
     * a slip rather than a decision, and everything inside it is left alone. The
     * note is not trimmed: PLAN 5.6 keeps a note the user's own words, and this
     * path is where they are written by hand.
     *
     * The colours are given as the whole list the task is to carry, in the
     * user's own order, and are written as that list: the old relations go and
     * the new ones are numbered `0…N-1` without gaps, inside this transaction.
     * PLAN 5.10 makes the slot the order and PLAN 12.7 draws the name split
     * across the colours in it, so reordering the list is a real change with a
     * visible result, and a list saved with gaps in its numbering would be a
     * task whose name could not be shared out.
     *
     * How **many** colours a task has may not cross between one and several.
     * PLAN 5.10 has both kinds and says nothing about carrying a task from one
     * to the other, so the answer would have to be invented: which colour a
     * several-colour task keeps, what happens to the counter of a single-colour
     * one gaining a second. A task with no colour at all gaining its first, and
     * a single-colour task losing its only one, are not that crossing — PLAN
     * 5.10 calls a colourless task an ordinary state and 5.9 produces one by
     * deleting a colour.
     *
     * The four import marks (PLAN 10 and 11.7) are saved with the rest. They say
     * something about the work rather than about its progress, so changing one
     * moves nothing else: `needs_info` in particular is only ever cleared by the
     * user saying so here, never as a side effect of a total being filled in.
     *
     * Nothing about the task's work is touched. The identity, the piece of the
     * cell that names it, its stages and its history all stay exactly as they
     * were: a change of colour is a change of what is to be made, not of what
     * has been done.
     *
     * A total may not fall below work already recorded. PLAN 6.4 and 7.2 keep
     * what is owed and what each stage has done within it, so a smaller total
     * would leave counters the history could not account for. A finished card or
     * board task whose pipeline is counted up to its old total is refused
     * outright: PLAN describes no reopening on a change of total, so inventing
     * one — or quietly moving the stage counts — would be writing a rule nobody
     * agreed to.
     *
     * @return true when something actually changed.
     * @throws TaskEditException with the case that stopped it; nothing written.
     * @throws IllegalArgumentException if the pool does not allow [trackingMode],
     *   which is a programming mistake rather than the user's.
     */
    @Transaction
    open suspend fun editTask(
        taskId: EntityId,
        name: String,
        colorIds: List<EntityId>,
        requiredQuantity: Int?,
        notes: String?,
        trackingMode: TrackingMode,
        flags: TaskFlags?,
        clock: Clock,
    ): Boolean {
        val task = workableTaskById(taskId) ?: refuse(TaskEditFailure.TASK_NOT_AVAILABLE)
        requireAllowedTrackingMode(task.poolType, trackingMode)

        val cleanName = name.trim()
        if (cleanName.isEmpty()) refuse(TaskEditFailure.TASK_NAME_EMPTY)
        if (cleanName.any { it == '\n' || it == '\r' }) refuse(TaskEditFailure.NAME_CONTAINS_LINE_BREAK)
        // PLAN 10 gives `Eksik` and `Ödünç Parçalar` a column each and a cell is
        // in one of them, so the pair together could not describe anything. An
        // edit that says nothing about the marks leaves them exactly as they are,
        // rather than clearing four values it never read.
        if (flags?.conflict == true) refuse(TaskEditFailure.MISSING_AND_BORROWED)
        val marks =
            flags ?: TaskFlags(
                isMissing = task.isMissing,
                isBorrowed = task.isBorrowed,
                needsInfo = task.needsInfo,
                needsClassification = task.needsClassification,
            )

        // Read in slot order, so what is compared against is the list as the
        // user last left it rather than whatever order the rows come back in.
        val current = colorsOfTask(taskId).map { it.colorId }
        val earlier = colorIds.indexOfFirst { id -> colorIds.indexOf(id) != colorIds.lastIndexOf(id) }
        if (earlier >= 0) {
            // Both ends of the clash, so a panel can say which two entries.
            refuse(
                TaskEditFailure.DUPLICATE_COLOR,
                row = colorIds.indexOfLast { it == colorIds[earlier] },
                conflictsWith = earlier,
            )
        }
        if ((current.size > 1) != (colorIds.size > 1)) refuse(TaskEditFailure.COLOR_COUNT_NOT_CHANGEABLE)
        val colorChanges = current != colorIds
        // Only a colour being *put on* a task is refused. One already stored —
        // an older record, a restored backup — is left exactly where it is, and
        // taking it off is allowed from any pool (PLAN 5.10).
        if (colorChanges) requireColorsAllowed(task.poolType, colorIds)
        if (colorChanges && colorIds.isNotEmpty()) {
            // One read for the whole list, compared in memory, and made before
            // anything is written: a task whose second colour had been deleted
            // must come out of this with the list it had, not with half of a new
            // one. Which entry it was travels with the refusal.
            val catalogue = allColorIds().toSet()
            colorIds.forEachIndexed { slot, id ->
                if (id !in catalogue) refuse(TaskEditFailure.COLOR_NOT_AVAILABLE, slot)
            }
        }

        if (requiredQuantity != null && requiredQuantity <= 0) refuse(TaskEditFailure.INVALID_REQUIRED_QUANTITY)
        if (requiredQuantity != null) {
            if (requiredQuantity < task.currentMissingQuantity) refuse(TaskEditFailure.QUANTITY_BELOW_PROGRESS)
            if (requiredQuantity < furthestStageOf(taskId)) refuse(TaskEditFailure.QUANTITY_BELOW_PROGRESS)
        }
        val changesTotal = requiredQuantity != task.requiredQuantity
        if (changesTotal && task.isCompleted && stageCountOf(taskId) > 0) {
            refuse(TaskEditFailure.QUANTITY_LOCKED_BY_COMPLETION)
        }
        // A finished task owes nothing, so a total it could no longer cover would
        // put the two out of step; PLAN 6.4 forbids exactly that.
        if (requiredQuantity == null && task.currentMissingQuantity > 0) {
            refuse(TaskEditFailure.QUANTITY_BELOW_PROGRESS)
        }

        val unchanged =
            task.name == cleanName &&
                task.requiredQuantity == requiredQuantity &&
                task.notes == notes &&
                task.trackingMode == trackingMode &&
                task.isMissing == marks.isMissing &&
                task.isBorrowed == marks.isBorrowed &&
                task.needsInfo == marks.needsInfo &&
                task.needsClassification == marks.needsClassification &&
                !colorChanges
        if (unchanged) return false

        val moment = clock.now()
        writeTask(
            taskId = taskId,
            name = cleanName,
            requiredQuantity = requiredQuantity,
            notes = notes,
            trackingMode = trackingMode,
            isMissing = marks.isMissing,
            isBorrowed = marks.isBorrowed,
            needsInfo = marks.needsInfo,
            needsClassification = marks.needsClassification,
            updatedAt = moment,
        )
        if (colorChanges) {
            // The whole list at once: taken away and put back numbered from zero
            // inside this transaction, so no reader ever sees a task halfway
            // between two colour lists, and none of them is ever left with a
            // gap in its slots.
            removeColorsOfTask(taskId)
            colorIds.forEachIndexed { slotIndex, id ->
                insertTaskColor(TaskColorEntity(taskId = taskId, colorId = id, slotIndex = slotIndex))
            }
        }
        segmentOfTask(taskId)?.let { touchCell(it.cellId, moment) }
        return true
    }

    /**
     * Turns a task back into the words it was made from.
     *
     * PLAN 12.8 and 5.2 are explicit about what the user gets: the words stay
     * exactly where they were as ordinary text, and they stop being a piece of
     * work. So the cell reads the same afterwards as it did before, to the
     * character, and the task leaves the pools, the table and the export.
     *
     * **What it no longer does is destroy the record.** Until version 7 this
     * deleted the task row and, with it, every shortage ever reported against
     * that task — which PLAN 5.12 says is history that is never deleted, and PLAN
     * 12.15 asks the history screen to show. So the task is soft deleted instead,
     * exactly as PLAN 5.2 has every other deletion work: the row stays, its
     * colours stay, its pipeline stays, its shortages stay, and `deleted_at` is
     * what takes it out of every active view. Physical removal is the separate
     * maintenance action the same section describes, and is not this.
     *
     * The conversion is its own history event rather than a deletion event. They
     * are different things to have done — one takes a piece of work out of sight,
     * the other says those words were never a piece of work — and the tombstone
     * here is the mechanism, not the act. The game is read before the piece of
     * the cell is taken away, because that piece is the only way back to it.
     *
     * The cell is left canonical: the freed name joins the text on either side of
     * it into one piece, and the reading order closes up behind it.
     *
     * No other task is touched.
     *
     * @return true when there was a task here to convert.
     * @throws TaskEditException if the task is gone; nothing is written.
     */
    @Transaction
    open suspend fun convertTaskToText(
        taskId: EntityId,
        clock: Clock,
        idGenerator: IdGenerator,
        historyEventId: EntityId = IdGenerator.Random.newId(),
    ): Boolean {
        val task = workableTaskById(taskId) ?: refuse(TaskEditFailure.TASK_NOT_AVAILABLE)
        val segment = segmentOfTask(taskId) ?: refuse(TaskEditFailure.TASK_NOT_AVAILABLE)
        val gameId = gameIdOfCell(segment.cellId) ?: refuse(TaskEditFailure.TASK_NOT_AVAILABLE)
        val moment = clock.now()

        val rows = runsOfCell(segment.cellId)
        // What the cell will say afterwards: the same string, with this task's
        // name now ordinary text among the words around it.
        val pieces =
            rows.map { row ->
                if (row.segmentId == segment.id) {
                    // Its own row goes with the task, so the freed words need a
                    // home: a neighbour's row, or a new one of their own.
                    Piece(existingId = null, text = task.name, isTask = false)
                } else if (row.kind == SegmentKind.TASK) {
                    Piece(row.segmentId, row.taskName.orEmpty(), isTask = true)
                } else {
                    Piece(row.segmentId, row.text.orEmpty(), isTask = false)
                }
            }
        val merged = mergeText(pieces)

        rows.forEachIndexed { index, row -> moveSegment(row.segmentId, -(index + 1)) }

        // The piece that named the task goes, because those words are now part of
        // the text around them. Nothing else of the task goes with it.
        deleteSegment(segment.id)
        softDeleteTask(taskId, moment)
        insertHistoryEvent(
            HistoryEventEntity(
                id = historyEventId,
                kind = HistoryEventKind.TASK_CONVERTED_TO_TEXT,
                occurredAt = moment,
                gameId = gameId,
                taskId = taskId,
            ),
        )

        val kept = merged.mapNotNull { it.existingId }.toSet()
        rows
            .filterNot { it.segmentId in kept || it.segmentId == segment.id }
            .forEach { deleteSegment(it.segmentId) }

        merged.forEachIndexed { orderIndex, piece ->
            when {
                piece.isTask -> moveSegment(requireNotNull(piece.existingId), orderIndex)
                piece.existingId != null -> writeSegment(piece.existingId, piece.text, orderIndex, moment)
                else ->
                    insertSegment(
                        CellSegmentEntity.plainText(
                            id = idGenerator.newId(),
                            cellId = segment.cellId,
                            orderIndex = orderIndex,
                            text = piece.text,
                            moment = moment,
                        ),
                    )
            }
        }
        touchCell(segment.cellId, moment)
        return true
    }

    /**
     * One piece of the cell as it will stand, before identities are settled.
     *
     * [existingId] is null once a piece has been merged out of an existing row —
     * the row it came from is gone and the text needs a home. The freed name is
     * such a piece: its own row is deleted along with the task, so the words go
     * into a neighbour's row or into a new one.
     */
    private data class Piece(
        val existingId: EntityId?,
        val text: String,
        val isTask: Boolean,
    )

    /** Joins neighbouring stretches of text, keeping the first row that can hold them. */
    private fun mergeText(pieces: List<Piece>): List<Piece> =
        pieces.fold(mutableListOf()) { merged, piece ->
            val previous = merged.lastOrNull()
            if (!piece.isTask && previous != null && !previous.isTask) {
                merged[merged.lastIndex] =
                    Piece(
                        existingId = previous.existingId ?: piece.existingId,
                        text = previous.text + piece.text,
                        isTask = false,
                    )
            } else {
                merged += piece
            }
            merged
        }

    private fun refuse(
        failure: TaskEditFailure,
        row: Int? = null,
        conflictsWith: Int? = null,
    ): Nothing = throw TaskEditException(failure, row, conflictsWith)
}
