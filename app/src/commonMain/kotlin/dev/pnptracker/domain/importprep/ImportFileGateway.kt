package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.csv.CsvDelimiter
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.spreadsheet.WorkbookSnapshot

/**
 * What reading a chosen file turned up.
 *
 * The workbook alone cannot say how it was read. A spreadsheet has sheets and
 * cells and no separator to speak of; a CSV had to have one worked out before it
 * could become either. So the reading carries both, and the screen can tell the
 * user which separator it settled on without anything having to guess from the
 * file name.
 */
data class ImportSourceReading(
    val workbook: WorkbookSnapshot,
    val sourceFormat: ImportSourceFormat,
    /** Only a CSV has one, and every CSV has one. */
    val csvDelimiter: CsvDelimiter? = null,
) {
    init {
        require((csvDelimiter != null) == (sourceFormat == ImportSourceFormat.CSV)) {
            "A separator belongs to a CSV and to nothing else, but $sourceFormat had $csvDelimiter"
        }
    }
}

/**
 * A file the user picked, seen from the side of the application that must not
 * know about files.
 *
 * Only the name is exposed. Where the file sits is the user's private business
 * and is of no use above this line, so nothing here can leak a path into the
 * screen, the database or a message — there is simply nothing to leak.
 *
 * [fingerprint] can be asked more than once, and that is the point: the same
 * handle is fingerprinted before reading, after reading and again before saving,
 * so a file that changed underneath the preview is caught instead of saved.
 */
interface ImportFileHandle {
    val fileName: String

    /** @throws ImportPreparationException if the file cannot be read. */
    suspend fun fingerprint(): String

    /** @throws ImportPreparationException if the file cannot be read. */
    suspend fun readWorkbook(): ImportSourceReading
}

/** Asks the user for a spreadsheet or a CSV file. */
interface ImportFileGateway {
    /** The chosen file, or null when the user cancelled — which is not a failure. */
    suspend fun chooseFile(): ImportFileHandle?
}
