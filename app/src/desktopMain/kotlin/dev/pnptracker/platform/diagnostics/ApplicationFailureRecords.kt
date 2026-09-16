package dev.pnptracker.platform.diagnostics

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticLevel
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import java.awt.Window

/**
 * The record of the gate refusing to open the database (PLAN 14.7.2).
 *
 * Another copy already running is a refusal the user caused and can undo by
 * closing it, so it is a warning; every other refusal is an error. The refusal's
 * own cause gives the classes and nothing else.
 */
fun startupRefusalRecord(refused: StartupRefused): DiagnosticRecord =
    DiagnosticRecord(
        event = DiagnosticEvent.STARTUP_REFUSED,
        level = if (refused.problem == StartupProblem.ANOTHER_COPY_IS_RUNNING) DiagnosticLevel.WARN else DiagnosticLevel.ERROR,
        reason = refused.problem,
        failure = refused.cause,
    )

/**
 * Records an error that reached the window without any typed boundary catching
 * it, and then does exactly what [delegate] does.
 *
 * What Compose 1.11.1 does with such an error was measured before this was put in
 * front of it (PLAN 14.7.2): the default handler posts, to the event thread, an
 * error dialog followed by a request to close the window, and throws the error
 * again. None of that changes here — the same handler receives the same error,
 * after one record that holds the error's class and its root cause's class. A
 * diagnostics that throws changes nothing either. The record reaches the disk
 * while the dialog is open, and the close the handler requests goes through the
 * application's own close, which gives the log half a second to finish.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun recordingExceptionHandler(
    diagnostics: Diagnostics,
    delegate: WindowExceptionHandler,
): WindowExceptionHandler =
    WindowExceptionHandler { failure ->
        diagnostics.recordSafely {
            DiagnosticRecord(DiagnosticEvent.UNEXPECTED_FAILURE, area = DiagnosticArea.APPLICATION, failure = failure)
        }
        delegate.onException(failure)
    }

/** [recordingExceptionHandler] for every window, in front of the handler Compose would have used. */
@OptIn(ExperimentalComposeUiApi::class)
class RecordingWindowExceptionHandlerFactory(
    private val diagnostics: Diagnostics,
    private val delegate: WindowExceptionHandlerFactory = DefaultWindowExceptionHandlerFactory,
) : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: Window): WindowExceptionHandler =
        recordingExceptionHandler(diagnostics, delegate.exceptionHandler(window))
}
