package dev.pnptracker.platform.diagnostics

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** The file being written to (PLAN 14.7.1). */
const val ACTIVE_LOG_NAME: String = "pnp-tanilama.jsonl"

/** The lock file the one writing process holds for as long as it lives. */
const val LOG_LOCK_NAME: String = "pnp-tanilama.lock"

/** How many files there are at most, the active one included. */
const val LOG_FILE_COUNT: Int = 5

/** The largest a log file is allowed to grow. */
const val LONGEST_LOG_FILE_BYTES: Long = 1024L * 1024L

/** The name of the [generation]th older file: 1 is the newest, 4 the oldest. */
fun olderLogName(generation: Int): String {
    require(generation in 1 until LOG_FILE_COUNT) { "There is no log generation $generation" }
    return "pnp-tanilama.$generation.jsonl"
}

/** Every name rotation may ever touch, newest first. Nothing else in the folder is ours. */
val OWNED_LOG_NAMES: List<String> = listOf(ACTIVE_LOG_NAME) + (1 until LOG_FILE_COUNT).map(::olderLogName)

/** What is at a name, seen without following a symbolic link. */
enum class LogEntryKind {
    ABSENT,
    REGULAR_FILE,

    /** A symbolic link, a directory, a FIFO or anything else that is not a plain file. */
    OTHER,
}

/** An open log file that bytes are only ever added to the end of. */
interface LogAppendChannel : AutoCloseable {
    /** How many bytes the file holds now. */
    fun size(): Long

    /** The file's last byte, or null when it is empty. */
    fun lastByte(): Int?

    /** Adds all of [bytes] to the end, or throws. */
    fun append(bytes: ByteArray)
}

/**
 * Everything the log writer does to a disk, and nothing more.
 *
 * Narrow on purpose: a writer that can only ask these questions and make these
 * changes cannot reach a file outside its folder, and a test can stand a failing
 * disk in front of it without the production code knowing a test exists.
 *
 * Every method may throw an [IOException]; the writer treats that as a failure to
 * log and never as a reason to fail anything else.
 */
interface DiagnosticLogFileSystem {
    /** Makes the log folder if it is missing; refuses a folder that is a link or not a folder. */
    fun prepareDirectory()

    /** Takes the process lock, or answers null when another process holds it. */
    fun tryLock(): AutoCloseable?

    fun kindOf(name: String): LogEntryKind

    /** Opens [name] for appending, making it first when [createNew] (and failing if it exists). */
    fun openForAppend(
        name: String,
        createNew: Boolean,
    ): LogAppendChannel

    fun delete(name: String)

    /** Renames [from] to [to] in one step, and never over something already there. */
    fun moveWithoutReplacing(
        from: String,
        to: String,
    )
}

/** Thrown when the folder is there but is not something this writer may use. */
class LogDirectoryUnusable : IOException("The diagnostic log folder is not a plain folder")

/**
 * The real disk, confined to one folder.
 *
 * Only the canonical names are accepted, so a caller cannot be talked into a
 * path elsewhere; every look and every open is done without following a link.
 */
class NioDiagnosticLogFileSystem(
    private val directory: Path,
) : DiagnosticLogFileSystem {
    override fun prepareDirectory() {
        // The application's own state folder is owner-only, like its data folder;
        // the folders above it (~/.local/state) are not ours and keep their defaults.
        val appDirectory = directory.parent
        if (appDirectory != null && !Files.isDirectory(appDirectory)) {
            appDirectory.parent?.let { Files.createDirectories(it) }
            createOwnerOnlyDirectory(appDirectory)
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) createOwnerOnlyDirectory(directory)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw LogDirectoryUnusable()
    }

    override fun tryLock(): AutoCloseable? {
        val channel =
            FileChannel.open(
                directory.resolve(LOG_LOCK_NAME),
                setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                *ownerOnlyFile(),
            )
        val lock: FileLock? =
            try {
                channel.tryLock()
            } catch (failure: IOException) {
                channel.close()
                throw failure
            } catch (heldHere: java.nio.channels.OverlappingFileLockException) {
                // This very process already holds it through another writer: one
                // writer per process is the rule, so the second one stands down.
                null
            }
        if (lock == null) {
            channel.close()
            return null
        }
        return AutoCloseable {
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    override fun kindOf(name: String): LogEntryKind {
        val path = pathOf(name)
        val attributes =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return LogEntryKind.ABSENT
            }
        return if (attributes.isRegularFile) LogEntryKind.REGULAR_FILE else LogEntryKind.OTHER
    }

    override fun openForAppend(
        name: String,
        createNew: Boolean,
    ): LogAppendChannel {
        val options =
            mutableSetOf<OpenOption>(
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS,
            )
        options += if (createNew) StandardOpenOption.CREATE_NEW else StandardOpenOption.CREATE
        val path = pathOf(name)
        val channel = FileChannel.open(path, options, *ownerOnlyFile())
        return NioAppendChannel(path, channel)
    }

    override fun delete(name: String) {
        Files.delete(pathOf(name))
    }

    override fun moveWithoutReplacing(
        from: String,
        to: String,
    ) {
        val target = pathOf(to)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(to)
        Files.move(pathOf(from), target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun pathOf(name: String): Path {
        require(name in OWNED_LOG_NAMES) { "Not a diagnostic log name" }
        val path = directory.resolve(name)
        check(path.parent == directory) { "A log name left its folder" }
        return path
    }

    private fun createOwnerOnlyDirectory(path: Path) {
        try {
            Files.createDirectory(path, *ownerOnlyDirectory())
        } catch (_: FileAlreadyExistsException) {
            // Somebody made it first; whether it is usable is checked by the caller.
        }
    }

    private fun ownerOnlyDirectory(): Array<FileAttribute<*>> = posix(OWNER_ONLY_DIRECTORY)

    private fun ownerOnlyFile(): Array<FileAttribute<*>> = posix(OWNER_ONLY_FILE)

    private fun posix(permissions: Set<PosixFilePermission>): Array<FileAttribute<*>> =
        if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(permissions))
        } else {
            emptyArray()
        }

    private class NioAppendChannel(
        private val path: Path,
        private val channel: FileChannel,
    ) : LogAppendChannel {
        override fun size(): Long = channel.size()

        override fun lastByte(): Int? {
            val size = channel.size()
            if (size == 0L) return null
            // A separate reader: an APPEND channel's reads are not guaranteed to
            // honour a position, and the writer must not move its own.
            return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { reader ->
                val one = ByteBuffer.allocate(1)
                reader.read(one, size - 1)
                one.get(0).toInt() and 0xFF
            }
        }

        override fun append(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        override fun close() {
            channel.close()
        }
    }

    private companion object {
        val OWNER_ONLY_DIRECTORY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
        val OWNER_ONLY_FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    }
}
