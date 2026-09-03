package dev.pnptracker.platform.exportfiles

import dev.pnptracker.platform.awt.ModalFileDialog
import dev.pnptracker.platform.awt.showOnEventDispatchThread
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/**
 * Asks the user where to write a file.
 *
 * Kept behind an interface so the flow above it can be driven in a test without
 * a window server putting a dialog on someone's screen — the same reason the
 * import picker is, and through the same AWT hand-off.
 */
interface ExportFilePicker {
    /** Where to write, or null if the user closed the dialog. */
    suspend fun chooseDestination(suggestedName: String): Path?
}

/**
 * The system save dialog, through AWT.
 *
 * The dialog's own overwrite prompt is not relied on: AWT's own file dialog does
 * not ask on Linux, and one that did would be asking in a language and a style
 * the rest of the application does not control. The question is asked by the
 * application instead, after this has answered.
 */
class AwtExportFilePicker internal constructor(
    private val dialog: (suggestedName: String) -> ModalFileDialog,
) : ExportFilePicker {
    constructor(owner: Frame? = null, title: String) : this({ name -> awtSaveDialog(owner, title, name) })

    override suspend fun chooseDestination(suggestedName: String): Path? = showOnEventDispatchThread(dialog(suggestedName))
}

private fun awtSaveDialog(
    owner: Frame?,
    title: String,
    suggestedName: String,
): ModalFileDialog =
    ModalFileDialog { publish ->
        val dialog = FileDialog(owner, title, FileDialog.SAVE)
        dialog.file = suggestedName
        // Published before it goes modal, because after that this thread is inside
        // the dialog's own event loop and cannot hand anything out.
        publish { dialog.dispose() }
        dialog.isVisible = true
        // `files` is read for a load dialog and is empty for a save one: it lists
        // what was picked, and nothing has been picked when the file is about to
        // be created. The name and the folder are the two things a save dialog
        // really answers with, and null in either is the user having closed it.
        val folder = dialog.directory ?: return@ModalFileDialog null
        val name = dialog.file ?: return@ModalFileDialog null
        Path.of(folder, name)
    }
