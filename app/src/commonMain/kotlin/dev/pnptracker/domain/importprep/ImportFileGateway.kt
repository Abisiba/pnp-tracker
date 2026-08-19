package dev.pnptracker.domain.importprep

import dev.pnptracker.domain.spreadsheet.WorkbookSnapshot

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
    suspend fun readWorkbook(): WorkbookSnapshot
}

/** Asks the user for a spreadsheet. */
interface ImportFileGateway {
    /** The chosen file, or null when the user cancelled — which is not a failure. */
    suspend fun chooseFile(): ImportFileHandle?
}
