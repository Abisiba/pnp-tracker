package dev.pnptracker.domain.backup.restore

/**
 * The largest backup this application will read: 64 MiB (PLAN 14.4.1).
 *
 * The limit is on the file, not on what the file turns out to contain, and it is
 * applied before anything is parsed. A backup of a real library is a few
 * megabytes at the outside; a file far past that is either not a backup or is
 * one nothing good will come of loading whole into memory.
 */
const val MAXIMUM_BACKUP_BYTES: Long = 64L * 1024L * 1024L

/** How much is read from the file at a time. */
private const val CHUNK_BYTES = 64 * 1024

/**
 * A backup file, seen from the side of the application that must not know what a
 * file is.
 *
 * Only the name crosses this line — never a path (PLAN 14.4.5). The size is
 * asked for separately from the bytes because the size settles whether the bytes
 * are worth asking for at all.
 */
interface BackupInput {
    /** The name to show, never a path. */
    val fileName: String

    /**
     * What the file system says the file is, in bytes, or a negative number when
     * it will not say.
     *
     * Treated as a hint and nothing more. It is measured before the file is
     * opened, so it can be stale by the time it is read, and it comes from
     * whatever is on the other side of this interface. The reading below stays
     * within the limit whatever this answers.
     *
     * @throws BackupInputException if the file cannot be reached at all.
     */
    suspend fun declaredSize(): Long

    /**
     * Opens the file for reading.
     *
     * @throws BackupInputException if it cannot be opened.
     */
    suspend fun open(): BackupBytes
}

/** An open backup file, read a chunk at a time and closed on every path. */
interface BackupBytes {
    /**
     * Fills as much of [into] as it can, and answers how many bytes that was, or
     * a negative number at the end of the file.
     *
     * @throws BackupInputException if the read fails.
     */
    suspend fun read(into: ByteArray): Int

    /** Releases the file. Called exactly once, however the reading ended. */
    suspend fun close()
}

/**
 * The one way a platform says a backup file could not be read.
 *
 * Common code cannot catch an `IOException`, and it should not want to: the
 * message of one names the path it failed on. So the platform catches its own
 * and throws this, which carries nothing.
 */
class BackupInputException(
    cause: Throwable? = null,
) : Exception("The backup file could not be read", cause)

/** A stage of reading a backup: what it produced, or why it stopped. */
internal sealed interface Checked<out T> {
    data class Passed<T>(
        val value: T,
    ) : Checked<T>

    data class Failed(
        val rejection: BackupRejection,
    ) : Checked<Nothing>
}

internal fun refuse(
    problem: BackupProblem,
    place: BackupPlace = BackupPlace.File,
): Checked.Failed = Checked.Failed(BackupRejection(problem, place))

/**
 * Reads the file, and stops the moment it is clear it is too big.
 *
 * Three things happen here in an order that matters.
 *
 * The declared size is looked at first, so a file the file system already says
 * is a gigabyte is refused without being opened. That is PLAN 14.4.1's "the
 * limit applies before parsing begins" at its cheapest.
 *
 * Then the bytes are read in chunks, counting as they come, and the count is
 * checked against the limit after every chunk. The declared size is not trusted
 * for this: it can be a lie, it can be stale, and a file can grow between being
 * measured and being read. At most [limit] + 1 bytes are ever accepted — one
 * past the limit is enough to know, and nothing beyond it is read or kept.
 *
 * And the file is closed on every path, including the one where it was refused
 * halfway through.
 *
 * An empty file comes back as its own answer rather than as broken JSON. It is
 * the shape a half-finished copy or an interrupted download leaves behind, and
 * "there is nothing in this file" is a more useful thing to be told than "this
 * is not valid JSON".
 */
internal suspend fun readWithinLimit(
    input: BackupInput,
    limit: Long = MAXIMUM_BACKUP_BYTES,
): Checked<ByteArray> {
    val declared =
        try {
            input.declaredSize()
        } catch (unreachable: BackupInputException) {
            return refuse(BackupProblem.UNREADABLE)
        }
    if (declared > limit) return refuse(BackupProblem.SAFETY_LIMIT)

    val bytes =
        try {
            readChunks(input, limit)
        } catch (unreadable: BackupInputException) {
            return refuse(BackupProblem.UNREADABLE)
        } ?: return refuse(BackupProblem.SAFETY_LIMIT)

    if (bytes.isEmpty()) return refuse(BackupProblem.EMPTY_FILE)
    return Checked.Passed(bytes)
}

/** The bytes, or null the instant there turn out to be more than [limit] of them. */
private suspend fun readChunks(
    input: BackupInput,
    limit: Long,
): ByteArray? {
    val stream = input.open()
    try {
        // Sized to the limit only as it fills, so a small file costs a small
        // buffer and a hostile one cannot make this allocate 64 MiB up front.
        var filled = 0
        var buffer = ByteArray(CHUNK_BYTES)
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            if (read == 0) continue
            if (filled.toLong() + read > limit) return null
            if (filled + read > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, filled + read))
            chunk.copyInto(buffer, destinationOffset = filled, startIndex = 0, endIndex = read)
            filled += read
        }
        return if (filled == buffer.size) buffer else buffer.copyOf(filled)
    } finally {
        stream.close()
    }
}
