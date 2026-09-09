package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.BACKUP_EXTENSION
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.restore.BackupInput
import dev.pnptracker.domain.backup.restore.BackupSourceGateway
import dev.pnptracker.platform.awt.ModalFileDialog
import dev.pnptracker.platform.awt.showOnEventDispatchThread
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/**
 * Asks the user which backup file to open.
 *
 * The reading counterpart of [BackupFilePicker], and kept behind an interface for
 * the same reason: the flow above it can be driven in a test without a window
 * server putting a dialog on somebody's screen.
 */
interface BackupSourcePicker {
    /** The file to read, or null if the user closed the dialog. */
    suspend fun chooseSource(): Path?
}

/**
 * The system open dialog, through AWT.
 *
 * `.json` is offered as a filter rather than enforced by it. AWT's filename
 * filter is a suggestion that some desktops honour and others quietly ignore, so
 * a file that arrives here despite it is not treated as an error: it goes to the
 * reader like any other, and the reader refuses it in its own words. A picker
 * that rejected by extension would be a second, weaker copy of a check that is
 * already made properly one step later.
 */
class AwtBackupSourcePicker internal constructor(
    private val dialog: () -> ModalFileDialog,
) : BackupSourcePicker {
    constructor(owner: Frame? = null, title: String) : this({ awtOpenDialog(owner, title) })

    override suspend fun chooseSource(): Path? = showOnEventDispatchThread(dialog())
}

private fun awtOpenDialog(
    owner: Frame?,
    title: String,
): ModalFileDialog =
    ModalFileDialog { publish ->
        val dialog = FileDialog(owner, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(BACKUP_EXTENSION, ignoreCase = true) }
        // Published before it goes modal, because after that this thread is
        // inside the dialog's own event loop and cannot hand anything out.
        publish { dialog.dispose() }
        dialog.isVisible = true
        val folder = dialog.directory ?: return@ModalFileDialog null
        val name = dialog.file ?: return@ModalFileDialog null
        Path.of(folder, name)
    }

/**
 * The desktop end of choosing a backup to read.
 *
 * The mirror of `DesktopBackupFileGateway`: the [Path] the dialog answered with
 * is turned into a [BackupInput] here and never travels any further, so the
 * screen, the state and every message above this line know only a file name
 * (PLAN 14.4.5).
 */
class DesktopBackupSourceGateway(
    private val picker: BackupSourcePicker,
) : BackupSourceGateway {
    override suspend fun chooseSource(): BackupInput? {
        val chosen = picker.chooseSource() ?: return null
        if (chosen.fileName?.toString().isNullOrEmpty()) throw BackupException(BackupFailure.NO_DESTINATION)
        return PathBackupInput(chosen)
    }
}
