package dev.pnptracker.domain.backup.retention

import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/**
 * Whether a file's first bytes show this application wrote it.
 *
 * The half of the ownership test that costs a read, and the reason it exists is
 * that a name can be typed by anybody. Rotation is about to delete files, and
 * PLAN 14.4.11 asks for proof rather than a matching prefix; what follows is
 * that proof being held against real documents and against the near misses it
 * has to refuse.
 */
class BackupOwnershipTest {
    /** A real document from the real writer, not a string written out here. */
    private fun aRealDocument(): ByteArray =
        backupDocumentOf(anEmptyBackup(), appVersion = "0.1.0", sourceSchemaVersion = 8, createdAt = MOMENT)
            .json
            .encodeToByteArray()

    @Test
    fun `the opening this looks for is the opening the writer really writes`() {
        // The point of the test. If the envelope's field order or the writer's
        // compactness ever changed, this constant would quietly stop matching
        // real backups and rotation would stop clearing anything — so the
        // constant is checked against the writer rather than against itself.
        val document = aRealDocument()

        assertTrue(beginsLikeABackupDocument(document.copyOf(BACKUP_HEADER_BYTES)))
        assertTrue(document.decodeToString().startsWith("{\"format\":\"pnp-tracker-backup\",\"formatVersion\":1,"))
    }

    @Test
    fun `a file that is not one of ours is not taken for one`() {
        val refused =
            listOf(
                "",
                "{}",
                "[]",
                "not json at all",
                // Our words, then something of its own.
                "{\"format\":\"pnp-tracker-backup\",\"formatVersion\":\"1\"}",
                // Almost our envelope, with a space the compact writer never emits.
                "{\"format\": \"pnp-tracker-backup\",\"formatVersion\":1}",
                // Right shape, wrong format name.
                "{\"format\":\"pnp-tracker-export\",\"formatVersion\":1}",
                // Our opening, but not first.
                " {\"format\":\"pnp-tracker-backup\",\"formatVersion\":1}",
            )

        refused.forEach { text ->
            assertFalse(beginsLikeABackupDocument(text.encodeToByteArray()), text)
        }
    }

    @Test
    fun `a header that stops before the answer is no answer`() {
        val document = aRealDocument()

        // Truncation is what an unreadable or half-written file looks like from
        // here, and it has to read as "not shown to be ours" rather than as ours.
        (0 until 46).forEach { length ->
            assertFalse(beginsLikeABackupDocument(document.copyOf(length)), "$length bayt")
        }
    }

    @Test
    fun `a database is recognised by its magic, its page size and its own version`() {
        assertTrue(beginsLikeADatabaseOfVersion(aDatabaseHeader(pageSize = 4096, userVersion = 3), userVersion = 3))
        assertTrue(beginsLikeADatabaseOfVersion(aDatabaseHeader(pageSize = 512, userVersion = 1), userVersion = 1))
        // 65536 is stored as 1, because the field is two bytes wide.
        assertTrue(beginsLikeADatabaseOfVersion(aDatabaseHeader(pageSize = 1, userVersion = 7), userVersion = 7))
    }

    @Test
    fun `a database whose version is not the one its name claims is left alone`() {
        // The agreement between the file and its own name is the whole strength
        // of this check: a `.db` under one of our names is only removed when it
        // really is a database of the version the name says (PLAN 14.4.9).
        assertFalse(beginsLikeADatabaseOfVersion(aDatabaseHeader(pageSize = 4096, userVersion = 8), userVersion = 3))
        assertFalse(beginsLikeADatabaseOfVersion(aDatabaseHeader(pageSize = 4096, userVersion = 0), userVersion = 3))
    }

    @Test
    fun `anything that is not a database is not treated as one`() {
        assertFalse(beginsLikeADatabaseOfVersion(ByteArray(0), userVersion = 3))
        assertFalse(beginsLikeADatabaseOfVersion(ByteArray(BACKUP_HEADER_BYTES), userVersion = 0))
        // Right length, wrong magic.
        val wrongMagic = aDatabaseHeader(pageSize = 4096, userVersion = 3).also { it[0] = 'X'.code.toByte() }
        assertFalse(beginsLikeADatabaseOfVersion(wrongMagic, userVersion = 3))
        // Right magic, a page size the format does not allow.
        assertFalse(beginsLikeADatabaseOfVersion(aDatabaseHeader(pageSize = 1000, userVersion = 3), userVersion = 3))
        assertFalse(beginsLikeADatabaseOfVersion(aDatabaseHeader(pageSize = 256, userVersion = 3), userVersion = 3))
        // A database header that stops short says nothing.
        assertFalse(beginsLikeADatabaseOfVersion(aDatabaseHeader(4096, 3).copyOf(BACKUP_HEADER_BYTES - 1), userVersion = 3))
    }

    @Test
    fun `a backup document is not a database and a database is not a document`() {
        val document = aRealDocument().copyOf(BACKUP_HEADER_BYTES)
        val database = aDatabaseHeader(pageSize = 4096, userVersion = 3)

        assertFalse(beginsLikeADatabaseOfVersion(document, userVersion = 3))
        assertFalse(beginsLikeABackupDocument(database))
    }

    /** The first 64 bytes of a SQLite file, as the format lays them out. */
    private fun aDatabaseHeader(
        pageSize: Int,
        userVersion: Int,
    ): ByteArray {
        val header = ByteArray(BACKUP_HEADER_BYTES)
        "SQLite format 3".encodeToByteArray().copyInto(header)
        header[15] = 0
        header[16] = ((pageSize shr 8) and 0xFF).toByte()
        header[17] = (pageSize and 0xFF).toByte()
        header[60] = ((userVersion shr 24) and 0xFF).toByte()
        header[61] = ((userVersion shr 16) and 0xFF).toByte()
        header[62] = ((userVersion shr 8) and 0xFF).toByte()
        header[63] = (userVersion and 0xFF).toByte()
        return header
    }
}
