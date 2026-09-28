package dev.pnptracker.platform.settings

import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageReadFailed
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.settings.Appearance
import dev.pnptracker.domain.settings.AppearanceNotSaved
import dev.pnptracker.domain.settings.AppearanceProblem
import dev.pnptracker.domain.settings.AppearanceStore
import dev.pnptracker.domain.settings.SettingsWriteFailure
import dev.pnptracker.domain.settings.appearanceDocumentFor
import dev.pnptracker.domain.settings.appearanceIn
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

/** What a half-written appearance file is called while it is still half written. */
private const val TEMPORARY_SUFFIX = ".json.part"

/**
 * Carries only its own name into a diagnostic line, which is the point.
 *
 * A file that is not the document this writes has no exception of its own, and
 * the shared storage record wants one. Nothing it holds is written down — the line
 * keeps class names and the reason enum, never contents — so a hand edited file
 * cannot travel into a log through here.
 */
private class AppearanceNotUnderstood : Exception("The appearance file is not the document this writes")

/**
 * How the application looks on this machine, and the only place it is touched.
 *
 * Built to the same three promises as the other two files (PLAN 14.4.12, 12.17):
 * reading never creates it, a file that cannot be understood is left exactly as it
 * is, and writes are serialised so it always holds one whole document.
 *
 * A write failure **is** raised here, unlike the table's sizes. Somebody pressed a
 * control and asked for this to be remembered, so being told it was not is worth a
 * line on the screen; what is on screen is already what they chose either way.
 */
class DesktopAppearanceStore(
    private val appearanceFile: Path,
    private val writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = TEMPORARY_SUFFIX),
    private val diagnostics: Diagnostics = Diagnostics.None,
) : AppearanceStore {
    private val oneAtATime = Mutex()

    /** A file that is broken once is broken every time, so it is said once. */
    private val readProblemRecorded = AtomicBoolean(false)

    override suspend fun read(): Appearance =
        readFromDisk().also { appearance ->
            val problem = appearance.problem
            if (problem != null && readProblemRecorded.compareAndSet(false, true)) {
                diagnostics.recordSafely {
                    storageReadFailed(DiagnosticArea.APPEARANCE, AppearanceNotUnderstood(), reason = problem)
                }
            }
        }

    private suspend fun readFromDisk(): Appearance =
        oneAtATime.withLock {
            withContext(Dispatchers.IO) {
                // Not there is not a problem: it is what a machine looks like
                // before anybody has chosen anything.
                if (!Files.exists(appearanceFile)) return@withContext Appearance()
                val text =
                    try {
                        Files.readString(appearanceFile)
                    } catch (notText: MalformedInputException) {
                        return@withContext Appearance(problem = AppearanceProblem.NOT_THE_EXPECTED_SHAPE)
                    } catch (couldNotRead: IOException) {
                        return@withContext Appearance(problem = AppearanceProblem.COULD_NOT_READ)
                    }
                appearanceIn(text)
            }
        }

    override suspend fun write(appearance: Appearance) {
        val document = appearanceDocumentFor(appearance)
        oneAtATime.withLock {
            withContext(Dispatchers.IO) {
                try {
                    writer.write(appearanceFile, document.encodeToByteArray())
                } catch (refused: AtomicWriteException) {
                    val failure = writeFailureOf(refused.failure)
                    diagnostics.recordSafely {
                        storageWriteFailed(DiagnosticArea.APPEARANCE, refused.failure, refused)
                    }
                    throw AppearanceNotSaved(failure, refused)
                }
            }
        }
    }
}

/**
 * The writer's five outcomes as the two a person acts on differently.
 *
 * The same two the settings file keeps, and for the same reason: the destination
 * is always the same folder, so the only question worth answering is whether that
 * folder will take a file at all.
 */
private fun writeFailureOf(failure: AtomicWriteFailure): SettingsWriteFailure =
    when (failure) {
        AtomicWriteFailure.NOT_WRITABLE -> SettingsWriteFailure.NOT_WRITABLE
        AtomicWriteFailure.TARGET_UNAVAILABLE -> SettingsWriteFailure.NOT_WRITABLE
        AtomicWriteFailure.TEMPORARY_FILE_FAILED -> SettingsWriteFailure.COULD_NOT_WRITE
        AtomicWriteFailure.WRITE_FAILED -> SettingsWriteFailure.COULD_NOT_WRITE
        AtomicWriteFailure.NOT_ATOMIC -> SettingsWriteFailure.COULD_NOT_WRITE
    }
