package dev.pnptracker.ui.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.sqlite.SQLiteException
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.BackupRestorer
import dev.pnptracker.domain.backup.restore.BackupSourceGateway
import dev.pnptracker.domain.backup.restore.RestoreProblem
import dev.pnptracker.domain.backup.restore.SafetyBackupWriter
import dev.pnptracker.domain.backup.restore.SafetySnapshot
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.restore.ValidatedBackup
import dev.pnptracker.domain.backup.retention.AutomaticBackupHousekeeping
import dev.pnptracker.domain.backup.retention.automaticBackupNameOf
import dev.pnptracker.domain.time.localMomentOf
import kotlinx.serialization.SerializationException
import kotlin.time.Clock

/**
 * Drives putting a backup back: choose a file, check it, ask, save a way out,
 * replace everything.
 *
 * The order is the safety. Checking comes before asking, so the destructive
 * question is never put to somebody about a file that turns out to be unusable
 * — PLAN 12.16 requires exactly that. The safety backup comes after the answer
 * and before the transaction, so cancelling costs nothing and going ahead always
 * leaves a way back. And the transaction comes last, so every way this can fail
 * before it leaves the user's data exactly as it was.
 *
 * Three things it refuses.
 *
 * It will not run two flows at once. A second click, a repeated Enter or a press
 * behind the question does nothing, so there is one dialog, one safety backup and
 * one transaction however many times the button is hit.
 *
 * It will not apply an answer to a different file. The agreement carries the
 * token of the backup it was asked about; choose another file and the old
 * agreement has nothing left to apply to.
 *
 * And it will not re-read the file after the agreement. What the transaction
 * writes is the [ValidatedBackup] the user was shown a summary of, held in
 * memory since the check. Reading the file again would mean a person agreeing to
 * one thing and the application applying whatever the file happened to say a
 * moment later.
 */
class RestoreController(
    private val sources: BackupSourceGateway,
    private val reader: UntrustedBackupReader,
    private val exporter: DatabaseBackupExporter,
    private val safety: SafetyBackupWriter,
    private val restorer: BackupRestorer,
    private val housekeeping: AutomaticBackupHousekeeping,
    private val clock: Clock,
) {
    var state: RestoreScreenState by mutableStateOf(RestoreScreenState.Idle)
        private set

    /**
     * The backup the question on screen is about.
     *
     * The immutable result of one reading, kept whole. Nothing between the
     * question and the transaction goes back to the file.
     */
    private var pending: ValidatedBackup? = null

    /** Raised for each backup that passes, so an old agreement cannot be reused. */
    private var token: Int = 0

    /** Raised each time a surface closes, so the keyboard goes back to the button. */
    var focusRecall: Int by mutableStateOf(0)
        private set

    /**
     * Raised once every time the database has been replaced.
     *
     * What the rest of the application watches. Every screen reads through a
     * database flow and refreshes itself, but a panel somebody left open is
     * pointing at a row that no longer exists, so this is the signal to close
     * those rather than let them write to something that has gone (PLAN 14.4.3).
     */
    var restoredTick: Int by mutableStateOf(0)
        private set

    /**
     * Asks which file, then checks it from end to end.
     *
     * Refuses while anything is in flight and while the question is on screen, so
     * a repeated key opens one dialog rather than two and cannot start a second
     * reading behind an unanswered question.
     */
    suspend fun chooseBackup() {
        if (state.isBusy) return
        if (state is RestoreScreenState.Confirming) return
        pending = null
        state = RestoreScreenState.ChoosingSource

        val chosen =
            try {
                sources.chooseSource()
            } catch (unusable: BackupException) {
                // The dialog answered with something that is not a file at all.
                // It is refused in the same words as a file that cannot be read,
                // because that is what it is from here.
                reject(BackupProblem.UNREADABLE)
                return
            }
        // Changing one's mind is not a failure. Nothing was read and nothing was
        // written, so the screen simply goes back to where it was.
        if (chosen == null) {
            state = RestoreScreenState.Idle
            focusRecall++
            return
        }

        state = RestoreScreenState.Validating
        when (val read = reader.read(chosen)) {
            is BackupReadResult.Refused -> reject(read.rejection.problem)
            is BackupReadResult.Valid -> {
                pending = read.backup
                token++
                state = RestoreScreenState.Confirming(token, read.backup.summary)
            }
        }
    }

    /** The user backed out before anything was written. Nothing at all happened. */
    fun cancelRestore() {
        if (state !is RestoreScreenState.Confirming) return
        pending = null
        state = RestoreScreenState.Idle
        focusRecall++
    }

    /**
     * The user agreed: save a way back, then replace everything, once.
     *
     * The whole of the destructive part of this application is these few lines,
     * and each step is guarded by the one before it. The database is read for the
     * safety backup; the safety backup is written; only a written safety backup
     * lets the transaction start (PLAN 14.4.4). An agreement that has been
     * overtaken — the state moved on, another file chosen — applies to nothing
     * and does nothing.
     */
    suspend fun confirmRestore() {
        val asked = state as? RestoreScreenState.Confirming ?: return
        val backup = pending ?: return
        if (asked.token != token) return

        // Set before the first suspension, so a second press arriving while this
        // one is in flight finds a state that is no longer a question.
        state = RestoreScreenState.CreatingSafetyBackup

        val document =
            try {
                exporter.backupDocument()
            } catch (unreadable: SQLiteException) {
                fail(RestoreProblem.SAFETY_BACKUP_NOT_MADE, safetyFileName = null)
                return
            } catch (unwritable: SerializationException) {
                fail(RestoreProblem.SAFETY_BACKUP_NOT_MADE, safetyFileName = null)
                return
            }
        // A broken invariant is deliberately not caught here or below. It is a
        // defect rather than an outcome, and hiding one behind "the backup could
        // not be made" would hide it under the user's data (PLAN 14.4.5).

        state = RestoreScreenState.WritingSafetyBackup
        val safetyFileName =
            try {
                safety.writeSafetyBackup(document.json.encodeToByteArray(), localMomentOf(clock.now()))
            } catch (notWritten: BackupException) {
                fail(RestoreProblem.SAFETY_BACKUP_NOT_WRITTEN, safetyFileName = null)
                return
            }

        state = RestoreScreenState.Applying(safetyFileName)
        // The rows that went into the safety file, so the transaction can refuse
        // to write over anything that changed while it was being written.
        val asItWas =
            SafetySnapshot(
                fileName = safetyFileName,
                data = document.envelope.data,
                dataSha256 = document.envelope.dataSha256,
            )
        val problem = restorer.restore(backup, asItWas)
        if (problem != null) {
            fail(problem, safetyFileName)
        } else {
            pending = null
            state = RestoreScreenState.Restored(fileName = backup.fileName, safetyFileName = safetyFileName)
            restoredTick++
            focusRecall++
        }

        // Last, and after the answer is on screen. The safety backup is on disk
        // whichever way the restore went, so the user's retention policy applies
        // to it either way — and clearing older ones is housekeeping that must
        // never delay what somebody is waiting for, change what they are told,
        // or undo a restore that has already happened (PLAN 14.4.11, 14.4.13).
        //
        // It also goes after the transaction rather than beside the safety
        // backup on purpose. Between writing that file and beginning the
        // transaction there is a window in which a write by anybody else makes
        // the restore refuse; putting a directory listing and a handful of
        // deletions into that window would widen it for no gain.
        automaticBackupNameOf(safetyFileName)?.let { housekeeping.afterWriting(it.setName) }
    }

    /** Puts the screen back to its starting point. */
    fun startOver() {
        if (state.isBusy) return
        pending = null
        state = RestoreScreenState.Idle
    }

    private fun reject(problem: BackupProblem) {
        pending = null
        state = RestoreScreenState.Rejected(problem)
        focusRecall++
    }

    private fun fail(
        problem: RestoreProblem,
        safetyFileName: String?,
    ) {
        pending = null
        state = RestoreScreenState.Failed(problem, safetyFileName)
        focusRecall++
    }
}
