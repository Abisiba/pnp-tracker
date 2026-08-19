package dev.pnptracker.platform.xlsx

import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.ImportFileGateway
import dev.pnptracker.domain.importprep.ImportFileHandle
import dev.pnptracker.domain.importprep.ImportPreparationException
import dev.pnptracker.domain.spreadsheet.WorkbookSnapshot
import dev.pnptracker.platform.files.FileFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The one extension this version reads. */
private const val XLSX_EXTENSION = ".xlsx"

/**
 * The desktop end of importing: it knows about paths, and nothing above it does.
 *
 * A chosen file becomes an [ImportFileHandle] that exposes only the file name.
 * The [Path] stays in here, which is what keeps it out of the screen, out of the
 * database and out of every message.
 */
class XlsxImportFileGateway(
    private val picker: XlsxFilePicker,
    private val reader: XlsxWorkbookReader = XlsxWorkbookReader(),
    private val fingerprint: FileFingerprint = FileFingerprint(),
) : ImportFileGateway {
    override suspend fun chooseFile(): ImportFileHandle? {
        val chosen = picker.chooseXlsxFile() ?: return null
        // The dialog filter is only a convenience; what came back is checked here.
        requireReadableXlsx(chosen)
        return PathImportFileHandle(chosen, reader, fingerprint)
    }

    private fun requireReadableXlsx(file: Path) {
        val name = file.fileName?.toString().orEmpty()
        if (!name.endsWith(XLSX_EXTENSION, ignoreCase = true)) {
            throw ImportPreparationException(ImportFailure.NOT_AN_XLSX_FILE)
        }
        if (!Files.exists(file)) throw ImportPreparationException(ImportFailure.FILE_NOT_FOUND)
        if (Files.isDirectory(file)) throw ImportPreparationException(ImportFailure.NOT_AN_XLSX_FILE)
        if (!Files.isReadable(file)) throw ImportPreparationException(ImportFailure.NOT_READABLE)
    }
}

private class PathImportFileHandle(
    private val file: Path,
    private val reader: XlsxWorkbookReader,
    private val fingerprint: FileFingerprint,
) : ImportFileHandle {
    override val fileName: String = file.fileName?.toString().orEmpty()

    override suspend fun fingerprint(): String =
        withContext(Dispatchers.IO) {
            try {
                fingerprint.of(file)
            } catch (cause: NoSuchFileException) {
                throw ImportPreparationException(ImportFailure.FILE_NOT_FOUND).initCauseQuietly(cause)
            } catch (cause: AccessDeniedException) {
                throw ImportPreparationException(ImportFailure.NOT_READABLE).initCauseQuietly(cause)
            } catch (cause: IOException) {
                throw ImportPreparationException(ImportFailure.DAMAGED_FILE).initCauseQuietly(cause)
            }
        }

    override suspend fun readWorkbook(): WorkbookSnapshot =
        withContext(Dispatchers.IO) {
            try {
                reader.read(file)
            } catch (cause: XlsxReadException) {
                throw ImportPreparationException(importFailureOf(cause.failure)).initCauseQuietly(cause)
            }
        }
}

/**
 * Translates what the spreadsheet library complained about into what the user
 * has to do next.
 *
 * The two lists are kept apart on purpose: one describes a parsing library, the
 * other describes a person's next step, and the screen should never have to
 * learn the vocabulary of the first.
 */
internal fun importFailureOf(failure: XlsxReadFailure): ImportFailure =
    when (failure) {
        XlsxReadFailure.FILE_NOT_FOUND -> ImportFailure.FILE_NOT_FOUND
        XlsxReadFailure.NOT_READABLE -> ImportFailure.NOT_READABLE
        XlsxReadFailure.NOT_AN_XLSX_FILE -> ImportFailure.NOT_AN_XLSX_FILE
        XlsxReadFailure.LEGACY_XLS_FILE -> ImportFailure.LEGACY_XLS_FILE
        XlsxReadFailure.DAMAGED_FILE -> ImportFailure.DAMAGED_FILE
        XlsxReadFailure.ENCRYPTED -> ImportFailure.ENCRYPTED
        XlsxReadFailure.REJECTED_BY_SAFETY_LIMIT -> ImportFailure.REJECTED_BY_SAFETY_LIMIT
    }

/** Keeps the original failure attached for a developer without putting it on screen. */
private fun ImportPreparationException.initCauseQuietly(cause: Throwable): ImportPreparationException = apply { initCause(cause) }
