package dev.pnptracker.domain.diagnostics

import dev.pnptracker.domain.backup.restore.BackupPlace
import dev.pnptracker.domain.export.ExportFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The line format of PLAN 14.7.1, byte for byte, and the promise behind it:
 * nothing but fixed names and numbers ever reaches a line.
 */
class DiagnosticLineTest {
    private fun lineOf(
        record: DiagnosticRecord,
        seq: Long = 7,
        at: Long = 1_757_924_464_512,
    ): String = checkNotNull(diagnosticLineOf(seq, at, "0.1.0", 8, record)).decodeToString()

    private fun parsed(line: String): JsonObject = Json.parseToJsonElement(line.removeSuffix("\n")).jsonObject

    @Test
    fun `the smallest line is exactly the required fields in their order`() {
        val bytes = checkNotNull(diagnosticLineOf(1, 0, "0.1.0", 8, DiagnosticRecord(DiagnosticEvent.RESTORE_COMPLETED)))

        val expected =
            "{\"v\":1,\"seq\":1,\"at\":\"1970-01-01T00:00:00.000Z\",\"level\":\"INFO\"," +
                "\"event\":\"restore.completed\",\"app\":\"0.1.0\",\"schema\":8}\n"
        assertContentEquals(expected.encodeToByteArray(), bytes)
        // UTF-8, no byte order mark, one line feed and only at the end.
        assertEquals('{'.code.toByte(), bytes.first())
        assertEquals(1, bytes.count { it == '\n'.code.toByte() })
    }

    @Test
    fun `every optional field is written in its place and nowhere else`() {
        val record =
            DiagnosticRecord(
                event = DiagnosticEvent.STORAGE_WRITE_FAILED,
                reason = ExportFailure.COULD_NOT_READ,
                area = DiagnosticArea.EXPORT,
                place = BackupPlace("tasks", "updatedAt"),
                fromSchema = 3,
                toSchema = 8,
                count = 12,
                failure = IllegalStateException("outer", IllegalArgumentException("inner")),
            )

        assertEquals(
            "{\"v\":1,\"seq\":7,\"at\":\"2025-09-15T08:21:04.512Z\",\"level\":\"ERROR\"," +
                "\"event\":\"storage.write_failed\",\"app\":\"0.1.0\",\"schema\":8," +
                "\"reason\":\"COULD_NOT_READ\",\"area\":\"EXPORT\",\"place\":\"tasks.updatedAt\"," +
                "\"fromSchema\":3,\"toSchema\":8,\"count\":12," +
                "\"exception\":\"java.lang.IllegalStateException\"," +
                "\"cause\":\"java.lang.IllegalArgumentException\"}\n",
            lineOf(record),
        )
    }

    @Test
    fun `the three levels come from the event and can only be one of three`() {
        assertEquals(setOf("INFO", "WARN", "ERROR"), DiagnosticLevel.entries.map { it.name }.toSet())
        assertEquals("INFO", parsed(lineOf(DiagnosticRecord(DiagnosticEvent.MIGRATION_COMPLETED)))["level"]!!.jsonPrimitive.content)
        assertEquals("WARN", parsed(lineOf(DiagnosticRecord(DiagnosticEvent.RESTORE_FILE_REFUSED)))["level"]!!.jsonPrimitive.content)
        assertEquals("ERROR", parsed(lineOf(DiagnosticRecord(DiagnosticEvent.EXPORT_BROKEN_DATA)))["level"]!!.jsonPrimitive.content)
        // PLAN 14.7.2: another copy running is a refusal written as a warning.
        val downgraded = DiagnosticRecord(DiagnosticEvent.STARTUP_REFUSED, level = DiagnosticLevel.WARN)
        assertEquals("WARN", parsed(lineOf(downgraded))["level"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the event list is the closed list of PLAN 14_7_2`() {
        assertEquals(
            listOf(
                "startup.refused",
                "startup.migration_completed",
                "settings.read_problem",
                "settings.write_failed",
                "storage.read_failed",
                "storage.write_failed",
                "import.file_unreadable",
                "import.snapshot_failed",
                "import.changed_meanwhile",
                "import.records_contradict",
                "import.rollback_provenance_broken",
                "import.draft_held_by_records",
                "backup.write_failed",
                "backup.rotation_incomplete",
                "restore.file_refused",
                "restore.not_completed",
                "restore.completed",
                "export.write_failed",
                "export.broken_data",
                "app.unexpected_failure",
                "diagnostics.records_dropped",
            ),
            DiagnosticEvent.entries.map { it.code },
        )
        val onlyInfo = DiagnosticEvent.entries.filter { it.level == DiagnosticLevel.INFO }.map { it.code }
        assertEquals(listOf("startup.migration_completed", "restore.completed"), onlyInfo)
    }

    @Test
    fun `a failure leaves its class names and nothing it was carrying`() {
        val carrying =
            "görev Kırmızı ev / oyun Işık, /home/ali/.local/share/pnp-tracker/pnp.db, " +
                "SELECT * FROM tasks WHERE id = '0f8fad5b-d9cb-469f-a165-70867728950e'"
        val failure = IllegalStateException(carrying, RuntimeException(carrying, IllegalArgumentException(carrying)))

        val line = lineOf(DiagnosticRecord(DiagnosticEvent.UNEXPECTED_FAILURE, failure = failure))

        val json = parsed(line)
        assertEquals("java.lang.IllegalStateException", json["exception"]!!.jsonPrimitive.content)
        assertEquals("java.lang.IllegalArgumentException", json["cause"]!!.jsonPrimitive.content)
        listOf("Kırmızı", "Işık", "/home", "pnp.db", "SELECT", "tasks", "0f8fad5b", "at dev.", "Exception:").forEach {
            assertFalse(it in line, "`$it` reached a diagnostic line: $line")
        }
    }

    @Test
    fun `a failure with no cause has no cause, and a cause that loops does not hang`() {
        assertNull(DiagnosticRecord(DiagnosticEvent.UNEXPECTED_FAILURE, failure = IllegalStateException()).cause)

        val first = IllegalStateException()
        val second = IllegalArgumentException(first)
        first.initCause(second)
        assertEquals("java.lang.IllegalArgumentException", ExceptionClassName.ofRootCause(first)?.value)

        var deep: Throwable = IllegalArgumentException()
        repeat(40) { deep = IllegalStateException(deep) }
        // Sixteen steps and no further: still a class name, never a hang.
        assertEquals("java.lang.IllegalStateException", ExceptionClassName.ofRootCause(deep)?.value)
    }

    @Test
    fun `a class without a usable name is written as a question mark`() {
        val anonymous = object : IllegalStateException("Kırmızı ev") {}

        val json = parsed(lineOf(DiagnosticRecord(DiagnosticEvent.UNEXPECTED_FAILURE, failure = anonymous)))

        assertEquals(ExceptionClassName.UNNAMED, json["exception"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a place is written only in the backup format's own words`() {
        assertEquals("tasks.updatedAt", DiagnosticPlace.of(BackupPlace("tasks", "updatedAt")).toString())
        assertEquals("envelope.dataSha256", DiagnosticPlace.of(BackupPlace("envelope", "dataSha256")).toString())
        assertEquals("file", DiagnosticPlace.of(BackupPlace.File).toString())
        assertEquals("data", DiagnosticPlace.of(BackupPlace("data")).toString())

        listOf(
            BackupPlace("Kırmızı ev"),
            BackupPlace("tasks", "Kırmızı ev"),
            BackupPlace("/home/ali/yedek.json"),
            BackupPlace("tasks", "name; DROP TABLE tasks"),
            BackupPlace("file", "updatedAt"),
        ).forEach { place ->
            assertNull(DiagnosticPlace.of(place), "$place was accepted")
            assertFalse("place" in lineOf(DiagnosticRecord(DiagnosticEvent.RESTORE_FILE_REFUSED, place = place)))
        }
    }

    @Test
    fun `a count below nothing is not written`() {
        assertFalse("count" in lineOf(DiagnosticRecord(DiagnosticEvent.RECORDS_DROPPED, count = -1)))
        assertEquals(0, parsed(lineOf(DiagnosticRecord(DiagnosticEvent.RECORDS_DROPPED, count = 0)))["count"]!!.jsonPrimitive.long)
    }

    @Test
    fun `moments are written in UTC with milliseconds on every day of the calendar`() {
        assertEquals("1970-01-01T00:00:00.000Z", utcMillisText(0))
        assertEquals("2025-09-15T08:21:04.512Z", utcMillisText(1_757_924_464_512))
        assertEquals("2000-02-29T00:00:00.000Z", utcMillisText(951_782_400_000))
        assertEquals("2024-02-29T23:59:59.999Z", utcMillisText(1_709_251_199_999))
        assertEquals("1969-12-31T23:59:59.999Z", utcMillisText(-1))
        assertEquals("9999-12-31T23:59:59.999Z", utcMillisText(253_402_300_799_999))
    }

    @Test
    fun `every line is one JSON object a real reader takes on its own`() {
        val lines =
            DiagnosticEvent.entries.mapIndexed { index, event ->
                lineOf(DiagnosticRecord(event, area = DiagnosticArea.entries[index % DiagnosticArea.entries.size]), seq = index + 1L)
            }

        lines.forEachIndexed { index, line ->
            assertTrue(line.endsWith("\n") && line.indexOf('\n') == line.length - 1)
            val json = parsed(line)
            assertEquals(1, json["v"]!!.jsonPrimitive.long)
            assertEquals(index + 1L, json["seq"]!!.jsonPrimitive.long)
            assertTrue(json.keys.containsAll(listOf("v", "seq", "at", "level", "event", "app", "schema")))
        }
    }
}
