package dev.pnptracker.domain.backup.restore

/**
 * Asks the user which backup file to read.
 *
 * Separate from the gateway that asks where to *save* one, and separate on
 * purpose. They are opposite directions, they open different dialogs, and
 * keeping them apart means the controller that saves a backup has no way to open
 * one and the controller that restores has no way to write over one.
 *
 * What comes back is a [BackupInput] — bytes and a name. No path crosses this
 * line, so nothing above it can be told where the user keeps their files
 * (PLAN 14.4.5).
 */
interface BackupSourceGateway {
    /**
     * The file the user chose, or null when they changed their mind — which is
     * an ordinary outcome and not a failure.
     *
     * @throws dev.pnptracker.domain.backup.BackupException if the dialog
     *   answered with something that cannot be read at all.
     */
    suspend fun chooseSource(): BackupInput?
}
