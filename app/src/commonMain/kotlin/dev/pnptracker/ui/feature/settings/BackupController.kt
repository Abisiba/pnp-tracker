package dev.pnptracker.ui.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.sqlite.SQLiteException
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.BackupFileGateway
import dev.pnptracker.domain.backup.BackupFileHandle
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.suggestedBackupFileName
import dev.pnptracker.domain.time.localMomentOf
import kotlinx.serialization.SerializationException
import kotlin.time.Clock

/**
 * Drives saving a backup: choose a file, agree to replace one if need be, write
 * it.
 *
 * The order of the four steps is the whole design. The destination is settled
 * first, so changing one's mind costs no database read at all; then the database
 * is read once; then the bytes are made; then the file is written. Nothing holds
 * a transaction open across the file dialog or across a slow disk, and the file
 * that lands describes exactly one moment.
 *
 * Three things it refuses. It will not start a second save while one is running,
 * so a double click cannot open two dialogs or write two files. It will not
 * replace a file that is already there until the user has said so. And an
 * agreement to replace belongs to the file it was asked about: choosing a
 * different destination afterwards leaves the old answer with nothing to apply
 * to.
 *
 * The chosen destination is held here rather than in the state, so the state
 * stays a plain value the screen and the tests can compare.
 */
class BackupController(
    private val gateway: BackupFileGateway,
    private val exporter: DatabaseBackupExporter,
    private val clock: Clock,
) {
    var state: BackupScreenState by mutableStateOf(BackupScreenState.Idle)
        private set

    /**
     * The destination the user chose, and the only one an agreement can apply to.
     *
     * Held as the handle itself rather than as a remembered name: a name is not
     * an identity, and two different folders can hold the same one.
     */
    private var destination: BackupFileHandle? = null

    /** Raised each time a surface closes, so the keyboard goes back to the button. */
    var focusRecall: Int by mutableStateOf(0)
        private set

    /** The name the save dialog will offer, from the user's own calendar day. */
    fun suggestedFileName(): String = suggestedBackupFileName(localMomentOf(clock.now()))

    /**
     * Asks where to save, and saves there.
     *
     * Refuses while anything is in flight and while a question is on screen, so
     * a double click and a repeated Enter open one dialog rather than two.
     */
    suspend fun saveBackup() {
        if (state.isBusy) return
        // A confirmation already on screen owns the flow; the button behind it
        // does nothing until it has been answered.
        if (state is BackupScreenState.ConfirmingOverwrite) return
        state = BackupScreenState.ChoosingDestination

        val chosen =
            try {
                gateway.chooseDestination(suggestedFileName())
            } catch (refused: BackupException) {
                fail(refused.failure)
                return
            }
        // Changing one's mind is not a failure; the screen goes back to where it
        // was, having read nothing and written nothing.
        if (chosen == null) {
            destination = null
            state = BackupScreenState.Idle
            focusRecall++
            return
        }

        destination = chosen
        val alreadyThere =
            try {
                chosen.exists()
            } catch (refused: BackupException) {
                fail(refused.failure)
                return
            }
        if (alreadyThere) {
            state = BackupScreenState.ConfirmingOverwrite(chosen.fileName)
            return
        }
        write(chosen)
    }

    /**
     * The user agreed to replace the file that was already there.
     *
     * Only the destination the question was asked about is written to. If the
     * flow has moved on — another dialog opened, the answer already given — this
     * does nothing rather than writing somewhere nobody agreed to.
     */
    suspend fun confirmOverwrite() {
        val asked = state as? BackupScreenState.ConfirmingOverwrite ?: return
        val chosen = destination ?: return
        if (chosen.fileName != asked.fileName) return
        write(chosen)
    }

    /** The user backed out; the file that was there is exactly as it was. */
    fun cancelOverwrite() {
        if (state !is BackupScreenState.ConfirmingOverwrite) return
        destination = null
        state = BackupScreenState.Idle
        focusRecall++
    }

    /** Puts the screen back to its starting point. */
    fun startOver() {
        if (state.isBusy) return
        destination = null
        state = BackupScreenState.Idle
    }

    /**
     * Reads the database once, builds the document, writes the file.
     *
     * The two halves are told apart because they fail for different reasons: a
     * database that cannot be read is not a disk that is full, and a user can do
     * something about the second. Everything else that could go wrong arrives as
     * one exception type, so there is one place where a failure becomes a
     * message and none where a raw cause could reach the screen.
     */
    private suspend fun write(chosen: BackupFileHandle) {
        state = BackupScreenState.Preparing
        val document =
            try {
                exporter.backupDocument()
            } catch (refused: SQLiteException) {
                // Storage said no while the snapshot was being read.
                fail(BackupFailure.COULD_NOT_READ_DATABASE)
                return
            } catch (refused: SerializationException) {
                // The rows were read but could not be written out as the
                // document. Its own answer, because the two call for different
                // things: one is a database to look at, the other is this
                // application's own format.
                fail(BackupFailure.COULD_NOT_BUILD_DOCUMENT)
                return
            }
        // A broken invariant is deliberately not caught above. It is a defect
        // rather than a saved-or-not, and dressing one up as "the backup could
        // not be made" would hide a bug behind the user's data.

        state = BackupScreenState.Writing(chosen.fileName)
        try {
            chosen.write(document.json.encodeToByteArray())
        } catch (refused: BackupException) {
            fail(refused.failure)
            return
        }
        destination = null
        state = BackupScreenState.Saved(chosen.fileName)
        focusRecall++
    }

    private fun fail(failure: BackupFailure) {
        destination = null
        state = BackupScreenState.Failed(failure)
        focusRecall++
    }
}
