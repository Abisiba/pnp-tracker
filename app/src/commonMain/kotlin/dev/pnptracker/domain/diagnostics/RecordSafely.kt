package dev.pnptracker.domain.diagnostics

import androidx.sqlite.SQLiteException

/**
 * Hands one record to [Diagnostics] from a failure path, and nothing about it back.
 *
 * Every boundary that records does so while it is turning a failure into the
 * answer the user is given. Whatever happens to the record — a diagnostics that
 * throws, a record that could not be built — must not replace that answer with
 * another one (PLAN 14.7.1: a logging failure never changes what the user asked
 * for), so both the building and the handing over happen inside one catch.
 * An [Error] is not caught: it is not the log's to swallow.
 */
inline fun Diagnostics.recordSafely(build: () -> DiagnosticRecord) {
    try {
        record(build())
    } catch (_: Exception) {
        // Deliberately nothing: the failure being recorded is the one that matters.
    }
}

/**
 * Storage refused a write, and the boundary told the user [reason] (PLAN 14.7.2).
 *
 * Only the refusal's class and its root cause's class survive; the refusal
 * itself is not kept.
 */
fun storageWriteFailed(
    area: DiagnosticArea,
    reason: Enum<*>,
    failure: Throwable,
): DiagnosticRecord = DiagnosticRecord(DiagnosticEvent.STORAGE_WRITE_FAILED, area = area, reason = reason, failure = failure)

/** Storage refused a read, and the boundary told the user it could not be read (PLAN 14.7.2). */
fun storageReadFailed(
    area: DiagnosticArea,
    failure: Throwable,
    reason: Enum<*>? = null,
): DiagnosticRecord = DiagnosticRecord(DiagnosticEvent.STORAGE_READ_FAILED, area = area, reason = reason, failure = failure)

/**
 * A reading a screen shows as failed without telling one cause from another.
 *
 * Storage refusing is recorded as the read failure it is. Anything else reaching
 * the same catch is not a storage problem at all, and is recorded as unexpected
 * rather than filed under one (PLAN 14.4.5) — the screen's own answer stays as it
 * was either way.
 */
fun readShownAsFailed(
    area: DiagnosticArea,
    failure: Throwable,
): DiagnosticRecord =
    if (failure is SQLiteException) {
        storageReadFailed(area, failure)
    } else {
        DiagnosticRecord(DiagnosticEvent.UNEXPECTED_FAILURE, area = area, failure = failure)
    }
