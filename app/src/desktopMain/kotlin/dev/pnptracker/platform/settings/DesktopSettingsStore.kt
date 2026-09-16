package dev.pnptracker.platform.settings

import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.settings.AutomaticBackupSettings
import dev.pnptracker.domain.settings.SettingsNotSaved
import dev.pnptracker.domain.settings.SettingsProblem
import dev.pnptracker.domain.settings.SettingsStore
import dev.pnptracker.domain.settings.SettingsWriteFailure
import dev.pnptracker.domain.settings.isKeepableCount
import dev.pnptracker.domain.settings.settingsDocumentFor
import dev.pnptracker.domain.settings.settingsIn
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.AtomicWriteException
import dev.pnptracker.platform.files.AtomicWriteFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** What a half-written settings file is called while it is still half written. */
private const val TEMPORARY_SUFFIX = ".json.part"

/**
 * The settings file on this machine, and the only place it is touched.
 *
 * Two things this will not do, and both are PLAN 14.4.12.
 *
 * It does not create the file by reading it. A machine that has never had a
 * setting saved has no settings file, and opening the screen, taking a backup,
 * putting one back or clearing old ones all leave it that way. The file appears
 * when somebody presses save and at no other moment.
 *
 * And it does not repair what it cannot read. A file that is not the document
 * this writes — a hand edit, a newer build's, a truncated write — is reported
 * and left exactly as it is. Rewriting it would destroy the only copy of
 * whatever it said, to fix a problem the application has already worked around
 * by using the default.
 *
 * Writes are serialised. Two saves cannot be in flight at once, so the file
 * always holds one of the values somebody asked for rather than the halves of
 * two. A read taken while a write is in flight waits for it, which is what makes
 * "save then read back" mean what it looks like.
 */
class DesktopSettingsStore(
    private val settingsFile: Path,
    private val writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = TEMPORARY_SUFFIX),
    private val diagnostics: Diagnostics = Diagnostics.None,
) : SettingsStore {
    private val oneAtATime = Mutex()

    /**
     * Whether a problem with the file has been recorded yet. The number is read
     * before every automatic backup, and a file that is broken once is broken
     * every time, so it is said once for the life of the process (PLAN 14.7.2).
     */
    private val readProblemRecorded = AtomicBoolean(false)

    override suspend fun read(): AutomaticBackupSettings =
        readFromDisk().also { settings ->
            val problem = settings.problem
            if (problem != null && readProblemRecorded.compareAndSet(false, true)) {
                diagnostics.recordSafely { DiagnosticRecord(DiagnosticEvent.SETTINGS_READ_PROBLEM, reason = problem) }
            }
        }

    private suspend fun readFromDisk(): AutomaticBackupSettings =
        oneAtATime.withLock {
            withContext(Dispatchers.IO) {
                // Not there is not a problem: it is what a machine looks like
                // before anybody has chosen anything.
                if (!Files.exists(settingsFile)) return@withContext AutomaticBackupSettings()
                val text =
                    try {
                        Files.readString(settingsFile)
                    } catch (notText: MalformedInputException) {
                        // Bytes that are not UTF-8 at all. Its own case only
                        // because `readString` answers with it before any of
                        // this gets to look at the contents.
                        return@withContext AutomaticBackupSettings(problem = SettingsProblem.NOT_THE_EXPECTED_SHAPE)
                    } catch (couldNotRead: IOException) {
                        return@withContext AutomaticBackupSettings(problem = SettingsProblem.COULD_NOT_READ)
                    }
                settingsIn(text)
            }
        }

    override suspend fun write(automaticBackupCount: Int) {
        require(isKeepableCount(automaticBackupCount)) { "A count outside the allowed range is never written" }
        val document = settingsDocumentFor(automaticBackupCount)
        oneAtATime.withLock {
            withContext(Dispatchers.IO) {
                try {
                    writer.write(settingsFile, document.encodeToByteArray())
                } catch (refused: AtomicWriteException) {
                    val failure = writeFailureOf(refused.failure)
                    diagnostics.recordSafely {
                        DiagnosticRecord(DiagnosticEvent.SETTINGS_WRITE_FAILED, reason = failure, failure = refused)
                    }
                    throw SettingsNotSaved(failure, refused)
                }
            }
        }
    }
}

/**
 * The writer's five outcomes as the two a person acts on differently.
 *
 * A backup keeps all five because its destination is one the user picked and
 * they may pick another. A setting always goes to the same folder, so the only
 * question worth answering is whether that folder will take a file at all.
 */
private fun writeFailureOf(failure: AtomicWriteFailure): SettingsWriteFailure =
    when (failure) {
        AtomicWriteFailure.NOT_WRITABLE -> SettingsWriteFailure.NOT_WRITABLE
        AtomicWriteFailure.TARGET_UNAVAILABLE -> SettingsWriteFailure.NOT_WRITABLE
        AtomicWriteFailure.TEMPORARY_FILE_FAILED -> SettingsWriteFailure.COULD_NOT_WRITE
        AtomicWriteFailure.WRITE_FAILED -> SettingsWriteFailure.COULD_NOT_WRITE
        AtomicWriteFailure.NOT_ATOMIC -> SettingsWriteFailure.COULD_NOT_WRITE
    }
