package dev.pnptracker.platform.xlsx

import dev.pnptracker.domain.importprep.ImportFailure

/** Why a spreadsheet could not be read. */
enum class XlsxReadFailure {
    FILE_NOT_FOUND,
    NOT_READABLE,
    NOT_AN_XLSX_FILE,
    LEGACY_XLS_FILE,
    DAMAGED_FILE,
    ENCRYPTED,
    REJECTED_BY_SAFETY_LIMIT,
}

/**
 * A spreadsheet could not be read.
 *
 * The message names the file and the reason and nothing else: whatever the file
 * contained is the user's data and does not belong in an error, a log or a bug
 * report. The original library exception is kept as the cause for developers.
 */
class XlsxReadException(
    val failure: XlsxReadFailure,
    val fileName: String,
    cause: Throwable? = null,
) : Exception("Could not read $fileName: $failure", cause)

/**
 * Translates what the spreadsheet library complained about into what the user
 * has to do next.
 *
 * The two lists are kept apart on purpose: one describes a parsing library, the
 * other describes a person's next step, and the screen should never have to
 * learn the vocabulary of the first.
 */
fun importFailureOf(failure: XlsxReadFailure): ImportFailure =
    when (failure) {
        XlsxReadFailure.FILE_NOT_FOUND -> ImportFailure.FILE_NOT_FOUND
        XlsxReadFailure.NOT_READABLE -> ImportFailure.NOT_READABLE
        XlsxReadFailure.NOT_AN_XLSX_FILE -> ImportFailure.NOT_AN_XLSX_FILE
        XlsxReadFailure.LEGACY_XLS_FILE -> ImportFailure.LEGACY_XLS_FILE
        XlsxReadFailure.DAMAGED_FILE -> ImportFailure.DAMAGED_FILE
        XlsxReadFailure.ENCRYPTED -> ImportFailure.ENCRYPTED
        XlsxReadFailure.REJECTED_BY_SAFETY_LIMIT -> ImportFailure.REJECTED_BY_SAFETY_LIMIT
    }
