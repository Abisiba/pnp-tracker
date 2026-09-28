package dev.pnptracker.platform.recovery

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.AppPaths
import dev.pnptracker.platform.files.AppPathsResolver
import dev.pnptracker.platform.files.PlatformFileRules
import dev.pnptracker.platform.files.XDG_LAYOUT
import dev.pnptracker.platform.startup.INSTANCE_LOCK_NAME
import dev.pnptracker.platform.startup.InstanceLock
import dev.pnptracker.platform.startup.deleteTemporaryTree
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A home of its own for one test: data, config and a temporary directory, all
 * under the system temporary directory and all swept afterwards.
 *
 * Nothing here can reach the real application: the child process is started
 * with `XDG_DATA_HOME`, `XDG_CONFIG_HOME` and `java.io.tmpdir` pointing inside
 * this home, and the test itself resolves its paths from the same three.
 */
class RecoveryHome : AutoCloseable {
    val root: Path = Files.createTempDirectory("pnp-tracker-recovery")
    val temporary: Path = Files.createDirectories(root.resolve("tmp"))
    private val realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
    private val realFootprintBefore = realFootprint()
    private val children = mutableListOf<WriterProcess>()

    val paths: AppPaths =
        AppPathsResolver(
            systemProperty = XDG_LAYOUT,
            environment = { name ->
                when (name) {
                    "XDG_DATA_HOME" -> root.resolve("data").toString()
                    "XDG_CONFIG_HOME" -> root.resolve("config").toString()
                    else -> null
                }
            },
        ).resolve().also { AppDirectoryInitializer().ensureDirectories(it) }

    /** A throwaway directory for a probe, inside this home. */
    fun probeDirectory(): Path = Files.createTempDirectory(temporary, "probe")

    /** Opens the database through the gate, does [work], and closes it the way the window does. */
    fun <T> withDatabase(work: suspend (AppDatabase) -> T): T {
        val opened = gateFor(paths, temporaryDirectory = ::probeDirectory).open()
        assertNull(opened.set, "a database on this build's schema was given a migration snapshot")
        return try {
            runBlocking { work(opened.database) }
        } finally {
            opened.database.close()
        }
    }

    /**
     * Opens the database again after whatever happened to it, and says what is there.
     *
     * Through the gate — the only way the application opens it — and then the two
     * questions SQLite answers about itself, asked on the writer connection.
     */
    fun reopen(): Reopened =
        withDatabase { database ->
            val foreignKeys = database.useWriterConnection { it.usePrepared("PRAGMA foreign_key_check") { s -> rows(s) } }
            val integrity = database.useWriterConnection { it.usePrepared("PRAGMA integrity_check") { s -> rows(s) } }
            val data = everythingIn(database)
            Reopened(data, fingerprintOf(data), foreignKeys, integrity, durabilityOf(database))
        }

    /** Starts the application in a process of its own, about to make [write]. */
    fun start(
        write: InterruptedWrite,
        ending: Ending,
    ): WriterProcess {
        val java = PlatformFileRules.javaLauncher().toString()
        val builder =
            ProcessBuilder(
                java,
                // No hsperfdata file for a killed process to leave behind.
                "-XX:-UsePerfData",
                "-Djava.io.tmpdir=$temporary",
                // The child's protocol lines carry Turkish letters and the parent
                // reads them as UTF-8. Left alone, a Windows JVM would write them
                // in the console's own code page and `figür` would arrive broken.
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-cp",
                System.getProperty("java.class.path"),
                "dev.pnptracker.platform.recovery.InterruptedWriterKt",
                write.name,
                ending.name,
            ).redirectErrorStream(true)
        builder.environment().apply {
            put("XDG_DATA_HOME", root.resolve("data").toString())
            put("XDG_CONFIG_HOME", root.resolve("config").toString())
        }
        return WriterProcess(builder.start(), paths.dataDirectory.resolve(INSTANCE_LOCK_NAME)).also(children::add)
    }

    /** Every file and directory under the data and config directories, relative to this home. */
    fun filesOnDisk(): List<String> =
        listOf(paths.dataDirectory, paths.configDirectory)
            .flatMap { top ->
                Files.walk(top).use { entries ->
                    entries
                        .filter { it != top }
                        // Joined with `/` rather than the system's separator: these
                        // names are what the tests compare against, and a file's
                        // place in this home is the same fact on both systems.
                        .map { it.relativeTo(root).joinToString("/") + if (Files.isDirectory(it)) "/" else "" }
                        .toList()
                }
            }.sorted()

    override fun close() {
        // Whatever a failed test left running goes first, and is seen to go.
        children.forEach { it.kill() }
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "a test changed whether the real application database exists",
        )
        assertEquals(realFootprintBefore, realFootprint(), "a test touched the real application's files")
        deleteTemporaryTree(root)
    }

    private fun rows(statement: SQLiteStatement): List<String> =
        buildList {
            while (statement.step()) {
                add((0 until statement.getColumnCount()).joinToString("|") { statement.getText(it) })
            }
        }
}

/**
 * The real application's own files, described without opening any of them.
 *
 * The paths are resolved from this JVM's real environment — the same answer
 * `Main` would get — and only their existence, size and modification time are
 * read. Nothing is created: resolving a path writes nothing, and no directory
 * initialiser runs here.
 */
fun realFootprint(): List<String> {
    val real =
        AppPathsResolver(
            systemProperty = XDG_LAYOUT,
        ).resolve()
    return listOf(
        real.databaseFile,
        Path.of("${real.databaseFile}-wal"),
        Path.of("${real.databaseFile}-shm"),
        Path.of("${real.databaseFile}.lck"),
        real.dataDirectory.resolve(INSTANCE_LOCK_NAME),
        real.backupsDirectory,
        real.configDirectory,
        real.settingsFile,
    ).map { path ->
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            "${path.name} absent"
        } else {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val entries = if (attributes.isDirectory) " entries=${path.namesInside().size}" else ""
            "${path.name} size=${attributes.size()} modified=${attributes.lastModifiedTime()}$entries"
        }
    }
}

/** What is in the database when it is opened again. */
data class Reopened(
    val data: BackupData,
    val fingerprint: String,
    val foreignKeyProblems: List<String>,
    val integrity: List<String>,
    val durability: Durability,
) {
    /** The two things SQLite says about a database that came through intact. */
    fun assertWhole() {
        assertEquals(emptyList(), foreignKeyProblems, "foreign_key_check found rows pointing at nothing")
        assertEquals(listOf("ok"), integrity, "integrity_check found damage")
    }
}

/**
 * The child process, and the lines it has said.
 *
 * Lines are read on a thread of their own into a queue, so waiting for one is
 * waiting for an *event*: the test goes on the moment the line arrives. The
 * limit on that wait is a guard against a test that would otherwise hang for
 * ever if the child broke, not part of what is being measured — nothing here
 * depends on how long anything takes.
 */
class WriterProcess(
    private val process: Process,
    private val instanceLock: Path,
) {
    private val lines = LinkedBlockingQueue<String>()
    private val transcript = StringBuilder()

    init {
        Thread {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { all ->
                all.forEach { line ->
                    synchronized(transcript) { transcript.appendLine(line) }
                    if (line.startsWith(PROTOCOL)) lines.put(line.removePrefix(PROTOCOL))
                }
            }
            lines.put(END_OF_OUTPUT)
        }.apply { isDaemon = true }.start()
    }

    /** The rest of the next protocol line, which must begin with [word]. */
    fun awaitLine(word: String): String {
        val line = lines.poll(GUARD_MINUTES, TimeUnit.MINUTES) ?: fail("the child said nothing for $GUARD_MINUTES minutes:\n${said()}")
        if (line == END_OF_OUTPUT) fail("the child ended before saying $word:\n${said()}")
        if (!line.startsWith("$word ")) fail("the child said '$line' where '$word' was expected:\n${said()}")
        return line.removePrefix("$word ")
    }

    /**
     * Kills the process the way the kernel does when power or patience runs out —
     * `SIGKILL`, no shutdown hooks, no `finally` — and waits until it is gone.
     */
    fun kill() {
        if (process.isAlive) process.destroyForcibly()
        assertTrue(process.waitFor(GUARD_MINUTES, TimeUnit.MINUTES), "the child did not die:\n${said()}")
        assertFalse(process.isAlive)
        // The child is gone. Whatever the system had hanging off it is the
        // system's to clear, and Windows finishes that a little after the process
        // itself: this read as a process left behind once in eight otherwise
        // identical kills. So it is given a bounded while to finish, and if
        // anything is still there the failure says what it was rather than only
        // that there was something.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REAPING_SECONDS)
        var left = process.descendants().toList()
        while (left.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(REAPING_POLL_MILLISECONDS)
            left = process.descendants().toList()
        }
        assertTrue(
            left.isEmpty(),
            "the child left processes of its own behind: ${left.map { it.info().command().orElse(it.pid().toString()) }}",
        )
        awaitTheSystemLettingGo()
    }

    /**
     * Waits until the system has given back what the killed child was holding.
     *
     * Linux gives it all back with the process. Windows releases a dead process's
     * handles a moment after the process itself is reported gone, and a start that
     * came inside that moment was refused — once as "another copy is running" and
     * once, further in, as a migration that failed. Asked here with the
     * application's own lock, which is the same handle question, and counted
     * rather than slept through, so what a test asserts afterwards is the recovery
     * and not the timing (PLAN 14.8.4).
     */
    private fun awaitTheSystemLettingGo() {
        fun letGo(): Boolean = runCatching { InstanceLock(instanceLock).withLock { true } == true }.getOrDefault(false)

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REAPING_SECONDS)
        var waits = 0
        while (!letGo()) {
            assertTrue(System.nanoTime() < deadline, "the system never gave back what the killed child held")
            waits += 1
            Thread.sleep(REAPING_POLL_MILLISECONDS)
        }
        if (waits > 0) println("KILL: the system let go after $waits wait(s) of $REAPING_POLL_MILLISECONDS ms")
    }

    /** Waits for a process that exits by itself, and gives its exit code. */
    fun awaitExit(): Int {
        assertTrue(process.waitFor(GUARD_MINUTES, TimeUnit.MINUTES), "the child did not exit:\n${said()}")
        return process.exitValue()
    }

    /**
     * Whether the child was ended by the system rather than ending itself.
     *
     * Linux reports a signalled death as 128 plus the signal. Windows has no
     * signals: `destroyForcibly` calls `TerminateProcess` and the exit code is
     * the one the JVM asks it to use. What both answers have in common is the
     * one that matters — it is not the zero of a process that closed itself —
     * and these children are killed while standing inside a transaction, so
     * they have no path of their own to any exit at all (PLAN 14.8.5).
     */
    val wasKilled: Boolean get() = !process.isAlive && process.exitValue() == killedExitCode

    val pid: Long get() = process.pid()

    private fun said(): String = synchronized(transcript) { transcript.toString() }

    private companion object {
        const val END_OF_OUTPUT = " end"
        const val GUARD_MINUTES = 3L

        /** 128 + 9: how the JVM reports a child that `SIGKILL` ended. */
        const val SIGKILL_EXIT = 137

        /** What `TerminateProcess` leaves behind, which is what Java asks it for. */
        const val TERMINATED_EXIT = 1

        /** How long the system may take to finish clearing a dead child's own children. */
        const val REAPING_SECONDS = 10L
        const val REAPING_POLL_MILLISECONDS = 50L

        val killedExitCode: Int get() = if (PlatformFileRules.onWindows) TERMINATED_EXIT else SIGKILL_EXIT
    }
}

/** The name the bundled SQLite driver unpacks its native library under. */
val DRIVERS_UNPACKED_NATIVE: Regex = Regex("androidx_sqliteJni\\d+\\.tmp")

/**
 * How many files the driver's own exit handling leaves behind on a normal close.
 *
 * Linux deletes the unpacked native library as the process goes. Windows cannot
 * delete a library that is still mapped into the process that is exiting, so
 * that one file stays: it sits in the temporary directory, holds no user data,
 * and nothing in the application reads it as a sign of anything. It is the same
 * single file a killed process leaves on either system (PLAN 14.8.4).
 */
fun nativesLeftByANormalExit(): Int = if (PlatformFileRules.onWindows) 1 else 0

/** Only the names, for asserting on what a folder holds. */
fun Path.namesInside(): List<String> = Files.list(this).use { entries -> entries.map { it.name }.sorted().toList() }
