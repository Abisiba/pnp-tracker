package dev.pnptracker.platform.awt

import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

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
internal suspend fun showOnEventDispatchThread(dialog: ModalFileDialog): Path? =
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
