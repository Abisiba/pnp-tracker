package dev.pnptracker.platform.csv

import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.ImportPreparationException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Reads a CSV file off the disk as text, and refuses to guess.
 *
 * The file is UTF-8 or it is not read. A decoder that replaces bad bytes
 * with a replacement character would let a file whose Turkish letters were saved in some older
 * encoding sail through and land in the database with the wrong words in it,
 * where nobody would ever find them again. Better to say so while the user can
 * still go and save the file again.
 *
 * The file is opened for reading and nothing else: it is never written, moved or
 * deleted (PLAN 14.3).
 *
 * This is the only piece of the CSV import that knows about files or character
 * sets. Everything above it is handed a `String` and stays testable without
 * either.
 */
class CsvFileReader {
    /** @throws ImportPreparationException if the file cannot be read as UTF-8 text. */
    fun readText(file: Path): String {
        val bytes =
            try {
                Files.readAllBytes(file)
            } catch (cause: NoSuchFileException) {
                throw ImportPreparationException(ImportFailure.FILE_NOT_FOUND).initCauseQuietly(cause)
            } catch (cause: AccessDeniedException) {
                throw ImportPreparationException(ImportFailure.NOT_READABLE).initCauseQuietly(cause)
            } catch (cause: IOException) {
                throw ImportPreparationException(ImportFailure.DAMAGED_FILE).initCauseQuietly(cause)
            }

        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (cause: CharacterCodingException) {
            throw ImportPreparationException(ImportFailure.NOT_UTF8).initCauseQuietly(cause)
        }
    }
}

/** Keeps the original failure attached for a developer without putting it on screen. */
private fun ImportPreparationException.initCauseQuietly(cause: Throwable): ImportPreparationException = apply { initCause(cause) }
