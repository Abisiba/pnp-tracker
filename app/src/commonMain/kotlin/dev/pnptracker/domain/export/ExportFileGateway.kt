package dev.pnptracker.domain.export

/**
 * A place the user chose to write to, seen from the side of the application that
 * must not know about files.
 *
 * Only the name is exposed. Where the file sits is the user's private business
 * and is of no use above this line, so no absolute path can reach the screen, a
 * message or a log through this route — there is simply nothing to leak.
 */
interface ExportFileHandle {
    /** The name to show, never a path. */
    val fileName: String

    /** Whether something is already there and would be replaced. */
    suspend fun exists(): Boolean

    /**
     * Writes [content] as UTF-8, all of it or none of it.
     *
     * @throws TaskExportException if the file could not be written. Whatever was
     *   at the destination before is unchanged when this throws.
     */
    suspend fun write(content: String)
}

/** Asks the user where to write the file. */
interface ExportFileGateway {
    /**
     * The chosen destination, or null when the user changed their mind — which
     * is an ordinary outcome and not a failure.
     *
     * @throws TaskExportException with [ExportFailure.UNSUPPORTED_FILE_TYPE] if
     *   the chosen name is not a `.csv`.
     */
    suspend fun chooseDestination(suggestedName: String): ExportFileHandle?
}
