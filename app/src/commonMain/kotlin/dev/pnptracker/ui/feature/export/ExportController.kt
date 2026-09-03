package dev.pnptracker.ui.feature.export

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.TaskExportSource
import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.ExportFileGateway
import dev.pnptracker.domain.export.ExportFileHandle
import dev.pnptracker.domain.export.SUGGESTED_EXPORT_FILE_NAME
import dev.pnptracker.domain.export.TaskExportException
import dev.pnptracker.domain.export.TaskExportNames
import dev.pnptracker.domain.export.taskCsvOf

/**
 * Drives writing the tasks out: choose a file, agree to replace one if need be,
 * write it.
 *
 * The order of the four steps is the whole design. The destination is settled
 * first, so changing one's mind costs no database read at all; then the database
 * is read once; then the bytes are made; then the file is written. Nothing holds
 * a transaction open across the file dialog or across a slow disk, and the file
 * that lands describes exactly one moment.
 *
 * Two things it refuses. It will not start a second export while one is running,
 * so a double click cannot open two dialogs or write two files. And it will not
 * replace a file that is already there until the user has said so, which is a
 * decision only they can make.
 *
 * The chosen destination is held here rather than in the state, so the state
 * stays a plain value the screen and the tests can compare.
 */
class ExportController(
    private val gateway: ExportFileGateway,
    private val tasks: TaskExportSource,
    private val names: suspend () -> TaskExportNames,
) {
    var state: ExportScreenState by mutableStateOf(ExportScreenState.Idle)
        private set

    private var destination: ExportFileHandle? = null

    /** True while an action is in flight, so the screen can disable its buttons. */
    val isBusy: Boolean
        get() = state is ExportScreenState.ChoosingDestination || state is ExportScreenState.Writing

    /** Raised each time the keyboard should be sent back to the export button. */
    var focusRecall: Int by mutableStateOf(0)
        private set

    suspend fun exportTasks() {
        if (isBusy) return
        // A confirmation already on screen owns the flow; the button behind it
        // does nothing until it has been answered.
        if (state is ExportScreenState.ConfirmingOverwrite) return
        state = ExportScreenState.ChoosingDestination

        val chosen =
            try {
                gateway.chooseDestination(SUGGESTED_EXPORT_FILE_NAME)
            } catch (refused: TaskExportException) {
                destination = null
                state = ExportScreenState.Failed(refused.failure)
                return
            }
        // Changing one's mind is not a failure; the screen goes back to where it
        // was, having read nothing and written nothing.
        if (chosen == null) {
            destination = null
            state = ExportScreenState.Idle
            return
        }

        destination = chosen
        val alreadyThere =
            try {
                chosen.exists()
            } catch (refused: TaskExportException) {
                destination = null
                state = ExportScreenState.Failed(refused.failure)
                return
            }
        if (alreadyThere) {
            state = ExportScreenState.ConfirmingOverwrite(chosen.fileName)
            return
        }
        write(chosen)
    }

    /** The user agreed to replace the file that was already there. */
    suspend fun confirmOverwrite() {
        if (state !is ExportScreenState.ConfirmingOverwrite) return
        val chosen = destination ?: return
        write(chosen)
    }

    /** The user backed out; the file that was there is exactly as it was. */
    fun cancelOverwrite() {
        if (state !is ExportScreenState.ConfirmingOverwrite) return
        destination = null
        state = ExportScreenState.Idle
        focusRecall++
    }

    /** Puts the screen back to its starting point. */
    fun startOver() {
        if (isBusy) return
        destination = null
        state = ExportScreenState.Idle
    }

    /**
     * Reads the database once, makes the bytes, writes the file.
     *
     * Everything that could go wrong arrives as one exception type, so there is
     * one place where a failure becomes a message and none where a raw cause
     * could reach the screen.
     */
    private suspend fun write(chosen: ExportFileHandle) {
        state = ExportScreenState.Writing
        try {
            val exported = tasks.exportedTasks()
            val content = taskCsvOf(exported, names())
            chosen.write(content)
            destination = null
            state = ExportScreenState.Written(fileName = chosen.fileName, taskCount = exported.size)
        } catch (refused: TaskExportException) {
            destination = null
            state = ExportScreenState.Failed(refused.failure)
        }
        focusRecall++
    }

    /** True when the outcome on screen is one the user asked for rather than a fault. */
    val hasFailed: Boolean get() = state is ExportScreenState.Failed

    /** The reason to show, or null when there is nothing wrong. */
    val failure: ExportFailure? get() = (state as? ExportScreenState.Failed)?.failure
}
