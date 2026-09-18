package dev.pnptracker.platform.diagnostics

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.domain.diagnostics.LONGEST_DIAGNOSTIC_LINE_BYTES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * One test's state home: `<tmp>/state/pnp-tracker/logs`, never the real one.
 *
 * Closing it puts write permission back on anything a test took it from and
 * deletes only the temporary directory it made. It also proves the real XDG
 * locations this application uses were left exactly as they were — their
 * existence, size and modification time, looked at without opening anything.
 */
class LogHome : AutoCloseable {
    private val temporary = TemporaryDatabaseDirectory()
    private val realBefore = realApplicationLocations()

    val root: Path = temporary.root
    val stateDirectory: Path = root.resolve("state/pnp-tracker")
    val logs: Path = stateDirectory.resolve("logs")

    fun fileSystem(): NioDiagnosticLogFileSystem = NioDiagnosticLogFileSystem(logs)

    fun sink(files: DiagnosticLogFileSystem = fileSystem()): DiagnosticLogSink = DiagnosticLogSink(files)

    fun file(name: String): Path = logs.resolve(name)

    /** Every entry in the log folder, by name, without following links. */
    fun names(): List<String> =
        if (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)) {
            emptyList()
        } else {
            Files.list(logs).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
        }

    /** The owned log files that exist, newest first. */
    fun ownedFiles(): List<String> = OWNED_LOG_NAMES.filter { Files.isRegularFile(file(it), LinkOption.NOFOLLOW_LINKS) }

    override fun close() {
        if (Files.exists(root)) {
            Files.walk(root).use { entries ->
                entries
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .forEach { runCatching { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) } }
            }
        }
        temporary.delete()
        assertEquals(realBefore, realApplicationLocations(), "a test changed the real application's files")
    }

    companion object {
        /** What the real XDG locations look like from outside: exists, size, mtime. */
        fun realApplicationLocations(): Map<String, String> {
            val home = Path.of(System.getProperty("user.home"))
            val locations =
                listOf(
                    home.resolve(".local/share/pnp-tracker"),
                    home.resolve(".local/share/pnp-tracker/pnp.db"),
                    home.resolve(".local/share/pnp-tracker/pnp.db.lck"),
                    home.resolve(".local/share/pnp-tracker/pnp-baslangic.lock"),
                    home.resolve(".local/share/pnp-tracker/backups"),
                    home.resolve(".config/pnp-tracker"),
                    home.resolve(".config/pnp-tracker/settings.json"),
                    home.resolve(".local/state/pnp-tracker"),
                    home.resolve(".local/state/pnp-tracker/logs"),
                )
            return locations.associate { path ->
                path.toString() to
                    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        "absent"
                    } else {
                        val entries = if (Files.isDirectory(path)) Files.list(path).use { it.count() } else -1
                        "${Files.size(path)} ${Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)} $entries"
                    }
            }
        }
    }
}

val testApp: AppInfo = AppInfo(id = "pnp-tracker", version = "0.1.0")

/** A syntactically complete line of exactly [bytes] bytes, tagged so it can be found again. */
fun paddedLine(
    tag: String,
    bytes: Int = LONGEST_DIAGNOSTIC_LINE_BYTES,
): ByteArray {
    val head = "{\"tag\":\"$tag\",\"pad\":\""
    val tail = "\"}\n"
    val padding = bytes - head.length - tail.length
    require(padding >= 0) { "a $bytes byte line cannot hold the tag $tag" }
    return (head + "x".repeat(padding) + tail).encodeToByteArray().also { check(it.size == bytes) }
}

/** The lines of a log file, each parsed on its own; a line that is not JSON is returned as null. */
fun linesOf(file: Path): List<JsonObject?> =
    Files.readAllLines(file).map { line -> runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() }

/** The tag of a [paddedLine]. */
fun JsonObject.tag(): String? = (this["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content

/**
 * A disk in front of the real one that can fail, or stall, chosen operations.
 *
 * The production writer cannot tell it apart from the real disk: this is how a
 * full disk, a refused rename or a stuck write is put in front of it without a
 * single hook in production code.
 */
class FaultyLogFileSystem(
    private val real: DiagnosticLogFileSystem,
) : DiagnosticLogFileSystem {
    enum class Operation { PREPARE, LOCK, KIND, OPEN, APPEND, DELETE, MOVE }

    /** Operations that throw an IOException every time. */
    @Volatile
    var failing: Set<Operation> = emptySet()

    /** Operations that throw a RuntimeException — a defect rather than a disk. */
    @Volatile
    var brokenCode: Set<Operation> = emptySet()

    /**
     * When set, appends wait until it is counted down or the channel is closed —
     * every append after the first [appendsBeforeGate], which go straight through.
     */
    @Volatile
    var appendGate: CountDownLatch? = null

    @Volatile
    var appendsBeforeGate: Int = 0

    @Volatile
    var calls: Int = 0
        private set

    private val appendsStarted = AtomicInteger()

    /** Every line that reached the disk, in the order it did. */
    private val appended = LinkedBlockingQueue<String>()

    private val written = AtomicInteger()

    /** Opened by the first line on disk, or by the lock being refused, whichever comes first. */
    private val firstOutcome = CountDownLatch(1)

    /** How many lines reached the disk through this file system so far. */
    val linesWritten: Int
        get() = written.get()

    /**
     * Waits until the first line is on disk (true) or another process was found
     * holding the lock (false). The bound only turns a hang into a failure.
     */
    fun awaitFirstLineOrRefusal(): Boolean {
        check(firstOutcome.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)) { "neither a line nor a refusal ever came" }
        return written.get() > 0
    }

    /** One permit for every append that is being held at [appendGate]. */
    private val held = Semaphore(0)

    /**
     * Waits until the next [count] lines have reached the disk, and returns them.
     *
     * This is how a test knows the worker has written something without asking
     * how fast this machine writes. The bound is not a measurement: it is there
     * so that a writer that never gets there fails the test instead of hanging it.
     */
    fun awaitLines(count: Int): List<String> =
        List(count) { index ->
            checkNotNull(appended.poll(HANG_GUARD_SECONDS, TimeUnit.SECONDS)) { "only $index of $count lines ever reached the disk" }
        }

    /** Waits for lines until one contains [text]; returns every line up to and including it. */
    fun awaitLineWith(text: String): List<String> {
        val seen = mutableListOf<String>()
        while (seen.lastOrNull()?.contains(text) != true) {
            seen += checkNotNull(appended.poll(HANG_GUARD_SECONDS, TimeUnit.SECONDS)) { "no line with $text ever reached the disk" }
        }
        return seen
    }

    /** Waits until an append is being held at the gate: the worker is inside a write and cannot move. */
    fun awaitHeldAtGate() {
        check(held.tryAcquire(HANG_GUARD_SECONDS, TimeUnit.SECONDS)) { "no append ever reached the gate" }
    }

    private fun visit(operation: Operation) {
        synchronized(this) { calls++ }
        if (operation in brokenCode) throw IllegalStateException("broken on purpose")
        if (operation in failing) throw IOException("disk says no")
    }

    override fun prepareDirectory() {
        visit(Operation.PREPARE)
        real.prepareDirectory()
    }

    override fun tryLock(): AutoCloseable? {
        visit(Operation.LOCK)
        return real.tryLock().also { if (it == null) firstOutcome.countDown() }
    }

    override fun kindOf(name: String): LogEntryKind {
        visit(Operation.KIND)
        return real.kindOf(name)
    }

    override fun openForAppend(
        name: String,
        createNew: Boolean,
    ): LogAppendChannel {
        visit(Operation.OPEN)
        val channel = real.openForAppend(name, createNew)
        return object : LogAppendChannel {
            @Volatile
            private var closed = false

            override fun size(): Long = channel.size()

            override fun lastByte(): Int? = channel.lastByte()

            override fun append(bytes: ByteArray) {
                visit(Operation.APPEND)
                val gate = appendGate
                if (gate != null && appendsStarted.getAndIncrement() >= appendsBeforeGate) {
                    held.release()
                    while (!gate.await(10, TimeUnit.MILLISECONDS)) {
                        if (closed) throw IOException("closed while waiting")
                    }
                }
                channel.append(bytes)
                written.incrementAndGet()
                appended.put(bytes.decodeToString())
                firstOutcome.countDown()
            }

            override fun close() {
                closed = true
                channel.close()
            }
        }
    }

    override fun delete(name: String) {
        visit(Operation.DELETE)
        real.delete(name)
    }

    override fun moveWithoutReplacing(
        from: String,
        to: String,
    ) {
        visit(Operation.MOVE)
        real.moveWithoutReplacing(from, to)
    }

    private companion object {
        const val HANG_GUARD_SECONDS = 300L
    }
}
