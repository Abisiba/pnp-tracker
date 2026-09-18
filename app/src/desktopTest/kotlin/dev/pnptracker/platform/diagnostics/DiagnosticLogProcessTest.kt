package dev.pnptracker.platform.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two real processes and one log folder (PLAN 14.7.1, R15): one writer at a
 * time, never a torn or interleaved line, and a lock the operating system gives
 * back when its holder is killed.
 *
 * What is measured here is the process, the lock and the lines — never how many
 * lines a machine writes before its shutdown bound runs out. Every writer says
 * how many of its lines reached the disk, and the folder must hold exactly
 * those, whole, in order and numbered without a gap. How many that is depends on
 * the machine and on its power settings, and nothing here does.
 */
class DiagnosticLogProcessTest {
    private val home = LogHome()
    private val started = mutableListOf<Writer>()

    @AfterTest
    fun tidy() {
        started.forEach { writer ->
            if (writer.process.isAlive) writer.process.destroyForcibly().waitFor(20, TimeUnit.SECONDS)
        }
        home.close()
    }

    /** A second process, and the one reader of what it says. */
    private class Writer(
        val process: Process,
    ) {
        private val said: BufferedReader = process.inputStream.bufferedReader()

        fun says(): String = said.readLine() ?: "(nothing)"

        fun go() {
            process.outputStream.bufferedWriter().apply {
                write("go\n")
                flush()
            }
        }

        /** Waits for the process to end on its own and returns how many lines it says it wrote. */
        fun finishes(): Int {
            val last = said.readText().trim()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "the writer failed: $last")
            val done = Regex("DONE (\\d+)").matchEntire(last)
            return checkNotNull(done) { "the writer did not finish: $last" }.groupValues[1].toInt()
        }
    }

    private fun start(vararg args: String): Writer {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                "dev.pnptracker.platform.diagnostics.DiagnosticLogWriterProcessKt",
                *args,
            ).redirectErrorStream(true)
        builder.environment()["XDG_STATE_HOME"] = home.root.resolve("state").toString()
        builder.environment()["XDG_DATA_HOME"] = home.root.resolve("data").toString()
        builder.environment()["XDG_CONFIG_HOME"] = home.root.resolve("config").toString()
        return Writer(builder.start()).also { started += it }
    }

    private fun allLines(): List<JsonObject> =
        home.ownedFiles().reversed().flatMap { name -> linesOf(home.file(name)).map { checkNotNull(it) { "a torn line in $name" } } }

    private fun JsonObject.area(): String = getValue("area").jsonPrimitive.content

    private fun JsonObject.seq(): Long = getValue("seq").jsonPrimitive.content.toLong()

    @Test
    fun `while one process holds the log the other writes nothing, and afterwards it can`() {
        val holder = start("hold", home.logs.toString(), "EXPORT")
        assertEquals("HELD", holder.says())

        val second = start("burst", home.logs.toString(), "BACKUP", "500")
        assertEquals("REFUSED", second.says())
        second.go()
        assertEquals(0, second.finishes(), "the second process put lines on disk while the first held the lock")
        assertEquals(listOf("EXPORT"), allLines().map { it.area() }, "the second process wrote while the first held the lock")

        holder.go()
        val held = holder.finishes()

        val third = start("burst", home.logs.toString(), "BACKUP", "500")
        assertEquals("WROTE", third.says(), "the lock was not free once its holder had finished")
        third.go()
        val after = third.finishes()

        // The holder's first line was written by hand with sequence 0; what its
        // queue wrote follows from 1. The third process starts again from 1.
        val lines = allLines()
        assertEquals(List(held) { "EXPORT" } + List(after) { "BACKUP" }, lines.map { it.area() })
        assertEquals((0L until held).toList(), lines.take(held).map { it.seq() })
        assertEquals((1L..after).toList(), lines.drop(held).map { it.seq() })
    }

    @Test
    fun `two processes writing at once never interleave or tear a line`() {
        val first = start("burst", home.logs.toString(), "EXPORT", "3000")
        val second = start("burst", home.logs.toString(), "BACKUP", "3000")
        // Both are alive and have each tried the lock before either bursts: one
        // holds it until it closes, the other was refused and stays quiet.
        val answers = listOf(first.says(), second.says())
        assertEquals(listOf("REFUSED", "WROTE"), answers.sorted(), "not exactly one of two live processes got the log")
        val (writer, refused) = if (answers[0] == "WROTE") first to second else second to first
        val writerArea = if (writer === first) "EXPORT" else "BACKUP"

        first.go()
        second.go()
        val wrote = writer.finishes()
        assertEquals(0, refused.finishes(), "the refused process put lines on disk")

        // Exactly what the writer says it wrote: whole lines, one process only,
        // numbered 1, 2, 3 … with no gap — whatever the shutdown could not flush
        // is missing from the end, never from the middle.
        val lines = allLines()
        assertTrue(wrote >= 1)
        assertEquals(List(wrote) { writerArea }, lines.map { it.area() })
        assertEquals((1L..wrote).toList(), lines.map { it.seq() })

        // Once both are gone, the next process takes the same lock and writes.
        val next = start("burst", home.logs.toString(), "SETTINGS", "1")
        assertEquals("WROTE", next.says(), "the lock was not free once both processes had finished")
        next.go()
        assertEquals(1, next.finishes())
        assertEquals(List(wrote) { writerArea } + "SETTINGS", allLines().map { it.area() })
    }

    @Test
    fun `a killed holder gives the lock back and every line it wrote stays readable`() {
        val holder = start("hold", home.logs.toString(), "EXPORT")
        assertEquals("HELD", holder.says())
        holder.process.destroyForcibly()
        assertTrue(holder.process.waitFor(20, TimeUnit.SECONDS))

        val next = home.sink()
        assertTrue(next.write(paddedLine("after-kill", 64)), "the lock stayed taken after its holder was killed")
        next.close()

        val lines = linesOf(home.file(ACTIVE_LOG_NAME))
        assertEquals(2, lines.size)
        assertEquals("EXPORT", checkNotNull(lines[0]).area())
        assertEquals("after-kill", lines[1]?.tag())
        assertTrue(Files.exists(home.file(LOG_LOCK_NAME)), "the lock file was deleted")
    }
}
