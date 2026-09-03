package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.csv.CsvDelimiter
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.spreadsheet.WorkbookSnapshot

/** A file that was read, together with the fingerprint it had while being read. */
data class ReadImportFile(
    val fileName: String,
    val sha256: String,
    val workbook: WorkbookSnapshot,
    val sourceFormat: ImportSourceFormat,
    /** The separator a CSV turned out to use; null for every other format. */
    val csvDelimiter: CsvDelimiter? = null,
)

/**
 * Reads a chosen file and makes sure it did not change while being read.
 *
 * A spreadsheet is an ordinary file on a machine the user also works on, so it
 * can be saved again between the moment it is fingerprinted and the moment it is
 * parsed. If that happens, the preview on screen would describe one version of
 * the file while the fingerprint recorded another, and the record of the import
 * would be quietly wrong forever after.
 *
 * So the fingerprint is taken twice around the read, and once more just before
 * saving. Any disagreement stops the import; nothing is written and the user is
 * asked to choose the file again.
 *
 * Keeping this apart from the screen is what makes it testable without a file:
 * a handle that reports different fingerprints is all a test needs.
 */
class ImportFileReader {
    /**
     * @throws ImportPreparationException with [ImportFailure.FILE_CHANGED_WHILE_READING]
     *   if the file changed while it was being read, or with whatever reason the
     *   handle reports if it could not be read at all.
     */
    suspend fun read(handle: ImportFileHandle): ReadImportFile {
        val before = handle.fingerprint()
        val reading = handle.readWorkbook()
        val after = handle.fingerprint()
        if (before != after) throw ImportPreparationException(ImportFailure.FILE_CHANGED_WHILE_READING)

        return ReadImportFile(
            fileName = handle.fileName,
            sha256 = before,
            workbook = reading.workbook,
            sourceFormat = reading.sourceFormat,
            csvDelimiter = reading.csvDelimiter,
        )
    }

    /**
     * Checks the file still is what the preview describes.
     *
     * @throws ImportPreparationException if it is not, or if it can no longer be read.
     */
    suspend fun requireUnchanged(
        handle: ImportFileHandle,
        expectedSha256: String,
    ) {
        if (handle.fingerprint() != expectedSha256) {
            throw ImportPreparationException(ImportFailure.FILE_CHANGED_WHILE_READING)
        }
    }
}
