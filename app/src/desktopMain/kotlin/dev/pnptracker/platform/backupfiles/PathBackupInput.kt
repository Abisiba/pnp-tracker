package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.restore.BackupBytes
import dev.pnptracker.domain.backup.restore.BackupInput
import dev.pnptracker.domain.backup.restore.BackupInputException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A backup file on this machine, offered upwards as bytes and a name.
 *
 * The desktop end of reading, and the mirror of what `DesktopBackupFileGateway`
 * does for writing: the [Path] lives in here and nothing above it ever sees one.
 * Only the file name crosses the line, so no absolute path can reach a screen, a
 * message or a log through this route (PLAN 14.4.5).
 *
 * Nothing here decides how much to read. The reader above applies the 64 MiB
 * limit and asks for one chunk at a time; this hands over what it is asked for
 * and closes the stream when told to. That division is on purpose: a limit
 * enforced by the side that owns the file would be a limit each platform could
 * get wrong differently.
 *
 * Every `IOException` is turned into a [BackupInputException], which carries
 * nothing. The message of the original names the path it failed on, and that is
 * exactly what must not travel.
 */
class PathBackupInput(
    private val file: Path,
) : BackupInput {
    override val fileName: String = file.fileName?.toString().orEmpty()

    override suspend fun declaredSize(): Long =
        withContext(Dispatchers.IO) {
            try {
                Files.size(file)
            } catch (unreachable: IOException) {
                throw BackupInputException(unreachable)
            }
        }

    override suspend fun open(): BackupBytes =
        withContext(Dispatchers.IO) {
            try {
                StreamedBytes(Files.newInputStream(file))
            } catch (unopenable: IOException) {
                throw BackupInputException(unopenable)
            }
        }
}

private class StreamedBytes(
    private val stream: InputStream,
) : BackupBytes {
    override suspend fun read(into: ByteArray): Int =
        withContext(Dispatchers.IO) {
            try {
                stream.read(into)
            } catch (unreadable: IOException) {
                throw BackupInputException(unreadable)
            }
        }

    /**
     * Closes the file, and says nothing if it cannot.
     *
     * This is called on every path, including the ones already on their way to
     * refusing the file. A failure to close has no bearing on whether the backup
     * was good, and letting one throw here would replace the real answer with a
     * worse one.
     */
    override suspend fun close() {
        withContext(Dispatchers.IO) {
            try {
                stream.close()
            } catch (ignored: IOException) {
                // Nothing to do and nothing to say.
            }
        }
    }
}
