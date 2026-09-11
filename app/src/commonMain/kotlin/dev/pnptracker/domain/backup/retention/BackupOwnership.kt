package dev.pnptracker.domain.backup.retention

import dev.pnptracker.domain.backup.BACKUP_FORMAT

/**
 * How many bytes of a file rotation needs in order to recognise it.
 *
 * Sixty-four, which is what the longer of the two questions below needs: a
 * SQLite header is exactly that long and keeps its schema version at the end of
 * it. Bounded on purpose — rotation looks at every candidate in the folder and a
 * backup may be tens of megabytes, so reading the whole of one to decide whether
 * it may be deleted would make the housekeeping cost more than the work it
 * follows.
 */
const val BACKUP_HEADER_BYTES: Int = 64

/**
 * The characters the canonical writer always starts a backup document with.
 *
 * `format` is the first field of the envelope and the writer emits compact JSON
 * with no spaces, so every file it has written begins with exactly these bytes
 * and then the format version's digits. That is not a coincidence being leaned
 * on quietly: the envelope's field order is part of the format (PLAN 14.4.1) and
 * a test holds this constant against a document the writer really produced.
 */
private const val CANONICAL_OPENING = "{\"format\":\"$BACKUP_FORMAT\",\"formatVersion\":"

/** The sixteen bytes every SQLite database begins with, the terminator included. */
private val SQLITE_MAGIC: ByteArray = "SQLite format 3".encodeToByteArray() + 0

private const val PAGE_SIZE_OFFSET = 16

private const val USER_VERSION_OFFSET = 60

/**
 * Whether [header] is the start of a document this application wrote.
 *
 * The second of the two things standing between rotation and somebody's data,
 * and the one that costs a read. A name can be typed by anybody; these bytes
 * cannot be arrived at by accident. Together with `automaticBackupNameOf` they
 * are what PLAN 14.4.11 means by proving ownership rather than matching a
 * prefix.
 *
 * Not a parse. Parsing a backup means reading all of it, checking a digest over
 * all of it and trying it on a database — which is exactly right before putting
 * one back, and far too much before deleting an old one. The question here is
 * narrower and answerable from the first few bytes: did the canonical writer
 * produce this file. A file that fails is not called broken; it is left alone.
 */
fun beginsLikeABackupDocument(header: ByteArray): Boolean {
    val opening = CANONICAL_OPENING.encodeToByteArray()
    if (header.size <= opening.size) return false
    if (!header.startsWith(opening)) return false
    // A digit has to follow, so a file that opens with our words and then says
    // something of its own is not mistaken for one of ours.
    return header[opening.size].toInt().toChar() in '0'..'9'
}

/**
 * Whether [header] is the start of a SQLite database still on schema [userVersion].
 *
 * The raw half of a migration set is not a backup document and cannot be checked
 * like one, so it is checked as what it is. Three things have to agree: the
 * magic every SQLite file carries, a page size the format allows, and the schema
 * version — which the file name already claims, so this is the file and its own
 * name saying the same thing.
 *
 * That last agreement is the useful one. A `.db` dropped into the folder under
 * one of our names is only ever removed if it really is a database of the
 * version its name says, and PLAN 14.4.9's raw copy is given a contract to meet:
 * a migration snapshot keeps the schema version it was taken from. A pair that
 * cannot show this is left in place for good, which costs a little disk and
 * never costs a file.
 */
fun beginsLikeADatabaseOfVersion(
    header: ByteArray,
    userVersion: Int,
): Boolean {
    if (header.size < BACKUP_HEADER_BYTES) return false
    if (!header.startsWith(SQLITE_MAGIC)) return false
    if (!isAllowedPageSize(bigEndianShort(header, PAGE_SIZE_OFFSET))) return false
    return bigEndianInt(header, USER_VERSION_OFFSET) == userVersion
}

/**
 * SQLite writes a page size of 65536 as 1, because the field is two bytes and
 * the value does not fit; every other allowed size is a power of two from 512 up.
 */
private fun isAllowedPageSize(stored: Int): Boolean = stored == 1 || (stored >= 512 && stored and (stored - 1) == 0)

private fun bigEndianShort(
    bytes: ByteArray,
    offset: Int,
): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

private fun bigEndianInt(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { this[it] == prefix[it] }
}
