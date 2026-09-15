package dev.pnptracker.domain.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The version of the line format; a field changing its meaning moves this. */
const val DIAGNOSTIC_RECORD_VERSION: Int = 1

/** The longest a line may be, its closing line feed included (PLAN 14.7.1). */
const val LONGEST_DIAGNOSTIC_LINE_BYTES: Int = 2 * 1024

private const val LINE_FEED = '\n'
private const val MILLIS_PER_DAY = 86_400_000L

private val lineJson = Json

/**
 * One record as the bytes of one JSON Lines line: UTF-8, no BOM, fields in the
 * order PLAN 14.7.1 lists them, closed by a single line feed.
 *
 * Null when the line would be longer than [LONGEST_DIAGNOSTIC_LINE_BYTES]; such a
 * record is not written and counts as dropped.
 */
fun diagnosticLineOf(
    seq: Long,
    atEpochMillis: Long,
    appVersion: String,
    schemaVersion: Int,
    record: DiagnosticRecord,
): ByteArray? {
    val line =
        buildJsonObject {
            put("v", DIAGNOSTIC_RECORD_VERSION)
            put("seq", seq)
            put("at", utcMillisText(atEpochMillis))
            put("level", record.level.name)
            put("event", record.event.code)
            put("app", appVersion)
            put("schema", schemaVersion)
            record.reason?.let { put("reason", it) }
            record.area?.let { put("area", it.name) }
            record.place?.let { put("place", it.toString()) }
            record.fromSchema?.let { put("fromSchema", it) }
            record.toSchema?.let { put("toSchema", it) }
            record.count?.takeIf { it >= 0 }?.let { put("count", it) }
            record.exception?.let { put("exception", it.value) }
            record.cause?.let { put("cause", it.value) }
        }
    val bytes = (lineJson.encodeToString(JsonObject.serializer(), line) + LINE_FEED).encodeToByteArray()
    return bytes.takeIf { it.size <= LONGEST_DIAGNOSTIC_LINE_BYTES }
}

/**
 * A moment as `yyyy-MM-ddTHH:mm:ss.SSSZ` in UTC, always with milliseconds.
 *
 * Worked out from the day count rather than asked of a calendar library, so the
 * text is the same on every machine and in every locale.
 */
fun utcMillisText(epochMillis: Long): String {
    val days = epochMillis.floorDiv(MILLIS_PER_DAY)
    val millisOfDay = epochMillis.mod(MILLIS_PER_DAY)

    // Days to a civil date in the proleptic Gregorian calendar.
    val shifted = days + 719_468L
    val era = (if (shifted >= 0) shifted else shifted - 146_096L) / 146_097L
    val dayOfEra = shifted - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthIndex = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthIndex + 2L) / 5L + 1L
    val month = if (monthIndex < 10L) monthIndex + 3L else monthIndex - 9L
    val year = yearOfEra + era * 400L + if (month <= 2L) 1L else 0L

    val hours = millisOfDay / 3_600_000L
    val minutes = millisOfDay / 60_000L % 60L
    val seconds = millisOfDay / 1_000L % 60L
    val millis = millisOfDay % 1_000L
    return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T" +
        "${pad(hours, 2)}:${pad(minutes, 2)}:${pad(seconds, 2)}.${pad(millis, 3)}Z"
}

private fun pad(
    value: Long,
    width: Int,
): String = if (value < 0) "-" + (-value).toString().padStart(width, '0') else value.toString().padStart(width, '0')
