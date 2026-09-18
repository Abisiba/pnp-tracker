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
 * Every step is announced when it has happened, never guessed at by the test:
 *
 * ```text
 * hold  <logs> <area>             writes one line through the sink, says HELD or
 *                                 REFUSED, waits for a line on stdin, records 200
 *                                 more through the queue, closes it and says
 *                                 DONE <lines this process put on disk>
 * burst <logs> <area> <count>     records one through the queue and waits until it
 *                                 is on disk or the lock was refused, says WROTE or
 *                                 REFUSED, waits for a line on stdin, records the
 *                                 other <count> − 1, closes and says DONE <lines>
 * ```
 *
 * The count after DONE is what reached this process's own disk calls. How many
 * that is depends on how fast this machine is and on the shutdown's 500 ms, and
 * the test does not care: it checks that the folder holds exactly those lines.
 */
fun main(args: Array<String>) {
    val logs = Path.of(args[1])
    val area = DiagnosticArea.valueOf(args[2])
    val record = DiagnosticRecord(DiagnosticEvent.STORAGE_WRITE_FAILED, area = area)
    val disk = FaultyLogFileSystem(NioDiagnosticLogFileSystem(logs))
    val sink = DiagnosticLogSink(disk)
    when (args[0]) {
        "hold" -> {
            val first = checkNotNull(diagnosticLineOf(0, System.currentTimeMillis(), testApp.version, 8, record))
            say(if (sink.write(first)) "HELD" else "REFUSED")
            readln()
            val queued = QueuedDiagnostics(sink, testApp)
            repeat(200) { queued.record(record) }
            queued.close()
        }

        "burst" -> {
            // Room for every record: this is about two writers, not a full queue.
            val count = args[3].toInt()
            val queued = QueuedDiagnostics(sink, testApp, capacity = count + 1)
            queued.record(record)
            say(if (disk.awaitFirstLineOrRefusal()) "WROTE" else "REFUSED")
            readln()
            repeat(count - 1) { queued.record(record) }
            queued.close()
        }
    }
    say("DONE ${disk.linesWritten}")
}

private fun say(line: String) {
    println(line)
    System.out.flush()
}
