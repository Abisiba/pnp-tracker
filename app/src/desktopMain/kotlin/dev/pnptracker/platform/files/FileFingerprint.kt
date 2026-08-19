package dev.pnptracker.platform.files

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val BUFFER_BYTES = 64 * 1024

/**
 * The SHA-256 of a file's contents, as 64 lower case hex characters.
 *
 * Read in blocks rather than all at once: a spreadsheet is normally small, but
 * nothing here should decide how much memory the application needs, and the
 * cost of streaming is nil.
 *
 * Only the bytes go into the digest. The name is not part of it, so renaming a
 * file does not make it look like a different one and two copies under different
 * names are recognised as the same file.
 */
class FileFingerprint {
    /** @throws IOException if the file cannot be read; the stream is closed either way. */
    fun of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        Files.newInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            byte.toUByte().toString(radix = 16).padStart(2, '0')
        }
    }
}
