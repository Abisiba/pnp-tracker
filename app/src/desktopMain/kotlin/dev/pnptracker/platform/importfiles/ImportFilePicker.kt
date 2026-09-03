package dev.pnptracker.platform.importfiles

import dev.pnptracker.platform.awt.ModalFileDialog
import dev.pnptracker.platform.awt.showOnEventDispatchThread
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/**
 * Asks the user for a file to import: a spreadsheet or a CSV.
 *
 * Kept behind an interface so the flow above it can be driven in a test without
 * a window server putting a dialog on someone's screen.
 */
interface ImportFilePicker {
    /** The chosen file, or null if the user closed the dialog. */
    suspend fun chooseImportFile(): Path?
}

/**
 * The system file dialog, through AWT.
 *
 * AWT is already on the classpath because the window is, so this needs nothing
 * new. On Linux it gives the desktop's own dialog rather than a Swing imitation
 * of one, which is what makes it feel like part of the machine.
 *
 * The extension filter is a convenience only — some desktops ignore it entirely
 * — so whatever comes back is checked properly afterwards.
 */
class AwtImportFilePicker internal constructor(
    private val dialog: ModalFileDialog,
) : ImportFilePicker {
    constructor(owner: Frame? = null, title: String) : this(awtFileDialog(owner, title))

    override suspend fun chooseImportFile(): Path? = showOnEventDispatchThread(dialog)
}

private fun awtFileDialog(
    owner: Frame?,
    title: String,
): ModalFileDialog =
    ModalFileDialog { publish ->
        val dialog = FileDialog(owner, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            name.endsWith(".xlsx", ignoreCase = true) || name.endsWith(".csv", ignoreCase = true)
        }
        dialog.isMultipleMode = false
        // Published before it goes modal, because after that this thread is inside
        // the dialog's own event loop and cannot hand anything out.
        publish { dialog.dispose() }
        dialog.isVisible = true
        dialog.files.firstOrNull()?.toPath()
    }
