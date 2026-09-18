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
import java.util.concurrent.TimeUnit
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
 *
 * None of these asks how many lines this machine can write in 500 ms. Where a
 * test needs the worker to have written something it waits for the lines to
 * reach the disk ([FaultyLogFileSystem.awaitLines]); where it needs the worker
 * held still it holds it at a gate. The only clock left is the shutdown bound
 * itself, and that is checked from above — close may never take longer.
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
        val disk = FaultyLogFileSystem(home.fileSystem())
        val diagnostics = QueuedDiagnostics(home.sink(disk), testApp, StoppedClock)
        repeat(10) { diagnostics.record(anError()) }
        disk.awaitLines(10)
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
        val disk = FaultyLogFileSystem(home.fileSystem())
        val diagnostics = QueuedDiagnostics(home.sink(disk), testApp, capacity = threads * each)
        val start = CyclicBarrier(threads)

        (0 until threads)
            .map { index ->
                thread {
                    start.await()
                    repeat(each) { diagnostics.record(anError(DiagnosticArea.entries[index])) }
                }
            }.forEach { it.join() }
        // This is about order under load, not about how much a shutdown can
        // flush: every line is on disk before close is asked for anything.
        disk.awaitLines(threads * each)
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
        // The report is written once the queue is empty again; wait for it to
        // reach the disk rather than for a shutdown to squeeze it out.
        faulty.awaitLineWith("diagnostics.records_dropped")
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
    fun `a normal shutdown writes the small set still queued, then lets go of the thread, the file and the lock`() {
        val disk = FaultyLogFileSystem(home.fileSystem())
        val gate = CountDownLatch(1)
        disk.appendGate = gate
        val diagnostics = QueuedDiagnostics(home.sink(disk), testApp)
        repeat(10) { diagnostics.record(anError()) }
        // The worker is inside its first write and cannot move, so the other
        // nine are still in the queue: whatever reaches the disk from here on,
        // the shutdown put there.
        disk.awaitHeldAtGate()

        val closer = thread { diagnostics.close() }
        awaitWaitingIn(closer)
        gate.countDown()
        closer.join(SHUTDOWN_BOUND_MILLIS)

        assertFalse(closer.isAlive, "close outlived its bound")
        assertEquals((1L..10L).toList(), lines().map { it.long("seq") }, "a normal shutdown left queued records behind")
        assertTrue(workers().isEmpty(), "the worker outlived close")
        diagnostics.record(anError())
        diagnostics.close()
        assertEquals(10, lines().size, "a record after close was written")

        val next = home.sink()
        assertTrue(next.write(paddedLine("next", 64)), "the lock was still held after close")
        next.close()
    }

    @Test
    fun `a shutdown that runs out of time returns within its bound, loses only the tail, and still frees the worker and the lock`() {
        val disk = FaultyLogFileSystem(home.fileSystem())
        disk.appendsBeforeGate = 3
        disk.appendGate = CountDownLatch(1) // never released: the fourth write never finishes
        val diagnostics = QueuedDiagnostics(home.sink(disk), testApp)
        repeat(10) { diagnostics.record(anError()) }
        disk.awaitLines(3)
        disk.awaitHeldAtGate()

        val startedAt = System.nanoTime()
        diagnostics.close()
        val tookMillis = (System.nanoTime() - startedAt) / 1_000_000

        // One wait for the worker, one more after the sink is closed under it.
        assertTrue(tookMillis < SHUTDOWN_BOUND_MILLIS, "close waited $tookMillis ms")
        assertTrue(workers().isEmpty(), "the stuck worker was not freed")
        // What reached the disk is whole and numbered 1, 2, 3 without a gap: the
        // loss is the tail the shutdown had no time for, never a line in the
        // middle, and no report claims a count nobody was left to write
        // (PLAN 14.7.1: a gap is a dropped record; the queue is waited for at most
        // the flush bound).
        val written = lines()
        assertEquals(listOf(1L, 2L, 3L), written.map { it.long("seq") })
        assertTrue(written.none { it.text("event") == "diagnostics.records_dropped" })
        assertEquals('\n'.code.toByte(), Files.readAllBytes(home.file(ACTIVE_LOG_NAME)).last(), "the file ends in half a line")

        val next = home.sink()
        assertTrue(next.write(paddedLine("next", 64)), "the lock was still held after a shutdown that ran out of time")
        next.close()
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
        val disk = FaultyLogFileSystem(home.fileSystem())
        val diagnostics =
            QueuedDiagnostics(
                home.sink(disk),
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
        disk.awaitLineWith("diagnostics.records_dropped")
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

    /**
     * Waits until [closer] is parked in close's wait for the worker — the only
     * timed wait on its way — so the shutdown has begun and takes no new record.
     */
    private fun awaitWaitingIn(closer: Thread) {
        val giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(HANG_GUARD_SECONDS)
        while (closer.state != Thread.State.TIMED_WAITING) {
            check(closer.isAlive && System.nanoTime() < giveUpAt) { "close never started waiting for the worker" }
            Thread.onSpinWait()
        }
    }

    private object StoppedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_757_924_464_512)
    }

    private companion object {
        /** Close's own ceiling: two waits of the flush bound, with room for the machine. */
        const val SHUTDOWN_BOUND_MILLIS = 3 * DIAGNOSTIC_FLUSH_MILLIS

        const val HANG_GUARD_SECONDS = 300L
    }
}
