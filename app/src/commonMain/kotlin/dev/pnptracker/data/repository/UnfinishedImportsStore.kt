package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageReadFailed
import dev.pnptracker.domain.importhealth.DraftContradiction
import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * One import the user has not confirmed, as the list of unfinished imports shows it.
 *
 * [isContradicting] and [mayBeHeldByRecords] are advisory: they describe the
 * database at the moment the list was read. Opening asks again, and a removal
 * decides inside its own transaction, so a row that has gone stale can at worst
 * show the wrong button — never do the wrong thing.
 */
data class UnfinishedImport(
    val batchId: EntityId,
    val fileName: String,
    val sheetName: String,
    /** PLAN 11.4.5: its records contradict each other, so it is shown apart and cannot be opened. */
    val isContradicting: Boolean,
    /**
     * A real task or game points at it, so a removal will be refused to protect them.
     *
     * Only ever true of a contradicting draft (D6, D7): the application's own
     * paths never write such a pointer to a draft.
     */
    val mayBeHeldByRecords: Boolean,
)

/** Storage would not answer a question about the unfinished imports; nothing was written. */
class UnfinishedImportsUnreadable(
    cause: Throwable,
) : Exception("The unfinished imports could not be read", cause)

/**
 * What the list of unfinished imports needs from storage, and nothing else.
 *
 * An interface for the same reason [ImportRollback] is one: the section can be
 * driven in a test without a database, and nothing above this line knows what a
 * Room entity looks like.
 */
interface UnfinishedImports {
    /**
     * Every draft import, newest first, each classified, kept fresh.
     *
     * @throws UnfinishedImportsUnreadable through the flow if storage refuses.
     */
    fun observeUnfinishedImports(): Flow<List<UnfinishedImport>>

    /**
     * What one import's records say right now, read afresh.
     *
     * Asked before a draft is opened, because the list it was chosen from may
     * describe a database that has moved on.
     *
     * @throws UnfinishedImportsUnreadable if storage refuses.
     */
    suspend fun healthOf(batchId: EntityId): DraftHealth

    /** Removes one unconfirmed import; see [ImportDraftRemoval.remove]. */
    suspend fun remove(batchId: EntityId): DraftRemovalOutcome
}

/**
 * The list of unfinished imports behind [UnfinishedImports].
 *
 * Classification is [ImportDao.healthOfDraftBatches] — the very pure function the
 * confirmation's two gates use, fed by three reads for the whole list — and it is
 * read again whenever any of the six tables the nine contradictions are about
 * changes. Room's own observed queries only notice the tables they name, and the
 * list depends on tasks and games as much as on the drafts, so the tracker is
 * asked about all six directly. Removing goes through [removal] and nowhere else.
 */
class UnfinishedImportsStore(
    private val database: AppDatabase,
    private val importDao: ImportDao,
    private val diagnostics: Diagnostics = Diagnostics.None,
    private val removal: ImportDraftRemoval = ImportDraftRemovalStore(importDao, diagnostics),
) : UnfinishedImports {
    override fun observeUnfinishedImports(): Flow<List<UnfinishedImport>> =
        database.invalidationTracker
            .createFlow(*TABLES_THE_CONTRADICTIONS_READ)
            .map {
                importDao.healthOfDraftBatches().map { (batch, health) ->
                    unfinishedImportOf(batch.id, batch.fileName, batch.sheetName, health)
                }
            }.catch { cause ->
                if (cause !is SQLiteException) throw cause
                diagnostics.recordSafely { storageReadFailed(DiagnosticArea.UNFINISHED_IMPORTS, cause) }
                throw UnfinishedImportsUnreadable(cause)
            }

    override suspend fun healthOf(batchId: EntityId): DraftHealth =
        try {
            importDao.draftHealthOf(batchId)
        } catch (cause: SQLiteException) {
            diagnostics.recordSafely { storageReadFailed(DiagnosticArea.UNFINISHED_IMPORTS, cause) }
            throw UnfinishedImportsUnreadable(cause)
        }

    override suspend fun remove(batchId: EntityId): DraftRemovalOutcome = removal.remove(batchId)

    private companion object {
        /** Every table a D1–D9 predicate reads (PLAN 11.4.5). */
        val TABLES_THE_CONTRADICTIONS_READ =
            arrayOf("import_batches", "raw_import_blocks", "draft_tasks", "import_batch_cells", "tasks", "games")
    }
}

private fun unfinishedImportOf(
    batchId: EntityId,
    fileName: String,
    sheetName: String,
    health: DraftHealth,
): UnfinishedImport {
    val contradictions = (health as? DraftHealth.Contradicting)?.contradictions.orEmpty()
    return UnfinishedImport(
        batchId = batchId,
        fileName = fileName,
        sheetName = sheetName,
        isContradicting = contradictions.isNotEmpty(),
        mayBeHeldByRecords =
            DraftContradiction.TASK_SOURCED_FROM_DRAFT in contradictions ||
                DraftContradiction.GAME_SOURCED_FROM_DRAFT in contradictions,
    )
}
