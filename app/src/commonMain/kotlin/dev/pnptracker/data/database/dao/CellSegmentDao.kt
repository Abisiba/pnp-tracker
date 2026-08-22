package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the pieces a cell is made of.
 *
 * Everything here reads or writes single rows. The rules that span a whole cell —
 * splitting a piece of text around a new task, merging two pieces of text that
 * end up side by side — belong to the transactions that own the whole document,
 * not to individual queries.
 */
@Dao
interface CellSegmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(segment: CellSegmentEntity)

    @Query("SELECT * FROM cell_segments WHERE cell_id = :cellId ORDER BY order_index")
    suspend fun segmentsOfCell(cellId: EntityId): List<CellSegmentEntity>

    @Query("SELECT * FROM cell_segments WHERE cell_id = :cellId ORDER BY order_index")
    fun observeSegmentsOfCell(cellId: EntityId): Flow<List<CellSegmentEntity>>

    @Query("SELECT * FROM cell_segments WHERE task_id = :taskId")
    suspend fun segmentOfTask(taskId: EntityId): CellSegmentEntity?

    /** Where the next piece of a cell goes: after everything already in it. */
    @Query("SELECT COALESCE(MAX(order_index), -1) + 1 FROM cell_segments WHERE cell_id = :cellId")
    suspend fun nextOrderIndex(cellId: EntityId): Int

    @Query("SELECT COUNT(*) FROM cell_segments WHERE cell_id = :cellId")
    suspend fun segmentCountOfCell(cellId: EntityId): Int
}
