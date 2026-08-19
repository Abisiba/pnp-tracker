package dev.pnptracker.platform.xlsx

import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Asks the user for a spreadsheet.
 *
 * Kept behind an interface so the flow above it can be driven in a test without
 * a window server putting a dialog on someone's screen.
 */
interface XlsxFilePicker {
    /** The chosen file, or null if the user closed the dialog. */
    suspend fun chooseXlsxFile(): Path?
}

/**
 * The blocking half of showing a file dialog, named so a test can stand in for it.
 *
 * An implementation is always called on the AWT event dispatch thread and is
 * expected to block there until the user is done, which is what a modal dialog
 * does. It must hand [publish] a way to take the dialog off the screen before it
 * starts blocking, so a cancelled caller has something to close.
 */
internal fun interface ModalFileDialog {
    fun show(publish: (close: () -> Unit) -> Unit): Path?
}

/**
 * The system file dialog, through AWT.
 *
 * AWT is already on the classpath because the window is, so this needs nothing
 * new. On Linux it gives the desktop's own dialog rather than a Swing imitation
 * of one, which is what makes it feel like part of the machine.
 *
 * The `.xlsx` filter is a convenience only — some desktops ignore it entirely —
 * so whatever comes back is checked properly afterwards.
 */
class AwtXlsxFilePicker internal constructor(
    private val dialog: ModalFileDialog,
) : XlsxFilePicker {
    constructor(owner: Frame? = null, title: String) : this(awtFileDialog(owner, title))

    override suspend fun chooseXlsxFile(): Path? = showOnEventDispatchThread(dialog)
}

private fun awtFileDialog(
    owner: Frame?,
    title: String,
): ModalFileDialog =
    ModalFileDialog { publish ->
        val dialog = FileDialog(owner, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".xlsx", ignoreCase = true) }
        dialog.isMultipleMode = false
        // Published before it goes modal, because after that this thread is inside
        // the dialog's own event loop and cannot hand anything out.
        publish { dialog.dispose() }
        dialog.isVisible = true
        dialog.files.firstOrNull()?.toPath()
    }

/**
 * Runs [dialog] on the AWT event dispatch thread and suspends until it answers.
 *
 * The dialog is built, shown and read on that one thread because it is the only
 * thread allowed to touch it. The hand-off goes through AWT's own [EventQueue]
 * rather than a coroutine dispatcher: `Dispatchers.Main` has no provider on this
 * classpath, and giving it one would mean a new dependency for a single call.
 *
 * `invokeLater` and not `invokeAndWait`, because the caller may already be on the
 * event dispatch thread — Compose runs there — and blocking that thread to wait
 * for work queued onto it would deadlock. Suspending instead leaves the thread
 * free to run the very block being waited on.
 */
private suspend fun showOnEventDispatchThread(dialog: ModalFileDialog): Path? =
    suspendCancellableCoroutine { continuation ->
        // Holds the way to take the dialog off the screen, and only for as long as
        // there is a dialog: null before it exists and null again once it has gone,
        // so a late cancellation never reaches a dialog that already closed.
        val close = AtomicReference<(() -> Unit)?>(null)

        fun closeAnyOpenDialog() {
            val closeDialog = close.getAndSet(null) ?: return
            // Back onto the event dispatch thread. A modal dialog keeps pumping
            // events there, so this still gets through while one is on screen.
            EventQueue.invokeLater { closeDialog() }
        }

        continuation.invokeOnCancellation { closeAnyOpenDialog() }

        EventQueue.invokeLater {
            val outcome =
                try {
                    Result.success(
                        dialog.show { closeDialog ->
                            close.set(closeDialog)
                            // Cancelled while the dialog was still being built, so
                            // nobody was listening a moment ago. Close it now.
                            if (!continuation.isActive) closeAnyOpenDialog()
                        },
                    )
                } catch (cause: Throwable) {
                    // Caught this widely on purpose: this is a hand-off between two
                    // threads, not a classification. Whatever the dialog threw goes
                    // back to the caller exactly as it was, and nothing is turned
                    // into a file failure here. Left uncaught it would disappear
                    // into AWT's own handler and the caller would wait forever.
                    Result.failure(cause)
                }
            close.set(null)
            // A cancelled continuation drops this, so nothing ever resumes twice.
            continuation.resumeWith(outcome)
        }
    }
