package dev.pnptracker.platform.diagnostics

import dev.pnptracker.domain.diagnostics.LONGEST_DIAGNOSTIC_LINE_BYTES
import java.io.IOException

private const val LINE_FEED: Byte = '\n'.code.toByte()

/** How many failures in a row end logging for the rest of the process. */
const val FAILURES_BEFORE_GIVING_UP: Int = 3

/**
 * Puts whole lines at the end of the active log file and keeps the folder
 * within five files of at most 1 MiB each (PLAN 14.7.1).
 *
 * Used by one thread at a time — the writer's worker — and it never throws.
 * Every answer is a boolean: the line is on disk, or it is not and nothing else
 * in the application needs to know why.
 *
 * Nothing happens on disk until the first line: the folder, the lock and the
 * file are all made then, so a run in which nothing failed leaves no trace.
 */
class DiagnosticLogSink(
    private val files: DiagnosticLogFileSystem,
) : AutoCloseable {
    @Volatile
    private var lock: AutoCloseable? = null

    @Volatile
    private var active: LogAppendChannel? = null

    private var failuresInARow = 0

    /** True once this process will write no more lines, for whatever reason. */
    @Volatile
    var isDisabled: Boolean = false
        private set

    /**
     * Writes [line], which must be one line of at most 2 KiB ending in a line feed.
     *
     * @return whether the line is now in the active file.
     */
    fun write(line: ByteArray): Boolean {
        if (isDisabled || !isOneLine(line)) return false
        return try {
            writeOrThrow(line)
            failuresInARow = 0
            true
        } catch (refused: Refused) {
            disable()
            false
        } catch (unusable: LogDirectoryUnusable) {
            // A link or a file where the folder should be: not ours to follow.
            disable()
            false
        } catch (failure: Exception) {
            // IOException from the disk, or a defect below: either way the line is
            // lost, the handle is dropped so the next line starts clean, and a few
            // of these in a row are the end of logging rather than a loop.
            closeActive()
            failuresInARow++
            if (failuresInARow >= FAILURES_BEFORE_GIVING_UP) disable()
            false
        }
    }

    /**
     * Lets go of the file and the lock for good; safe to call from any thread,
     * and more than once. A closed sink writes nothing more.
     */
    override fun close() {
        isDisabled = true
        closeActive()
        val held = lock
        lock = null
        runCatching { held?.close() }
    }

    private fun writeOrThrow(line: ByteArray) {
        if (lock == null) {
            files.prepareDirectory()
            // Another process is the writer: this one stays quiet for good (PLAN
            // 14.7.1). The lock file is never deleted, so both always lock the
            // same file.
            lock = files.tryLock() ?: throw Refused()
        }
        var channel = active ?: openActive(line)
        if (channel.size() > 0 && channel.size() + line.size > LONGEST_LOG_FILE_BYTES) {
            rotate()
            channel = checkNotNull(active)
        }
        channel.append(line)
    }

    /**
     * Opens the active file where the last run left it.
     *
     * A file that does not end in a line feed was cut off in the middle of a
     * line. One line feed is added first so the fragment stays a fragment and
     * the next line starts on its own — unless that byte would itself push the
     * file over its size, in which case the file is rotated as it is.
     */
    private fun openActive(line: ByteArray): LogAppendChannel {
        when (files.kindOf(ACTIVE_LOG_NAME)) {
            LogEntryKind.OTHER -> throw Refused()
            LogEntryKind.ABSENT, LogEntryKind.REGULAR_FILE -> Unit
        }
        val channel = files.openForAppend(ACTIVE_LOG_NAME, createNew = false)
        active = channel
        val size = channel.size()
        if (size > 0 && channel.lastByte() != LINE_FEED.toInt()) {
            if (size + 1 + line.size > LONGEST_LOG_FILE_BYTES) {
                rotate()
                return checkNotNull(active)
            }
            channel.append(byteArrayOf(LINE_FEED))
        }
        return channel
    }

    /**
     * Shifts every file one name older and starts an empty active file.
     *
     * Only the five canonical names are looked at, each without following a
     * link. If any of them is a link, a folder or anything but a plain file,
     * nothing is deleted and logging stops: the limit is never exceeded and a
     * thing this writer did not make is never removed. The order is decided by
     * the names alone; modification times play no part.
     *
     * Every step is a single delete or rename, so a crash leaves at most a gap
     * in the names, which the next rotation fills. The oldest file is deleted
     * only when the file before it needs the name.
     */
    private fun rotate() {
        if (OWNED_LOG_NAMES.any { files.kindOf(it) == LogEntryKind.OTHER }) throw Refused()

        val oldest = olderLogName(LOG_FILE_COUNT - 1)
        for (generation in LOG_FILE_COUNT - 2 downTo 1) {
            val name = olderLogName(generation)
            if (files.kindOf(name) != LogEntryKind.REGULAR_FILE) continue
            val older = olderLogName(generation + 1)
            // The oldest file goes only when something actually needs its name;
            // a gap left by a crash is filled rather than widened.
            if (older == oldest && files.kindOf(oldest) == LogEntryKind.REGULAR_FILE) files.delete(oldest)
            files.moveWithoutReplacing(name, older)
        }
        closeActive()
        if (files.kindOf(ACTIVE_LOG_NAME) == LogEntryKind.REGULAR_FILE) {
            files.moveWithoutReplacing(ACTIVE_LOG_NAME, olderLogName(1))
        }
        active = files.openForAppend(ACTIVE_LOG_NAME, createNew = true)
    }

    private fun closeActive() {
        val open = active
        active = null
        runCatching { open?.close() }
    }

    private fun disable() {
        close()
    }

    private fun isOneLine(line: ByteArray): Boolean =
        line.isNotEmpty() &&
            line.size <= LONGEST_DIAGNOSTIC_LINE_BYTES &&
            line.last() == LINE_FEED &&
            line.indexOf(LINE_FEED) == line.size - 1

    /** Not a failure of the disk but a reason this process must not write at all. */
    private class Refused : IOException()
}
