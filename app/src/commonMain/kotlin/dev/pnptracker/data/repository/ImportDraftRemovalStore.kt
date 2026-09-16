package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.EntityId

/**
 * Removing an import the user never confirmed.
 *
 * Kept as an interface for the same reason [ImportRollback] is: the screen that
 * will drive it (PLAN 11.4.5, İş 7 Dilim 4) can be exercised without a database,
 * and nothing above this line has to know what a Room entity looks like.
 */
interface ImportDraftRemoval {
    /**
     * Removes one unconfirmed import and the rows of its own, in one transaction.
     *
     * Every expected outcome comes back as a value, including asking twice and
     * the database refusing. Only a defect — a broken postcondition, an invariant
     * the code relies on — comes out as an exception.
     */
    suspend fun remove(batchId: EntityId): DraftRemovalOutcome
}

/**
 * The removal engine behind [ImportDraftRemoval].
 *
 * Takes no clock, no identity generator, no snapshot taker and no file: a
 * removal writes no history line and triggers no automatic backup (PLAN 11.4.5,
 * 14.4.7), and it never reads the source file again, because the raw cells in
 * the database are all there is to remove.
 */
class ImportDraftRemovalStore(
    private val importDao: ImportDao,
    private val diagnostics: Diagnostics = Diagnostics.None,
) : ImportDraftRemoval {
    override suspend fun remove(batchId: EntityId): DraftRemovalOutcome {
        val outcome =
            try {
                importDao.removeDraftBatch(batchId)
            } catch (cause: SQLiteException) {
                // Storage refused, so the transaction rolled back and nothing at all
                // was written. Only this is turned into an answer; anything else is a
                // defect and travels out as it is.
                diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.DRAFT_REMOVAL, DraftRemovalRefusal.COULD_NOT_SAVE, cause) }
                return DraftRemovalOutcome.Refused(batchId, DraftRemovalRefusal.COULD_NOT_SAVE)
            }
        // Real records hold the draft: decided inside the transaction, and recorded
        // here, once, after it has ended (PLAN 14.7.2). The other refusals are
        // answers about a draft that is gone or settled, and are not recorded.
        if ((outcome as? DraftRemovalOutcome.Refused)?.refusal == DraftRemovalRefusal.HELD_BY_RECORDS) {
            diagnostics.recordSafely { DiagnosticRecord(DiagnosticEvent.IMPORT_DRAFT_HELD_BY_RECORDS) }
        }
        return outcome
    }
}
