package dev.pnptracker.data.repository

import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.data.database.entity.TaskStageEntity
import dev.pnptracker.data.database.projection.CellColumnRow
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * A real [ImportDao] with a tally of how the target cells were asked about.
 *
 * The point of the batched lookup is that the cost of summarising a batch does
 * not grow with the number of drafts in it, and no assertion about the answer
 * can show that: the answer is the same either way. So this counts the calls
 * instead, and a test can say the targets were looked up together.
 *
 * Everything else is passed straight through. The eight members Room keeps
 * protected cannot be delegated from out here, but summarising never reaches
 * them; a test that does will be told so rather than quietly getting nothing.
 */
class CountingImportDao(
    private val real: ImportDao,
) : ImportDao() {
    /** How many times the whole batch of targets was looked up at once. */
    var activeCellColumnsCalls: Int = 0
        private set

    /** How many times a single target was looked up on its own. */
    var activeCellCountCalls: Int = 0
        private set

    override suspend fun activeCellColumns(cellIds: Collection<EntityId>): List<CellColumnRow> {
        activeCellColumnsCalls++
        return real.activeCellColumns(cellIds)
    }

    override suspend fun activeCellCount(cellId: EntityId): Int {
        activeCellCountCalls++
        return real.activeCellCount(cellId)
    }

    override suspend fun activeCellCount(): Int = real.activeCellCount()

    override suspend fun insertBatch(batch: ImportBatchEntity) = real.insertBatch(batch)

    override suspend fun insertRawBlock(block: RawImportBlockEntity) = real.insertRawBlock(block)

    override suspend fun draftBatches(): List<ImportBatchEntity> = real.draftBatches()

    override suspend fun batchById(id: EntityId): ImportBatchEntity? = real.batchById(id)

    override suspend fun allBatches(): List<ImportBatchEntity> = real.allBatches()

    override suspend fun batchesWithFingerprint(sha256: String): List<ImportBatchEntity> = real.batchesWithFingerprint(sha256)

    override suspend fun rawBlocksOfBatch(batchId: EntityId): List<RawImportBlockEntity> = real.rawBlocksOfBatch(batchId)

    override suspend fun unprocessedRawBlocksOfBatch(batchId: EntityId): List<RawImportBlockEntity> =
        real.unprocessedRawBlocksOfBatch(batchId)

    override suspend fun rawBlockById(id: EntityId): RawImportBlockEntity? = real.rawBlockById(id)

    override suspend fun draftTasksOfBlock(blockId: EntityId): List<DraftTaskEntity> = real.draftTasksOfBlock(blockId)

    override suspend fun draftTaskById(id: EntityId): DraftTaskEntity? = real.draftTaskById(id)

    override suspend fun draftTasksOfBatch(batchId: EntityId): List<DraftTaskEntity> = real.draftTasksOfBatch(batchId)

    override suspend fun unprocessedRawBlockCount(batchId: EntityId): Int = real.unprocessedRawBlockCount(batchId)

    override suspend fun columnTypeOfCell(cellId: EntityId): CellColumnType? = real.columnTypeOfCell(cellId)

    override suspend fun nextSegmentIndex(cellId: EntityId): Int = real.nextSegmentIndex(cellId)

    override fun observeBatch(id: EntityId): Flow<ImportBatchEntity?> = real.observeBatch(id)

    override fun observeRawBlocksOfBatch(batchId: EntityId): Flow<List<RawImportBlockEntity>> = real.observeRawBlocksOfBatch(batchId)

    override fun observeDraftTasksOfBatch(batchId: EntityId): Flow<List<DraftTaskEntity>> = real.observeDraftTasksOfBatch(batchId)

    override fun observeDraftBatches(): Flow<List<ImportBatchEntity>> = real.observeDraftBatches()

    override suspend fun markRawBlockProcessed(
        id: EntityId,
        isProcessed: Boolean,
        updatedAt: Instant,
    ): Int = real.markRawBlockProcessed(id, isProcessed, updatedAt)

    override suspend fun updateGameCompletionHint(
        id: EntityId,
        decision: HintDecision,
        updatedAt: Instant,
    ): Int = real.updateGameCompletionHint(id, decision, updatedAt)

    override suspend fun updateDraftCompletionHint(
        id: EntityId,
        decision: HintDecision,
        updatedAt: Instant,
    ): Int = real.updateDraftCompletionHint(id, decision, updatedAt)

    // Room keeps these to itself, so they cannot be handed on. Summarising never
    // asks for them; anything that does has outgrown this double.
    override suspend fun insertDraftTaskRow(draft: DraftTaskEntity): Unit = outOfReach("insertDraftTaskRow")

    override suspend fun updateDraftTaskRow(draft: DraftTaskEntity): Int = outOfReach("updateDraftTaskRow")

    override suspend fun deleteDraftBatchRow(id: EntityId): Int = outOfReach("deleteDraftBatchRow")

    override suspend fun insertSegment(segment: CellSegmentEntity): Unit = outOfReach("insertSegment")

    override suspend fun insertStage(stage: TaskStageEntity): Unit = outOfReach("insertStage")

    override suspend fun insertTask(task: TaskEntity): Unit = outOfReach("insertTask")

    override suspend fun updateDraftTargetRow(
        draftId: EntityId,
        targetCellId: EntityId?,
        poolType: PoolType?,
        trackingMode: TrackingMode?,
        updatedAt: Instant,
    ): Int = outOfReach("updateDraftTargetRow")

    override suspend fun setDraftMaterializedTask(
        draftId: EntityId,
        taskId: EntityId,
        updatedAt: Instant,
    ): Int = outOfReach("setDraftMaterializedTask")

    override suspend fun markBatchConfirmed(
        batchId: EntityId,
        createdTaskCount: Int,
        updatedAt: Instant,
    ): Int = outOfReach("markBatchConfirmed")

    private fun outOfReach(name: String): Nothing =
        throw UnsupportedOperationException("CountingImportDao cannot pass $name on; it is protected in ImportDao.")
}
