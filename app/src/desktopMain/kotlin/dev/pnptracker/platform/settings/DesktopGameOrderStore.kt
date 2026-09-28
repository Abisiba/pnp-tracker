package dev.pnptracker.platform.settings

import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.domain.diagnostics.storageReadFailed
import dev.pnptracker.domain.diagnostics.storageWriteFailed
import dev.pnptracker.domain.games.GameOrder
import dev.pnptracker.domain.games.GameOrderProblem
import dev.pnptracker.domain.games.GameOrderStore
import dev.pnptracker.domain.games.gameOrderDocumentFor
import dev.pnptracker.domain.games.gameOrderIn
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

/** What a half-written order file is called while it is still half written. */
private const val TEMPORARY_SUFFIX = ".json.part"

/** Carries only its own name into a diagnostic line, never the file's contents. */
private class GameOrderNotUnderstood : Exception("The game order file is not the document this writes")

/**
 * The order the user put their games in, on this machine, and the only place it
 * is touched.
 *
 * Built to the table sizes' promises and for the same reasons (PLAN 12.17,
 * 12.18): reading never creates the file, a file that cannot be understood is left
 * exactly as it is, writes are serialised so it always holds one whole document,
 * and a write that fails is recorded and not raised — the table is already in the
 * order the user gave it, and the only loss is that the next start forgets.
 */
class DesktopGameOrderStore(
    private val orderFile: Path,
    private val writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = TEMPORARY_SUFFIX),
    private val diagnostics: Diagnostics = Diagnostics.None,
) : GameOrderStore {
    private val oneAtATime = Mutex()
    private val readProblemRecorded = AtomicBoolean(false)

    override suspend fun read(): GameOrder =
        readFromDisk().also { order ->
            val problem = order.problem
            if (problem != null && readProblemRecorded.compareAndSet(false, true)) {
                diagnostics.recordSafely {
                    storageReadFailed(DiagnosticArea.GAME_TABLE, GameOrderNotUnderstood(), reason = problem)
                }
            }
        }

    private suspend fun readFromDisk(): GameOrder =
        oneAtATime.withLock {
            withContext(Dispatchers.IO) {
                // Not there is not a problem: nobody has moved a game yet.
                if (!Files.exists(orderFile)) return@withContext GameOrder()
                val text =
                    try {
                        Files.readString(orderFile)
                    } catch (notText: MalformedInputException) {
                        return@withContext GameOrder(problem = GameOrderProblem.NOT_THE_EXPECTED_SHAPE)
                    } catch (couldNotRead: IOException) {
                        return@withContext GameOrder(problem = GameOrderProblem.COULD_NOT_READ)
                    }
                gameOrderIn(text)
            }
        }

    override suspend fun write(order: GameOrder) {
        val document = gameOrderDocumentFor(order)
        oneAtATime.withLock {
            withContext(Dispatchers.IO) {
                try {
                    writer.write(orderFile, document.encodeToByteArray())
                } catch (refused: AtomicWriteException) {
                    diagnostics.recordSafely {
                        storageWriteFailed(DiagnosticArea.GAME_TABLE, refused.failure, refused)
                    }
                }
            }
        }
    }
}
