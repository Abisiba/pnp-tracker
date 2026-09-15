package dev.pnptracker.platform.diagnostics

import dev.pnptracker.AppInfo
import dev.pnptracker.domain.backup.restore.SUPPORTED_SOURCE_SCHEMA_VERSION
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.diagnosticLineOf
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

/** How many records may wait for the disk before new ones are dropped (PLAN 14.7.1). */
const val DIAGNOSTIC_QUEUE_CAPACITY: Int = 256

/** How long a controlled shutdown waits for the queue to reach the disk. */
const val DIAGNOSTIC_FLUSH_MILLIS: Long = 500

private const val IDLE_POLL_MILLIS = 50L

/**
 * The application's diagnostic log: a bounded queue in front of one worker.
 *
 * [record] takes a moment, a sequence number and a place in the queue, and
 * returns. It never touches the disk, never waits for the worker and never
 * throws; a full queue drops the record and counts it. The worker is the only
 * thread that writes, so lines cannot be interleaved, and the order in the file
 * is the order the sequence numbers were handed out in.
 *
 * `seq` starts at 1 in every process and is given to every record the moment it
 * is recorded, whether or not it later reaches the disk: a gap in the numbers is
 * a record that was dropped. How many were dropped is itself written as
 * `diagnostics.records_dropped` once the queue has room again — carrying the
 * count and nothing of what the dropped records said.
 */
class QueuedDiagnostics(
    private val sink: DiagnosticLogSink,
    private val app: AppInfo,
    private val clock: Clock = Clock.System,
    private val schemaVersion: Int = SUPPORTED_SOURCE_SCHEMA_VERSION,
    capacity: Int = DIAGNOSTIC_QUEUE_CAPACITY,
) : Diagnostics,
    AutoCloseable {
    private class Pending(
        val seq: Long,
        val atEpochMillis: Long,
        val record: DiagnosticRecord,
    )

    private val queue = ArrayBlockingQueue<Pending>(capacity)
    private val numbering = Any()
    private var nextSeq = 1L

    @Volatile
    private var closing = false

    @Volatile
    private var abandoned = false

    private val dropped = AtomicLong()

    private val worker =
        Thread(::drain, "pnp-diagnostics").apply {
            isDaemon = true
            start()
        }

    override fun record(record: DiagnosticRecord) {
        try {
            synchronized(numbering) {
                if (closing) return
                val pending = Pending(nextSeq++, clock.now().toEpochMilliseconds(), record)
                if (!queue.offer(pending)) dropped.incrementAndGet()
            }
        } catch (_: Exception) {
            // A clock that will not answer is still no reason to fail the caller.
            dropped.incrementAndGet()
        }
    }

    /**
     * Stops taking records, gives the worker up to [DIAGNOSTIC_FLUSH_MILLIS] to
     * write what is queued, and lets go of the file, the lock and the thread.
     */
    override fun close() {
        synchronized(numbering) {
            if (closing) return
            closing = true
        }
        worker.join(DIAGNOSTIC_FLUSH_MILLIS)
        if (worker.isAlive) {
            // Out of time: what is still queued is given up. Closing the sink
            // under a worker stuck in a write is what makes that write return.
            abandoned = true
            sink.close()
            worker.join(DIAGNOSTIC_FLUSH_MILLIS)
        }
        sink.close()
    }

    private fun drain() {
        while (!abandoned) {
            val pending =
                try {
                    queue.poll(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
            if (pending == null) {
                reportDropped()
                if (closing) return
                continue
            }
            writeOne(pending)
        }
    }

    /** Writes one record; every way it can fail ends with the record counted, not retried. */
    private fun writeOne(pending: Pending) {
        try {
            val line = diagnosticLineOf(pending.seq, pending.atEpochMillis, app.version, schemaVersion, pending.record)
            if (line == null || !sink.write(line)) {
                if (!sink.isDisabled) dropped.incrementAndGet()
            }
        } catch (_: Exception) {
            dropped.incrementAndGet()
        }
    }

    private fun reportDropped() {
        if (dropped.get() == 0L || sink.isDisabled) return
        try {
            // Numbered only while nothing is waiting, so the report cannot land in
            // the file ahead of a record that was given a smaller number.
            val (seq, count) =
                synchronized(numbering) {
                    if (queue.isNotEmpty()) return
                    nextSeq++ to dropped.getAndSet(0)
                }
            val report = DiagnosticRecord(DiagnosticEvent.RECORDS_DROPPED, count = count)
            val line = diagnosticLineOf(seq, clock.now().toEpochMilliseconds(), app.version, schemaVersion, report)
            if (line != null) sink.write(line)
        } catch (_: Exception) {
            // The report is itself a diagnostic; losing it changes nothing else.
        }
    }

    companion object {
        /** The log that lives in [logsDirectory], written by this process if it can take the lock. */
        fun inDirectory(
            logsDirectory: Path,
            app: AppInfo,
            clock: Clock = Clock.System,
        ): QueuedDiagnostics = QueuedDiagnostics(DiagnosticLogSink(NioDiagnosticLogFileSystem(logsDirectory)), app, clock)
    }
}
