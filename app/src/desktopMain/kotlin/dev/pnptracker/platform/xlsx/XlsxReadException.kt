package dev.pnptracker.platform.xlsx

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
