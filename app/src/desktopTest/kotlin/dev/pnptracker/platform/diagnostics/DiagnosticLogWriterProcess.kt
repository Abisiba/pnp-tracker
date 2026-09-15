package dev.pnptracker.platform.diagnostics

import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.diagnosticLineOf
import java.nio.file.Path

/**
 * A second process that writes to a log folder, for `DiagnosticLogProcessTest`.
 *
 * A file lock belongs to a process, so whether two copies of the application
 * can corrupt one log — and whether a killed copy gives the lock back — has no
 * honest answer inside one JVM.
 *
 * ```text
 * hold  <logs> <area>             writes one line through the sink, says HELD or
 *                                 REFUSED, waits for a line on stdin, writes 200
 *                                 more through the queue and says DONE
 * burst <logs> <area> <count>     records <count> through the queue, says DONE
 * ```
 */
fun main(args: Array<String>) {
    val logs = Path.of(args[1])
    val area = DiagnosticArea.valueOf(args[2])
    val record = DiagnosticRecord(DiagnosticEvent.STORAGE_WRITE_FAILED, area = area)
    when (args[0]) {
        "hold" -> {
            val sink = DiagnosticLogSink(NioDiagnosticLogFileSystem(logs))
            val first = checkNotNull(diagnosticLineOf(0, System.currentTimeMillis(), testApp.version, 8, record))
            println(if (sink.write(first)) "HELD" else "REFUSED")
            System.out.flush()
            readln()
            val queued = QueuedDiagnostics(sink, testApp)
            repeat(200) { queued.record(record) }
            queued.close()
        }

        "burst" -> {
            // Room for every record: this is about two writers, not a full queue.
            val count = args[3].toInt()
            val queued = QueuedDiagnostics(DiagnosticLogSink(NioDiagnosticLogFileSystem(logs)), testApp, capacity = count + 1)
            repeat(count) { queued.record(record) }
            queued.close()
        }
    }
    println("DONE")
    System.out.flush()
}
