package dev.pnptracker.platform.diagnostics

import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The queue half of PLAN 14.7.1: a caller that never waits for a disk, one
 * worker that owns the file, sequence numbers that say what was lost, and a
 * shutdown that writes what it can and lets go of everything.
 */
class QueuedDiagnosticsTest {
    private val home = LogHome()

    @AfterTest
    fun tidy() {
        home.close()
        assertTrue(workers().isEmpty(), "a diagnostics worker was left running: ${workers()}")
    }

    private fun workers(): List<Thread> = Thread.getAllStackTraces().keys.filter { it.name == "pnp-diagnostics" && it.isAlive }

    private fun lines(): List<JsonObject> = linesOf(home.file(ACTIVE_LOG_NAME)).map { checkNotNull(it) { "a line did not parse" } }

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun anError(area: DiagnosticArea = DiagnosticArea.EXPORT) =
        DiagnosticRecord(DiagnosticEvent.STORAGE_READ_FAILED, area = area, failure = IllegalStateException("/home/ali"))

    @Test
    fun `records reach the file in order, numbered from one, with the build's version and schema`() {
        val diagnostics = QueuedDiagnostics(home.sink(), testApp, StoppedClock)
        repeat(10) { diagnostics.record(anError()) }
        diagnostics.close()

        val written = lines()
        assertEquals((1L..10L).toList(), written.map { it.long("seq") })
        written.forEach { line ->
            assertEquals("0.1.0", line.text("app"))
            assertEquals(8, line.long("schema"))
            assertEquals("2025-09-15T08:21:04.512Z", line.text("at"))
            assertEquals("storage.read_failed", line.text("event"))
        }
    }

    @Test
    fun `many threads at once never interleave a line and never lose a number`() {
        val threads = 8
        val each = 2_000
        val diagnostics = QueuedDiagnostics(home.sink(), testApp, capacity = threads * each)
        val start = CyclicBarrier(threads)

        (0 until threads)
            .map { index ->
                thread {
                    start.await()
                    repeat(each) { diagnostics.record(anError(DiagnosticArea.entries[index])) }
                }
            }.forEach { it.join() }
        diagnostics.close()

        // Sixteen thousand lines are more than one file holds, so this also
        // rotates under load: read every file, oldest first.
        val written = home.ownedFiles().reversed().flatMap { name -> linesOf(home.file(name)).map { checkNotNull(it) } }
        assertTrue(home.ownedFiles().size > 1, "the load did not rotate")
        assertEquals(threads * each, written.size)
        assertEquals((1L..(threads * each).toLong()).toList(), written.map { it.long("seq") }, "the file is not in number order")
        assertEquals(List(threads) { each }, written.groupBy { it.text("area") }.values.map { it.size })
        home.ownedFiles().forEach { name ->
            assertTrue(
                Files
                    .readString(home.file(name))
                    .lines()
                    .dropLast(1)
                    .all { it.startsWith("{\"v\":1,") },
            )
        }
    }

    @Test
    fun `a full queue drops instead of making the caller wait, and says how many it dropped`() {
        val faulty = FaultyLogFileSystem(home.fileSystem())
        val gate = CountDownLatch(1)
        faulty.appendGate = gate
        val diagnostics = QueuedDiagnostics(home.sink(faulty), testApp, capacity = 4)

        val startedAt = System.nanoTime()
        repeat(1_000) { diagnostics.record(anError()) }
        val tookMillis = (System.nanoTime() - startedAt) / 1_000_000
        // The worker is stuck in its first write the whole time; had recording
        // waited for it, this loop would still be running.
        assertEquals(1L, gate.count, "the disk was released before the callers finished")
        assertTrue(tookMillis < 5_000, "recording waited for the disk: $tookMillis ms")

        gate.countDown()
        diagnostics.close()

        val written = lines()
        val records = written.filter { it.text("event") == "storage.read_failed" }
        val report = written.single { it.text("event") == "diagnostics.records_dropped" }
        assertTrue(records.size in 1..5, "expected the one in flight and at most four queued, got ${records.size}")
        assertEquals(1_000L - records.size, report.long("count"))
        assertEquals("WARN", report.text("level"))
        assertEquals(setOf("v", "seq", "at", "level", "event", "app", "schema", "count"), report.keys)
        // Dropped records keep their numbers, so the report comes after all of them.
        assertTrue(report.long("seq") > 1_000L)
        assertEquals(written.map { it.long("seq") }.sorted(), written.map { it.long("seq") })
    }

    @Test
    fun `closing writes what is queued, then lets go of the thread, the file and the lock`() {
        val diagnostics = QueuedDiagnostics(home.sink(), testApp)
        repeat(100) { diagnostics.record(anError()) }
        diagnostics.close()

        assertEquals(100, lines().size)
        assertTrue(workers().isEmpty(), "the worker outlived close")
        diagnostics.record(anError())
        diagnostics.close()
        assertEquals(100, lines().size, "a record after close was written")

        val next = home.sink()
        assertTrue(next.write(paddedLine("next", 64)), "the lock was still held after close")
        next.close()
    }

    @Test
    fun `a disk that never answers holds shutdown for about a second at most and still frees the worker`() {
        val faulty = FaultyLogFileSystem(home.fileSystem())
        faulty.appendGate = CountDownLatch(1) // never released
        val diagnostics = QueuedDiagnostics(home.sink(faulty), testApp)
        repeat(10) { diagnostics.record(anError()) }

        val startedAt = System.nanoTime()
        diagnostics.close()
        val tookMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(tookMillis < 3 * DIAGNOSTIC_FLUSH_MILLIS, "close waited $tookMillis ms")
        assertTrue(workers().isEmpty(), "the stuck worker was not freed")
    }

    @Test
    fun `a disk that fails, or code below that breaks, never reaches the caller and never loops`() {
        listOf(
            FaultyLogFileSystem(home.fileSystem()).apply { failing = setOf(FaultyLogFileSystem.Operation.PREPARE) },
            FaultyLogFileSystem(home.fileSystem()).apply { brokenCode = setOf(FaultyLogFileSystem.Operation.KIND) },
            FaultyLogFileSystem(home.fileSystem()).apply { failing = setOf(FaultyLogFileSystem.Operation.APPEND) },
        ).forEach { faulty ->
            val diagnostics = QueuedDiagnostics(home.sink(faulty), testApp)
            repeat(200) { diagnostics.record(anError()) }
            diagnostics.close()

            assertTrue(faulty.calls < 100, "logging kept retrying: ${faulty.calls} disk calls for 200 records")
        }
        assertFalse(Files.exists(home.file(ACTIVE_LOG_NAME)) && lines().isNotEmpty(), "a failing disk still produced lines")
    }

    @Test
    fun `a clock that throws costs the record and nothing else`() {
        val diagnostics =
            QueuedDiagnostics(
                home.sink(),
                testApp,
                object : Clock {
                    var calls = 0

                    override fun now(): Instant {
                        calls++
                        if (calls == 1) throw IllegalStateException("no time")
                        return Instant.fromEpochMilliseconds(0)
                    }
                },
            )
        diagnostics.record(anError())
        diagnostics.record(anError())
        diagnostics.close()

        val written = lines()
        assertEquals(listOf("storage.read_failed", "diagnostics.records_dropped"), written.map { it.text("event") })
        assertEquals(1L, written.last().long("count"))
    }

    @Test
    fun `nothing is made on disk by a writer that is never given a record`() {
        val diagnostics = QueuedDiagnostics.inDirectory(home.logs, testApp)
        diagnostics.close()

        assertFalse(Files.exists(home.stateDirectory), "an unused log made its folder")
    }

    private object StoppedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_757_924_464_512)
    }
}
