package dev.pnptracker.platform.settings

import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageReadFailed
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.games.TableSizes
import dev.pnptracker.domain.games.TableSizesProblem
import dev.pnptracker.domain.games.TableSizesStore
import dev.pnptracker.domain.games.tableSizesDocumentFor
import dev.pnptracker.domain.games.tableSizesIn
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.AtomicWriteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** What a half-written sizes file is called while it is still half written. */
private const val TEMPORARY_SUFFIX = ".json.part"

/**
 * Carries only its own name into a diagnostic line, which is the point.
 *
 * A file that is not the document this writes has no exception of its own to
 * report, and the shared storage record wants one. Nothing it holds is written
 * down — the line keeps class names and the reason enum, never contents — so a
 * hand edited file cannot travel into a log through here.
 */
private class TableSizesNotUnderstood : Exception("The table sizes file is not the document this writes")

/**
 * The table's sizes on this machine, and the only place they are touched.
 *
 * Built to the same three promises as the settings file (PLAN 14.4.12, PLAN
 * 12.17): reading never creates it, a file that cannot be understood is left
 * exactly as it is, and writes are serialised so the file always holds one whole
 * document.
 *
 * Where it differs is what a failure costs. A setting that will not save is told
 * to the user, because they asked for it and it did not happen. A layout that
 * will not save is not: the table is already drawn the way they dragged it, and
 * the only loss is that the next start forgets. So a write failure is recorded
 * and swallowed, and a read problem is recorded once for the life of the process
 * — a file that is broken once is broken every time.
 */
class DesktopTableSizesStore(
    private val sizesFile: Path,
    private val writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = TEMPORARY_SUFFIX),
    private val diagnostics: Diagnostics = Diagnostics.None,
) : TableSizesStore {
    private val oneAtATime = Mutex()
    private val readProblemRecorded = AtomicBoolean(false)

    override suspend fun read(): TableSizes =
        readFromDisk().also { sizes ->
            val problem = sizes.problem
            if (problem != null && readProblemRecorded.compareAndSet(false, true)) {
                diagnostics.recordSafely {
                    storageReadFailed(DiagnosticArea.GAME_TABLE, TableSizesNotUnderstood(), reason = problem)
                }
            }
        }

    private suspend fun readFromDisk(): TableSizes =
        oneAtATime.withLock {
            withContext(Dispatchers.IO) {
                // Not there is not a problem: it is what a machine looks like
                // before anybody has dragged anything.
                if (!Files.exists(sizesFile)) return@withContext TableSizes.Default
                val text =
                    try {
                        Files.readString(sizesFile)
                    } catch (notText: MalformedInputException) {
                        return@withContext TableSizes(problem = TableSizesProblem.NOT_THE_EXPECTED_SHAPE)
                    } catch (couldNotRead: IOException) {
                        return@withContext TableSizes(problem = TableSizesProblem.COULD_NOT_READ)
                    }
                tableSizesIn(text)
            }
        }

    override suspend fun write(sizes: TableSizes) {
        val document = tableSizesDocumentFor(sizes)
        oneAtATime.withLock {
            withContext(Dispatchers.IO) {
                try {
                    writer.write(sizesFile, document.encodeToByteArray())
                } catch (refused: AtomicWriteException) {
                    // Swallowed on purpose; see the class note. The file that was
                    // there before is untouched, and the table keeps the size the
                    // user just gave it for as long as the window is open.
                    diagnostics.recordSafely {
                        storageWriteFailed(DiagnosticArea.GAME_TABLE, refused.failure, refused)
                    }
                }
            }
        }
    }
}
