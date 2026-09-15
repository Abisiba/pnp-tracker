package dev.pnptracker.platform.diagnostics

import dev.pnptracker.domain.diagnostics.LONGEST_DIAGNOSTIC_LINE_BYTES
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file half of PLAN 14.7.1 on a real disk: whole lines only, five files of
 * at most 1 MiB, nothing that is not ours touched, and no failure ever thrown
 * back at the application.
 */
class DiagnosticLogSinkTest {
    private val home = LogHome()

    @AfterTest
    fun tidy() = home.close()

    private fun sizeOf(name: String): Long = Files.size(home.file(name))

    /** Writes whole 2 KiB lines until the active file holds [lines] of them. */
    private fun DiagnosticLogSink.fill(
        lines: Int,
        tag: String,
    ) = repeat(lines) { assertTrue(write(paddedLine("$tag-$it")), "line $tag-$it was not written") }

    @Test
    fun `nothing is made on disk until the first line, then the folder, the lock and the file are private`() {
        val sink = home.sink()
        assertFalse(Files.exists(home.stateDirectory), "a sink that has written nothing made a folder")

        assertTrue(sink.write(paddedLine("first", 64)))
        sink.close()

        assertEquals(listOf(ACTIVE_LOG_NAME, LOG_LOCK_NAME), home.names())
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.stateDirectory)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.logs)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.file(ACTIVE_LOG_NAME))))
        assertEquals("first", linesOf(home.file(ACTIVE_LOG_NAME)).single()?.tag())
    }

    @Test
    fun `a line is at most 2 KiB, ends in one line feed and holds no other`() {
        val sink = home.sink()

        assertTrue(sink.write(paddedLine("limit", LONGEST_DIAGNOSTIC_LINE_BYTES)))
        assertFalse(sink.write(paddedLine("over", LONGEST_DIAGNOSTIC_LINE_BYTES + 1)))
        assertFalse(sink.write("{\"no\":\"feed\"}".encodeToByteArray()))
        assertFalse(sink.write("{\"a\":1}\n{\"b\":2}\n".encodeToByteArray()))
        assertFalse(sink.write(ByteArray(0)))
        // Refusing a malformed line is not a failure of the disk.
        assertFalse(sink.isDisabled)
        sink.close()

        assertEquals(LONGEST_DIAGNOSTIC_LINE_BYTES.toLong(), sizeOf(ACTIVE_LOG_NAME))
    }

    @Test
    fun `a file may reach exactly 1 MiB, and the first line past it starts the next file`() {
        val sink = home.sink()
        val lineBytes = LONGEST_DIAGNOSTIC_LINE_BYTES
        val whole = (LONGEST_LOG_FILE_BYTES / lineBytes).toInt()
        check(whole * lineBytes.toLong() == LONGEST_LOG_FILE_BYTES)

        sink.fill(whole, "a")
        assertEquals(LONGEST_LOG_FILE_BYTES, sizeOf(ACTIVE_LOG_NAME))
        assertEquals(listOf(ACTIVE_LOG_NAME), home.ownedFiles(), "a file of exactly 1 MiB was rotated")

        assertTrue(sink.write(paddedLine("b-0", 64)))
        sink.close()

        assertEquals(listOf(ACTIVE_LOG_NAME, olderLogName(1)), home.ownedFiles())
        assertEquals(LONGEST_LOG_FILE_BYTES, sizeOf(olderLogName(1)))
        assertEquals(listOf("b-0"), linesOf(home.file(ACTIVE_LOG_NAME)).map { it?.tag() })
        assertEquals("a-${whole - 1}", linesOf(home.file(olderLogName(1))).last()?.tag(), "a line was split across files")
    }

    @Test
    fun `one byte short of room is not room`() {
        val sink = home.sink()
        sink.fill(511, "a")
        // 511 × 2 048 + 2 047 = 1 048 575: one byte under the limit.
        assertTrue(sink.write(paddedLine("almost", LONGEST_DIAGNOSTIC_LINE_BYTES - 1)))
        assertEquals(LONGEST_LOG_FILE_BYTES - 1, sizeOf(ACTIVE_LOG_NAME))
        assertEquals(listOf(ACTIVE_LOG_NAME), home.ownedFiles())

        assertTrue(sink.write(paddedLine("next", 64)))
        sink.close()

        assertEquals(LONGEST_LOG_FILE_BYTES - 1, sizeOf(olderLogName(1)))
        assertEquals(listOf("next"), linesOf(home.file(ACTIVE_LOG_NAME)).map { it?.tag() })
    }

    @Test
    fun `only five files are ever kept, newest first, each within its size`() {
        val sink = home.sink()
        val perFile = (LONGEST_LOG_FILE_BYTES / LONGEST_DIAGNOSTIC_LINE_BYTES).toInt()
        // Eight files' worth: three rotations more than the folder may hold.
        repeat(8) { file -> sink.fill(perFile, "f$file") }
        sink.close()

        assertEquals(OWNED_LOG_NAMES, home.ownedFiles())
        assertEquals(OWNED_LOG_NAMES.sorted() + LOG_LOCK_NAME, home.names().sorted())
        OWNED_LOG_NAMES.forEach { assertTrue(sizeOf(it) <= LONGEST_LOG_FILE_BYTES) }
        // The active file is the eighth, .1 the seventh … .4 the fourth.
        OWNED_LOG_NAMES.forEachIndexed { age, name ->
            val tags = linesOf(home.file(name)).map { checkNotNull(it).tag()!! }
            assertTrue(tags.all { it.startsWith("f${7 - age}-") }, "$name holds $tags")
            assertEquals(perFile, tags.size)
        }
    }

    @Test
    fun `files that are not exactly ours are never touched by rotation`() {
        val strangers =
            mapOf(
                "pnp-tanilama.jsonl.part" to "yarım",
                "pnp-tanilama.5.jsonl" to "beşinci",
                "pnp-tanilama.0.jsonl" to "sıfırıncı",
                "pnp-tanilamax.jsonl" to "benzer",
                "pnp-tanilama.1.jsonl.bak" to "yedek",
                "notlar.txt" to "kullanıcının notu",
            )
        Files.createDirectories(home.logs)
        strangers.forEach { (name, text) -> Files.writeString(home.logs.resolve(name), text) }

        val sink = home.sink()
        val perFile = (LONGEST_LOG_FILE_BYTES / LONGEST_DIAGNOSTIC_LINE_BYTES).toInt()
        repeat(7) { file -> sink.fill(perFile, "f$file") }
        sink.close()

        strangers.forEach { (name, text) -> assertEquals(text, Files.readString(home.logs.resolve(name)), "$name changed") }
        assertEquals(OWNED_LOG_NAMES, home.ownedFiles())
    }

    @Test
    fun `a link, a folder or a FIFO under one of our names stops logging and is left as it was`() {
        val outside = home.root.resolve("outside.txt").also { Files.writeString(it, "dokunma") }
        val cases: List<Pair<String, (Path) -> Unit>> =
            listOf(
                olderLogName(4) to { path -> Files.createSymbolicLink(path, outside) },
                olderLogName(2) to { path -> Files.createDirectory(path) },
                olderLogName(1) to { path -> makeFifo(path) },
                ACTIVE_LOG_NAME to { path -> Files.createSymbolicLink(path, outside) },
            )

        cases.forEach { (name, plant) ->
            val caseHome = LogHome()
            try {
                Files.createDirectories(caseHome.logs)
                val sink = caseHome.sink()
                if (name != ACTIVE_LOG_NAME) {
                    // A full active file, so the next line has to rotate.
                    sink.fill((LONGEST_LOG_FILE_BYTES / LONGEST_DIAGNOSTIC_LINE_BYTES).toInt(), "full")
                }
                plant(caseHome.file(name))
                val before = Files.readAttributes(caseHome.file(name), "*", LinkOption.NOFOLLOW_LINKS).keys

                assertFalse(sink.write(paddedLine("next")), "a line was written past $name")
                assertTrue(sink.isDisabled, "logging went on around $name")
                assertFalse(sink.write(paddedLine("later")))
                sink.close()

                assertTrue(Files.exists(caseHome.file(name), LinkOption.NOFOLLOW_LINKS), "$name was removed")
                assertEquals(before, Files.readAttributes(caseHome.file(name), "*", LinkOption.NOFOLLOW_LINKS).keys)
                assertEquals("dokunma", Files.readString(outside), "a link was followed")
                if (name != ACTIVE_LOG_NAME) {
                    assertTrue(Files.size(caseHome.file(ACTIVE_LOG_NAME)) <= LONGEST_LOG_FILE_BYTES)
                    assertEquals(listOf(ACTIVE_LOG_NAME), caseHome.ownedFiles().filter { it == ACTIVE_LOG_NAME })
                }
            } finally {
                caseHome.close()
            }
        }
    }

    @Test
    fun `a log folder that is a link is not followed`() {
        val elsewhere = Files.createDirectories(home.root.resolve("elsewhere"))
        Files.createDirectories(home.stateDirectory)
        Files.createSymbolicLink(home.logs, elsewhere)

        val sink = home.sink()
        assertFalse(sink.write(paddedLine("x", 64)))
        assertTrue(sink.isDisabled)
        sink.close()

        assertEquals(0, Files.list(elsewhere).use { it.count() }, "something was written through the link")
    }

    @Test
    fun `rotation is decided by names, whatever the modification times say`() {
        fun layOut(target: LogHome) {
            Files.createDirectories(target.logs)
            // The oldest file looks newest and the newest looks oldest.
            (1..3).forEach { generation ->
                Files.write(target.file(olderLogName(generation)), paddedLine("g$generation", 64))
            }
            Files.write(target.file(ACTIVE_LOG_NAME), paddedLine("active", 64))
        }

        val scrambled = LogHome()
        try {
            layOut(home)
            layOut(scrambled)
            val now = System.currentTimeMillis()
            OWNED_LOG_NAMES.filter { Files.exists(scrambled.file(it)) }.forEachIndexed { index, name ->
                Files.setLastModifiedTime(scrambled.file(name), FileTime.fromMillis(now - (10 - index) * 86_400_000L))
            }

            listOf(home, scrambled).forEach { target ->
                val sink = target.sink()
                sink.fill((LONGEST_LOG_FILE_BYTES / LONGEST_DIAGNOSTIC_LINE_BYTES).toInt(), "fill")
                assertTrue(sink.write(paddedLine("rotated", 64)))
                sink.close()
            }

            assertEquals(contentsOf(home), contentsOf(scrambled))
            assertEquals("active", linesOf(home.file(olderLogName(1))).first()?.tag())
            assertEquals("g3", linesOf(home.file(olderLogName(4))).single()?.tag())
        } finally {
            scrambled.close()
        }
    }

    @Test
    fun `a rotation cut short by a crash is finished by the next one, the same way every time`() {
        // What a crash between two steps leaves: .4 already deleted, .3 already
        // moved to .4, .2 not yet moved — so .3 is a gap — and the active file full.
        fun crashedLayout(target: LogHome) {
            Files.createDirectories(target.logs)
            Files.write(target.file(olderLogName(4)), paddedLine("was-3", 64))
            Files.write(target.file(olderLogName(2)), paddedLine("was-2", 64))
            Files.write(target.file(olderLogName(1)), paddedLine("was-1", 64))
            val full = DiagnosticLogSink(target.fileSystem())
            full.fill((LONGEST_LOG_FILE_BYTES / LONGEST_DIAGNOSTIC_LINE_BYTES).toInt(), "active")
            full.close()
        }

        val again = LogHome()
        try {
            listOf(home, again).forEach { target ->
                crashedLayout(target)
                val sink = target.sink()
                assertTrue(sink.write(paddedLine("after", 64)))
                sink.close()
            }

            assertEquals(contentsOf(home), contentsOf(again))
            assertEquals(OWNED_LOG_NAMES, home.ownedFiles())
            assertEquals("after", linesOf(home.file(ACTIVE_LOG_NAME)).single()?.tag())
            assertEquals("active-0", linesOf(home.file(olderLogName(1))).first()?.tag())
            assertEquals("was-1", linesOf(home.file(olderLogName(2))).single()?.tag())
            assertEquals("was-2", linesOf(home.file(olderLogName(3))).single()?.tag())
            // The gap is filled, so nothing had to give way.
            assertEquals("was-3", linesOf(home.file(olderLogName(4))).single()?.tag())
        } finally {
            again.close()
        }
    }

    @Test
    fun `a line cut off by a crash stays a fragment and every line before and after it survives`() {
        Files.createDirectories(home.logs)
        val whole = paddedLine("before", 64)
        Files.write(home.file(ACTIVE_LOG_NAME), whole + "{\"v\":1,\"seq\":9,\"at\":\"20".encodeToByteArray())

        val sink = home.sink()
        assertTrue(sink.write(paddedLine("after", 64)))
        sink.close()

        val lines = linesOf(home.file(ACTIVE_LOG_NAME))
        assertEquals(3, lines.size)
        assertEquals("before", lines[0]?.tag())
        assertEquals(null, lines[1], "the fragment was glued to something")
        assertEquals("after", lines[2]?.tag())
    }

    @Test
    fun `a fragment at the very end of a full file is rotated away rather than pushed past the limit`() {
        Files.createDirectories(home.logs)
        val filler = paddedLine("x").let { line -> ByteArray(0) + List(511) { line }.flatMap { it.asList() } }
        val fragment = ByteArray((LONGEST_LOG_FILE_BYTES - filler.size).toInt() - 10) { 'y'.code.toByte() }
        Files.write(home.file(ACTIVE_LOG_NAME), filler + fragment)
        val before = Files.readAllBytes(home.file(ACTIVE_LOG_NAME))

        val sink = home.sink()
        assertTrue(sink.write(paddedLine("next")))
        sink.close()

        assertContentEquals(before, Files.readAllBytes(home.file(olderLogName(1))))
        assertEquals("next", linesOf(home.file(ACTIVE_LOG_NAME)).single()?.tag())
    }

    @Test
    fun `a folder that cannot be written to fails quietly and gives up after three tries`() {
        Files.createDirectories(home.stateDirectory)
        Files.setPosixFilePermissions(home.stateDirectory, PosixFilePermissions.fromString("r-x------"))
        if (Files.isWritable(home.stateDirectory)) return // running as root: the file system will not refuse

        val sink = home.sink()
        repeat(FAILURES_BEFORE_GIVING_UP - 1) { assertFalse(sink.write(paddedLine("x", 64))) }
        assertFalse(sink.isDisabled)
        assertFalse(sink.write(paddedLine("x", 64)))
        assertTrue(sink.isDisabled)
        sink.close()
    }

    @Test
    fun `every disk operation can fail without an exception reaching the caller`() {
        FaultyLogFileSystem.Operation.entries.forEach { operation ->
            val caseHome = LogHome()
            try {
                val faulty = FaultyLogFileSystem(caseHome.fileSystem())
                val sink = caseHome.sink(faulty)
                val firstTouch = operation == FaultyLogFileSystem.Operation.PREPARE || operation == FaultyLogFileSystem.Operation.LOCK
                if (!firstTouch) {
                    // A full file and a full folder first, so the failing write has
                    // to rotate: delete, move, create, append.
                    Files.createDirectories(caseHome.logs)
                    (1..4).forEach { Files.write(caseHome.file(olderLogName(it)), paddedLine("old-$it", 64)) }
                    sink.fill((LONGEST_LOG_FILE_BYTES / LONGEST_DIAGNOSTIC_LINE_BYTES).toInt(), "full")
                }

                faulty.failing = setOf(operation)
                val answers = List(5) { sink.write(paddedLine("while-$operation")) }
                faulty.failing = emptySet()

                assertEquals(List(5) { false }, answers, "$operation failing still wrote")
                assertTrue(sink.isDisabled, "$operation failing did not end logging after repeated failures")
                sink.close()

                assertTrue(caseHome.ownedFiles().size <= LOG_FILE_COUNT)
                caseHome.ownedFiles().forEach { name ->
                    assertTrue(Files.size(caseHome.file(name)) <= LONGEST_LOG_FILE_BYTES)
                    assertTrue(linesOf(caseHome.file(name)).all { it != null }, "$operation left a broken line in $name")
                }
            } finally {
                caseHome.close()
            }
        }
    }

    @Test
    fun `a failure that clears lets the next line through`() {
        val faulty = FaultyLogFileSystem(home.fileSystem())
        val sink = home.sink(faulty)
        assertTrue(sink.write(paddedLine("one", 64)))

        faulty.failing = setOf(FaultyLogFileSystem.Operation.APPEND)
        assertFalse(sink.write(paddedLine("lost", 64)))
        assertFalse(sink.write(paddedLine("lost", 64)))
        faulty.failing = emptySet()
        assertTrue(sink.write(paddedLine("two", 64)))
        assertTrue(sink.write(paddedLine("three", 64)))
        sink.close()

        assertEquals(listOf("one", "two", "three"), linesOf(home.file(ACTIVE_LOG_NAME)).map { it?.tag() })
    }

    @Test
    fun `broken code below the sink is contained just like a broken disk`() {
        val faulty = FaultyLogFileSystem(home.fileSystem())
        faulty.brokenCode = setOf(FaultyLogFileSystem.Operation.KIND)
        val sink = home.sink(faulty)

        val answers = List(10) { sink.write(paddedLine("x", 64)) }
        sink.close()

        assertTrue(answers.none { it })
        assertTrue(sink.isDisabled)
        assertTrue(faulty.calls < 40, "the sink kept trying: ${faulty.calls} calls")
    }

    @Test
    fun `a second writer in the same process stands down instead of sharing the file`() {
        val first = home.sink()
        val second = home.sink()

        assertTrue(first.write(paddedLine("first", 64)))
        assertFalse(second.write(paddedLine("second", 64)))
        assertTrue(second.isDisabled)
        first.close()
        second.close()

        assertEquals(listOf("first"), linesOf(home.file(ACTIVE_LOG_NAME)).map { it?.tag() })
        // The lock file is never deleted, so every writer locks the same file.
        assertTrue(Files.exists(home.file(LOG_LOCK_NAME)))
        val third = home.sink()
        assertTrue(third.write(paddedLine("third", 64)), "the lock was not given back on close")
        third.close()
    }

    private fun contentsOf(target: LogHome): Map<String, List<String?>> =
        target.ownedFiles().associateWith { name -> linesOf(target.file(name)).map { it?.tag() } }

    private fun makeFifo(path: Path) {
        val made = ProcessBuilder("mkfifo", path.toString()).redirectErrorStream(true).start()
        check(made.waitFor(20, TimeUnit.SECONDS) && made.exitValue() == 0) { "mkfifo failed" }
    }
}
