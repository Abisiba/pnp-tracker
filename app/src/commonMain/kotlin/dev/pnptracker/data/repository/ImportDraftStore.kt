package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.ImportPreparationException
import dev.pnptracker.domain.importprep.PreparedImportDraft
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import kotlin.time.Clock

/** What a finished save produced, for the screen to report. */
data class SavedImportSummary(
    val batchId: EntityId,
    val fileName: String,
    val sheetName: String,
    val rawBlockCount: Int,
)

/** One earlier import of the same file, as far as the user needs to know about it. */
data class EarlierImport(
    val batchId: EntityId,
    val fileName: String,
    val sheetName: String,
    val status: ImportBatchStatus,
)

/**
 * The two things the import screen needs of storage.
 *
 * Kept as an interface because the screen has no business knowing whether there
 * is a database behind it, and because driving the whole flow in a test should
 * not need one.
 */
interface ImportDrafts {
    /**
     * Every earlier import of a file with this fingerprint, newest first.
     *
     * Asked rather than stored: whether a file is a repeat is a question about
     * the other rows in the table, and an answer written down at import time
     * would be wrong as soon as the next import ran.
     */
    suspend fun earlierImportsOf(sha256: String): List<EarlierImport>

    /**
     * Writes [draft] as a single draft import. Nothing is written if any part fails.
     *
     * @throws ImportPreparationException with [ImportFailure.COULD_NOT_SAVE] when
     *   storage would not take it. The draft in hand is untouched by that and can
     *   be offered again as it stands.
     */
    suspend fun save(draft: PreparedImportDraft): SavedImportSummary
}

/**
 * Puts a prepared import into the database, and answers whether a file has been
 * imported before.
 *
 * Identifiers and the time are made here rather than while the user is still
 * looking at a preview, so that opening a preview twice cannot scatter unused
 * identifiers about. The clock is read exactly once per save, which is what
 * makes every row of one import share a timestamp.
 *
 * This step creates no game, cell, task or draft task. An import is a record of
 * what a file said; turning any of it into production records is a separate
 * decision the user has not made yet.
 */
class ImportDraftStore(
    private val importDao: ImportDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
    private val diagnostics: Diagnostics = Diagnostics.None,
) : ImportDrafts {
    override suspend fun earlierImportsOf(sha256: String): List<EarlierImport> =
        importDao.batchesWithFingerprint(sha256).map { batch ->
            EarlierImport(
                batchId = batch.id,
                fileName = batch.fileName,
                sheetName = batch.sheetName,
                status = batch.status,
            )
        }

    override suspend fun save(draft: PreparedImportDraft): SavedImportSummary {
        val now = clock.now()
        val batchId = idGenerator.newId()

        val batch =
            ImportBatchEntity(
                id = batchId,
                fileName = draft.fileName,
                sha256 = draft.sha256,
                sourceFormat = draft.sourceFormat,
                sheetName = draft.sheetName,
                startRowIndex = draft.startRowIndex,
                endRowIndex = draft.endRowIndex,
                startColumnIndex = draft.startColumnIndex,
                endColumnIndex = draft.endColumnIndex,
                // Reading a file creates no games and no tasks. These stay at
                // zero until the user confirms the import.
                createdGameCount = 0,
                createdTaskCount = 0,
                rawBlockCount = draft.rawBlockCount,
                status = ImportBatchStatus.DRAFT,
                importedAt = now,
                updatedAt = now,
            )
        val blocks =
            draft.blocks.map { block ->
                RawImportBlockEntity(
                    id = idGenerator.newId(),
                    importBatchId = batchId,
                    rawText = block.rawText,
                    sheetName = draft.sheetName,
                    rowIndex = block.rowIndex,
                    columnIndex = block.columnIndex,
                    sourceColumnType = block.sourceColumnType,
                    fillColorArgb = block.fillColorArgb,
                    gameCompletionHint = block.gameCompletionHint,
                    isProcessed = false,
                    createdAt = now,
                    updatedAt = now,
                )
            }

        // The one transaction the whole draft is written in, so a refusal here
        // leaves no batch and no block behind — which is what lets the screen
        // offer the very same draft again (PLAN 14.7.6). This is where storage
        // refusing becomes the answer the user is given, so this is where it is
        // recorded, once; nothing above it records it again (PLAN 14.7.2).
        try {
            importDao.saveDraftBatch(batch, blocks)
        } catch (cause: SQLiteException) {
            diagnostics.recordSafely { storageWriteFailed(DiagnosticArea.IMPORT_DRAFT, ImportFailure.COULD_NOT_SAVE, cause) }
            // The refusal itself stays here: its classes are already in the
            // record, its message is SQL, and the exception the screen catches
            // carries the reason and nothing else (PLAN 14.4.5, PLAN 14.7.1).
            throw ImportPreparationException(ImportFailure.COULD_NOT_SAVE)
        }

        return SavedImportSummary(
            batchId = batchId,
            fileName = draft.fileName,
            sheetName = draft.sheetName,
            rawBlockCount = blocks.size,
        )
    }
}
