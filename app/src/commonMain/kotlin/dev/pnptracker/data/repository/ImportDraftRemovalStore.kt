package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ImportDao
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
) : ImportDraftRemoval {
    override suspend fun remove(batchId: EntityId): DraftRemovalOutcome =
        try {
            importDao.removeDraftBatch(batchId)
        } catch (cause: SQLiteException) {
            // Storage refused, so the transaction rolled back and nothing at all
            // was written. Only this is turned into an answer; anything else is a
            // defect and travels out as it is.
            DraftRemovalOutcome.Refused(batchId, DraftRemovalRefusal.COULD_NOT_SAVE)
        }
}
