package dev.pnptracker.platform.diagnostics

import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticLevel
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.diagnosticLineOf
import dev.pnptracker.platform.backupfiles.BackupFilePicker
import dev.pnptracker.platform.exportfiles.ExportFilePicker
import dev.pnptracker.platform.importfiles.ImportFilePicker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A moment to write the kept records at; the lines are read, never the clock. */
private const val A_MOMENT = 1_757_924_464_512L

/** Everything one run of a boundary recorded, in the order it was recorded. */
class RecordingDiagnostics : Diagnostics {
    private val kept = CopyOnWriteArrayList<DiagnosticRecord>()

    override fun record(record: DiagnosticRecord) {
        kept += record
    }

    val records: List<DiagnosticRecord> get() = kept.toList()

    /** The one record this was given, or a failure naming what it was given instead. */
    fun only(): DiagnosticRecord {
        assertEquals(1, records.size, "expected one record, got ${records.map { it.event.code }}")
        return records.first()
    }

    fun forget() = kept.clear()

    /** The records as the lines they would be written as, read back by a real reader. */
    fun lines(): List<JsonObject> =
        records.mapIndexed { index, record ->
            val bytes = assertNotNull(diagnosticLineOf(index + 1L, A_MOMENT, "0.1.0", 8, record), "a record did not fit one line")
            Json.parseToJsonElement(bytes.decodeToString().removeSuffix("\n")).jsonObject
        }
}

/** A log that refuses every record, so nothing may depend on one being taken. */
class ThrowingDiagnostics : Diagnostics {
    var calls: Int = 0
        private set

    override fun record(record: DiagnosticRecord) {
        calls++
        throw IllegalStateException("the log itself is broken")
    }
}

/** What one boundary is expected to have recorded (PLAN 14.7.2). */
data class ExpectedRecord(
    val event: DiagnosticEvent,
    val level: DiagnosticLevel = event.level,
    val area: DiagnosticArea? = null,
    val reason: Enum<*>? = null,
    val exception: String? = null,
    val cause: String? = null,
    val count: Long? = null,
)

/** Holds one record to every field the event's row in PLAN 14.7.2 gives it. */
fun assertRecordedAsPlanned(
    expected: ExpectedRecord,
    record: DiagnosticRecord,
) {
    assertEquals(expected.event, record.event, "event")
    assertEquals(expected.level, record.level, "level of ${record.event.code}")
    assertEquals(expected.area, record.area, "area of ${record.event.code}")
    assertEquals(expected.reason?.name, record.reason, "reason of ${record.event.code}")
    assertEquals(expected.exception, record.exception?.value, "exception of ${record.event.code}")
    assertEquals(expected.cause, record.cause?.value, "cause of ${record.event.code}")
    assertEquals(expected.count, record.count, "count of ${record.event.code}")
}

/** Anything that would be somebody's data, a place on this machine, or a defect's words. */
private val NEVER_IN_A_LINE =
    listOf(
        "SELECT",
        "INSERT",
        "UPDATE",
        "DELETE",
        "FROM",
        "PRAGMA",
        "/home",
        "/tmp",
        "\\\\",
        ".db",
        ".json",
        ".csv",
        ".xlsx",
        "at dev.",
        "at java.",
        "Exception:",
        "message",
    )

/** A UUID anywhere in a line, in either case. */
private val UUID_SHAPE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * Proves the written bytes of every record hold nothing but fixed names and numbers.
 *
 * [secrets] are the things this particular test put into the application: the
 * Turkish names it wrote, the file names it chose, the folder it worked in, the
 * user this machine belongs to. None of them may appear, and neither may the
 * shapes nothing should ever have — SQL, a path, a UUID, a stack line.
 */
fun assertLinesCarryNothingOfTheUsers(
    diagnostics: RecordingDiagnostics,
    vararg secrets: String,
) {
    diagnostics.lines().forEach { line ->
        val text = line.toString()
        secrets.filter { it.isNotBlank() }.forEach { secret ->
            assertFalse(secret in text, "`$secret` reached a diagnostic line: $text")
        }
        NEVER_IN_A_LINE.forEach { forbidden ->
            assertFalse(forbidden in text, "`$forbidden` reached a diagnostic line: $text")
        }
        assertFalse(UUID_SHAPE.containsMatchIn(text), "an identifier reached a diagnostic line: $text")
        assertTrue(line.keys.containsAll(listOf("v", "seq", "at", "level", "event", "app", "schema")), "a line is missing a required field")
        assertTrue(
            line
                .getValue("event")
                .jsonPrimitive.content
                .isNotBlank(),
        )
    }
}

/** The save dialog for a backup, answering with the file a test chose. */
class FixedBackupPicker(
    private val chosen: Path,
) : BackupFilePicker {
    override suspend fun chooseDestination(suggestedName: String): Path = chosen
}

/** The save dialog for the task export, answering with the file a test chose. */
class FixedExportPicker(
    private val chosen: Path,
) : ExportFilePicker {
    override suspend fun chooseDestination(suggestedName: String): Path = chosen
}

/** The open dialog for an import, answering with the file a test wrote. */
class FixedImportPicker(
    private val chosen: Path,
) : ImportFilePicker {
    override suspend fun chooseImportFile(): Path = chosen
}
