package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageReadFailed
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.importrollback.ImportRollbackPreview
import dev.pnptracker.domain.importrollback.ImportRollbackResult
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * One import that has already been confirmed, as the list of them needs to read.
 *
 * No identifier of anything inside it and no file path: a row names the file it
 * came from, the sheet it read, how much it made and where it stands, which is
 * everything PLAN 11.4.4 has the screen say about one.
 */
data class SettledImport(
    val batchId: EntityId,
    val fileName: String,
    val sheetName: String,
    val createdTaskCount: Int,
    val status: ImportBatchStatus,
) {
    /**
     * Whether taking this one back is even worth offering.
     *
     * Only a confirmed import can be: a draft made nothing, and one already
     * taken back is finished (PLAN 11.4.4). This is not the decision — the
     * engine makes that, twice — it only keeps a button off a row that could
     * never use it.
     */
    val canBeTakenBack: Boolean get() = status == ImportBatchStatus.CONFIRMED
}

/**
 * Taking a confirmed import back, and looking at the ones there are.
 *
 * Kept as an interface for the same reason [ImportConfirmation] is: the screen
 * driving it can be exercised without a database, and nothing above this line
 * has to know what a Room entity looks like.
 */
interface ImportRollback {
    /**
     * Every import that has been confirmed, newest first, kept fresh.
     *
     * One stream for the whole list rather than one per batch, and the rows
     * carry no live decision: whether one can be taken back is asked of
     * [previewRollback] when the user asks for it, not held in a list that would
     * be out of date by the time they read it.
     */
    fun observeSettledImports(): Flow<List<SettledImport>>

    /**
     * What taking this import back would do right now.
     *
     * Advisory only. Every check in it is made again inside [rollBack], from
     * rows read there — a preview is a picture of a moment that has already
     * passed by the time the user reads it.
     */
    suspend fun previewRollback(batchId: EntityId): ImportRollbackPreview

    /**
     * Takes one confirmed import back, in one transaction.
     *
     * @throws ImportRollbackException if nothing was taken back, which is the
     *   only other outcome there is. It carries the tasks and cells that stood
     *   in the way, so the screen can name them.
     */
    suspend fun rollBack(batchId: EntityId): ImportRollbackResult
}

class ImportRollbackStore(
    private val importDao: ImportDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
    private val diagnostics: Diagnostics = Diagnostics.None,
) : ImportRollback {
    override fun observeSettledImports(): Flow<List<SettledImport>> =
        importDao
            .observeSettledBatches()
            .map { batches ->
                batches.map {
                    SettledImport(
                        batchId = it.id,
                        fileName = it.fileName,
                        sheetName = it.sheetName,
                        createdTaskCount = it.createdTaskCount,
                        status = it.status,
                    )
                }
            }.catch { cause ->
                if (cause !is SQLiteException) throw cause
                diagnostics.recordSafely { storageReadFailed(DiagnosticArea.SETTLED_IMPORTS, cause) }
                throw ImportRollbackException(ImportRollbackFailure.COULD_NOT_SAVE, cause = cause)
            }

    override suspend fun previewRollback(batchId: EntityId): ImportRollbackPreview {
        val preview =
            try {
                importDao.previewRollback(batchId)
            } catch (cause: SQLiteException) {
                throw storageRefused(cause)
            }
        if (preview.blockingFailure == ImportRollbackFailure.PROVENANCE_BROKEN) recordProvenanceBroken()
        return preview
    }

    override suspend fun rollBack(batchId: EntityId): ImportRollbackResult =
        try {
            importDao.rollBackConfirmedBatch(batchId = batchId, clock = clock, idGenerator = idGenerator)
        } catch (cause: SQLiteException) {
            throw storageRefused(cause)
        } catch (refused: ImportRollbackException) {
            // Every other refusal is an answer the user can see the reason for and
            // is not recorded (PLAN 14.7.2); a batch whose own records do not say
            // it made these tasks is.
            if (refused.failure == ImportRollbackFailure.PROVENANCE_BROKEN) recordProvenanceBroken()
            throw refused
        }

    /**
     * One record per refusal the user is shown. The preview and the transaction
     * make the same decision, and each one reaches the user on its own: a preview
     * that refuses is never followed by a transaction, and a transaction only
     * refuses after a preview that did not.
     */
    private fun recordProvenanceBroken() {
        diagnostics.recordSafely { DiagnosticRecord(DiagnosticEvent.IMPORT_ROLLBACK_PROVENANCE_BROKEN) }
    }

    /**
     * Storage refused, so the transaction rolled back and nothing at all was
     * written.
     *
     * Only this is turned into an answer the user can be given; a broken
     * invariant travels out as it is, because it is a defect rather than a
     * saved-or-not, and dressing one up as "the import looks damaged" would hide
     * a bug behind the user's data.
     */
    private fun storageRefused(cause: SQLiteException): ImportRollbackException {
        diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.IMPORT_ROLLBACK, ImportRollbackFailure.COULD_NOT_SAVE, cause) }
        return ImportRollbackException(ImportRollbackFailure.COULD_NOT_SAVE, cause = cause)
    }
}
