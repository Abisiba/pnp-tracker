package dev.pnptracker.platform.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 */
class DiagnosticLogProcessTest {
    private val home = LogHome()
    private val started = mutableListOf<Process>()

    @AfterTest
    fun tidy() {
        started.forEach { process ->
            if (process.isAlive) process.destroyForcibly().waitFor(20, TimeUnit.SECONDS)
        }
        home.close()
    }

    private fun start(vararg args: String): Process {
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
        return builder.start().also { started += it }
    }

    private fun Process.says(): String = inputStream.bufferedReader().readLine() ?: "(nothing)"

    private fun Process.finishes(): String {
        val said = inputStream.bufferedReader().readText().trim()
        assertTrue(waitFor(60, TimeUnit.SECONDS) && exitValue() == 0, "the writer failed: $said")
        return said
    }

    private fun allLines(): List<JsonObject> =
        home.ownedFiles().reversed().flatMap { name -> linesOf(home.file(name)).map { checkNotNull(it) { "a torn line in $name" } } }

    private fun JsonObject.area(): String = getValue("area").jsonPrimitive.content

    @Test
    fun `while one process holds the log the other writes nothing, and afterwards it can`() {
        val holder = start("hold", home.logs.toString(), "EXPORT")
        assertEquals("HELD", holder.says())

        val second = start("burst", home.logs.toString(), "BACKUP", "500")
        assertEquals("DONE", second.finishes())
        assertEquals(listOf("EXPORT"), allLines().map { it.area() }, "the second process wrote while the first held the lock")

        holder.outputStream.bufferedWriter().apply {
            write("go\n")
            flush()
        }
        assertEquals("DONE", holder.finishes())

        val third = start("burst", home.logs.toString(), "BACKUP", "500")
        assertEquals("DONE", third.finishes())

        val areas = allLines().map { it.area() }
        assertEquals(List(201) { "EXPORT" } + List(500) { "BACKUP" }, areas)
    }

    @Test
    fun `two processes writing at once never interleave or tear a line`() {
        val first = start("burst", home.logs.toString(), "EXPORT", "3000")
        val second = start("burst", home.logs.toString(), "BACKUP", "3000")
        assertEquals("DONE", first.finishes())
        assertEquals("DONE", second.finishes())

        val lines = allLines()
        assertTrue(lines.size == 3000 || lines.size == 6000, "unexpected ${lines.size} lines")
        // Each process's lines form one unbroken block numbered 1, 2, 3 …
        val blocks =
            lines.fold(mutableListOf<MutableList<JsonObject>>()) { runs, line ->
                if (runs.isEmpty() || runs.last().last().area() != line.area()) runs += mutableListOf(line) else runs.last() += line
                runs
            }
        assertTrue(blocks.size <= 2, "lines of the two processes were interleaved: ${blocks.map { it.first().area() to it.size }}")
        blocks.forEach { block ->
            assertEquals(
                (1L..block.size).toList(),
                block.map {
                    it
                        .getValue("seq")
                        .jsonPrimitive.content
                        .toLong()
                },
            )
        }
    }

    @Test
    fun `a killed holder gives the lock back and every line it wrote stays readable`() {
        val holder = start("hold", home.logs.toString(), "EXPORT")
        assertEquals("HELD", holder.says())
        holder.destroyForcibly()
        assertTrue(holder.waitFor(20, TimeUnit.SECONDS))

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
