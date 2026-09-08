package dev.pnptracker.domain.backup

/**
 * The SHA-256 of some bytes, as 64 lower case hex characters.
 *
 * Declared here and answered per platform for the same reason
 * [dev.pnptracker.domain.text.graphemeBoundariesOf] is: the common runtime has
 * no digest, and the JVM's is a `java.security` type that has no business
 * travelling through shared code.
 *
 * There is one digest implementation on each platform and this is the only way
 * to it. PLAN 14.4.1 makes a backup's checksum part of the format, so a second
 * implementation would be a second answer waiting to disagree with the first.
 */
expect fun sha256Of(bytes: ByteArray): String
