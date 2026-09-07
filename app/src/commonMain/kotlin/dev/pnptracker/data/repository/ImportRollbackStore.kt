package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.importrollback.ImportRollbackPreview
import dev.pnptracker.domain.importrollback.ImportRollbackResult
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlin.time.Clock

/**
 * Taking a confirmed import back, and nothing else.
 *
 * Kept as an interface for the same reason [ImportConfirmation] is: the screen
 * that will drive this in the next slice can be exercised without a database,
 * and nothing above this line has to know what a Room entity looks like.
 */
interface ImportRollback {
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
) : ImportRollback {
    override suspend fun previewRollback(batchId: EntityId): ImportRollbackPreview = importDao.previewRollback(batchId)

    override suspend fun rollBack(batchId: EntityId): ImportRollbackResult =
        try {
            importDao.rollBackConfirmedBatch(batchId = batchId, clock = clock, idGenerator = idGenerator)
        } catch (cause: SQLiteException) {
            // Storage refused, so the transaction rolled back and nothing at all
            // was written. Only this is turned into an answer the user can be
            // given; a broken invariant travels out as it is, because it is a
            // defect rather than a saved-or-not, and dressing one up as "the
            // import looks damaged" would hide a bug behind the user's data.
            throw ImportRollbackException(ImportRollbackFailure.COULD_NOT_SAVE, cause = cause)
        }
}
