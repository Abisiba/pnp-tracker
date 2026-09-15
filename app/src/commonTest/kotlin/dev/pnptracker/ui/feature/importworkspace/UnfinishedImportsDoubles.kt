package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.data.repository.UnfinishedImport
import dev.pnptracker.data.repository.UnfinishedImports
import dev.pnptracker.data.repository.UnfinishedImportsUnreadable
import dev.pnptracker.domain.importhealth.DraftContradiction
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** An unfinished import as the list reports it. */
fun anUnfinishedImport(
    batchId: EntityId,
    fileName: String = "liste.csv",
    isContradicting: Boolean = false,
    mayBeHeldByRecords: Boolean = false,
) = UnfinishedImport(batchId, fileName, "Sayfa1", isContradicting, mayBeHeldByRecords)

/**
 * A stand in for the list's storage that records what it was asked.
 *
 * It decides nothing: every answer is set by the test, which is the point — the
 * screen must ask, show what came back, and work nothing out of its own.
 */
class FakeUnfinishedImports(
    rows: List<UnfinishedImport> = emptyList(),
) : UnfinishedImports {
    val list = MutableStateFlow(rows)
    var listUnreadable = false

    /** What a fresh reading of each draft says; a draft not named here is sound. */
    val health = mutableMapOf<EntityId, DraftHealth>()
    var healthUnreadable = false
    var healthThrows: Throwable? = null

    var outcome: (EntityId) -> DraftRemovalOutcome = { batchId -> DraftRemovalOutcome.Removed(batchId, 3, 2, 4, 0) }
    var removeThrows: Throwable? = null

    /** Holds the next call open, so a second one can be tried while it is in flight. */
    var healthGate: CompletableDeferred<Unit>? = null
    var removeGate: CompletableDeferred<Unit>? = null

    var healthCalls = 0
        private set
    var removeCalls = 0
        private set

    override fun observeUnfinishedImports(): Flow<List<UnfinishedImport>> =
        if (listUnreadable) flow { throw UnfinishedImportsUnreadable(IllegalStateException("storage")) } else list

    override suspend fun healthOf(batchId: EntityId): DraftHealth {
        healthCalls += 1
        healthGate?.await()
        healthThrows?.let { throw it }
        if (healthUnreadable) throw UnfinishedImportsUnreadable(IllegalStateException("storage"))
        return health[batchId] ?: DraftHealth.Sound(batchId)
    }

    override suspend fun remove(batchId: EntityId): DraftRemovalOutcome {
        removeCalls += 1
        removeGate?.await()
        removeThrows?.let { throw it }
        return outcome(batchId)
    }

    companion object {
        fun contradicting(batchId: EntityId) = DraftHealth.Contradicting(batchId, setOf(DraftContradiction.RAW_CELL_COUNT_DISAGREES))
    }
}
