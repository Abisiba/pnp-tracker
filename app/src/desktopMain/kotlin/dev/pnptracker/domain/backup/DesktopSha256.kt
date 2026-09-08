package dev.pnptracker.domain.backup

import java.security.MessageDigest

/**
 * The platform's own SHA-256. Nothing here computes a digest; it asks for one.
 */
actual fun sha256Of(bytes: ByteArray): String = lowerCaseHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/**
 * A digest as the text everything in this application writes it in: lower case,
 * two characters a byte, no separators.
 *
 * Shared with the file fingerprint the import flow takes, so a checksum written
 * into a backup and one written into `import_batches` are the same shape of
 * string and there is one place that decides what that shape is.
 */
internal fun lowerCaseHex(bytes: ByteArray): String =
    bytes.joinToString("") { byte ->
        byte.toUByte().toString(radix = 16).padStart(2, '0')
    }
