package dev.pnptracker.platform.importfiles

import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.ImportFileGateway
import dev.pnptracker.domain.importprep.ImportFileHandle
import dev.pnptracker.domain.importprep.ImportPreparationException
import dev.pnptracker.domain.importprep.ImportSourceReading
import dev.pnptracker.domain.importprep.readCsvWorkbook
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.platform.csv.CsvFileReader
import dev.pnptracker.platform.files.FileFingerprint
import dev.pnptracker.platform.xlsx.XlsxReadException
import dev.pnptracker.platform.xlsx.XlsxWorkbookReader
import dev.pnptracker.platform.xlsx.importFailureOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private const val XLSX_EXTENSION = ".xlsx"
private const val CSV_EXTENSION = ".csv"

/** Not read, but worth naming: the advice for one is to save it again as `.xlsx`. */
private const val LEGACY_XLS_EXTENSION = ".xls"

/**
 * The desktop end of importing: it knows about paths, and nothing above it does.
 *
 * A chosen file becomes an [ImportFileHandle] that exposes only the file name.
 * The [Path] stays in here, which is what keeps it out of the screen, out of the
 * database and out of every message.
 *
 * Which of the two readers a file goes to is decided here, by extension and
 * without regard to case, and nowhere else. Both readers hand back the same
 * [ImportSourceReading], so everything past this point — the preview, the review
 * screen, the drafts, the confirmation — is one path with one set of rules.
 */
class DesktopImportFileGateway(
    private val picker: ImportFilePicker,
    private val xlsx: XlsxWorkbookReader = XlsxWorkbookReader(),
    private val csv: CsvFileReader = CsvFileReader(),
    private val fingerprint: FileFingerprint = FileFingerprint(),
) : ImportFileGateway {
    override suspend fun chooseFile(): ImportFileHandle? {
        val chosen = picker.chooseImportFile() ?: return null
        // The dialog filter is only a convenience; what came back is checked here.
        val format = readableFormatOf(chosen)
        return when (format) {
            ImportSourceFormat.XLSX -> XlsxFileHandle(chosen, xlsx, fingerprint)
            ImportSourceFormat.CSV -> CsvFileHandle(chosen, csv, fingerprint)
        }
    }

    private fun readableFormatOf(file: Path): ImportSourceFormat {
        val name = file.fileName?.toString().orEmpty()
        val format =
            when {
                name.endsWith(XLSX_EXTENSION, ignoreCase = true) -> ImportSourceFormat.XLSX
                name.endsWith(CSV_EXTENSION, ignoreCase = true) -> ImportSourceFormat.CSV
                // An old workbook is told apart from the rest, because the thing
                // to do about one is specific and worth saying.
                name.endsWith(LEGACY_XLS_EXTENSION, ignoreCase = true) ->
                    throw ImportPreparationException(ImportFailure.LEGACY_XLS_FILE)
                // Neither: the one message that names both formats this reads.
                else -> throw ImportPreparationException(ImportFailure.UNSUPPORTED_FILE_TYPE)
            }
        if (!Files.exists(file)) throw ImportPreparationException(ImportFailure.FILE_NOT_FOUND)
        if (Files.isDirectory(file)) throw ImportPreparationException(ImportFailure.UNSUPPORTED_FILE_TYPE)
        if (!Files.isReadable(file)) throw ImportPreparationException(ImportFailure.NOT_READABLE)
        return format
    }
}

/** What both handles share: a path nobody else sees, and a fingerprint of it. */
private abstract class PathImportFileHandle(
    private val file: Path,
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
}

private class XlsxFileHandle(
    private val file: Path,
    private val reader: XlsxWorkbookReader,
    fingerprint: FileFingerprint,
) : PathImportFileHandle(file, fingerprint) {
    override suspend fun readWorkbook(): ImportSourceReading =
        withContext(Dispatchers.IO) {
            try {
                ImportSourceReading(workbook = reader.read(file), sourceFormat = ImportSourceFormat.XLSX)
            } catch (cause: XlsxReadException) {
                throw ImportPreparationException(importFailureOf(cause.failure)).initCauseQuietly(cause)
            }
        }
}

/**
 * A CSV read into the same workbook shape a spreadsheet is read into.
 *
 * The file is decoded and checked whole before this returns, so a file that is
 * wrong on its last line has still written nothing: the database has not been
 * touched at any point on this path.
 */
private class CsvFileHandle(
    private val file: Path,
    private val reader: CsvFileReader,
    fingerprint: FileFingerprint,
) : PathImportFileHandle(file, fingerprint) {
    override suspend fun readWorkbook(): ImportSourceReading =
        withContext(Dispatchers.IO) {
            val text = reader.readText(file)
            // Only the file name travels on. A sheet named after the file is what
            // gives the preview something to call the one logical page a CSV has.
            val reading = readCsvWorkbook(fileName = file.fileName?.toString().orEmpty(), text = text)
            ImportSourceReading(
                workbook = reading.workbook,
                sourceFormat = ImportSourceFormat.CSV,
                csvDelimiter = reading.delimiter,
            )
        }
}

/** Keeps the original failure attached for a developer without putting it on screen. */
private fun ImportPreparationException.initCauseQuietly(cause: Throwable): ImportPreparationException = apply { initCause(cause) }
