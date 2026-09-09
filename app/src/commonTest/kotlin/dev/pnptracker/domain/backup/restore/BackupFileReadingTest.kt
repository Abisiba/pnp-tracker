package dev.pnptracker.domain.backup.restore

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much of an untrusted file is read, and what it has to be.
 *
 * These are the checks that stand between a hostile file and everything else.
 * None of them looks at what the file says: they are about its size and its
 * bytes, and every one of them happens before a parser is handed anything.
 *
 * The probe counts how often it was asked, because "the limit is applied before
 * parsing begins" (PLAN 14.4.1) is a claim about what did *not* happen, and the
 * only honest way to test one of those is to watch the thing that should not
 * have been reached.
 */
class BackupFileReadingTest {
    private val probe = CountingProbe()
    private val reader = UntrustedBackupReader(probe)

    @Test
    fun `a file with nothing in it is its own answer, not broken JSON`() =
        runBlocking<Unit> {
            assertEquals(BackupProblem.EMPTY_FILE, refusalOf(FakeBackupInput(ByteArray(0))))
        }

    @Test
    fun `a file of exactly the limit is read`() =
        runBlocking<Unit> {
            // PLAN 14.4.1 says the size is limited to 64 MiB, so 64 MiB itself is
            // within it. Padded with spaces, which JSON allows anywhere, so what
            // this measures is the size gate and not the parser.
            val document = documentOf()
            val padding = MAXIMUM_BACKUP_BYTES.toInt() - document.encodeToByteArray().size
            assertTrue(padding > 0, "the sample document is somehow larger than the limit")
            val file = fileOf(" ".repeat(padding) + document)
            assertEquals(MAXIMUM_BACKUP_BYTES, file.declaredSize())

            val result = reader.read(file)
            assertTrue(result is BackupReadResult.Valid, "a file of exactly the limit was refused: $result")
        }

    @Test
    fun `one byte past the limit is refused`() =
        runBlocking<Unit> {
            val document = documentOf()
            val padding = MAXIMUM_BACKUP_BYTES.toInt() - document.encodeToByteArray().size + 1
            val file = fileOf(" ".repeat(padding) + document)

            assertEquals(BackupProblem.SAFETY_LIMIT, refusalOf(file))
            assertEquals(0, probe.asked)
        }

    @Test
    fun `a file the file system already says is too big is not even opened`() =
        runBlocking<Unit> {
            val file = FakeBackupInput(documentOf().encodeToByteArray(), declared = MAXIMUM_BACKUP_BYTES + 1)

            assertEquals(BackupProblem.SAFETY_LIMIT, refusalOf(file))
            assertEquals(0, file.opened, "a file over the limit was opened anyway")
        }

    @Test
    fun `a file that grew after it was measured is still refused`() =
        runBlocking<Unit> {
            // The size the file system reports is a hint: it is taken before the
            // file is opened, and a file can be replaced or appended to in
            // between. The reading counts for itself.
            val huge = ByteArray(2048) { ' '.code.toByte() }
            val reader = UntrustedBackupReader(probe, maximumBytes = 1024)
            val file = FakeBackupInput(huge, declared = 10)

            val result = reader.read(file)

            assertEquals(BackupProblem.SAFETY_LIMIT, (result as BackupReadResult.Refused).rejection.problem)
            assertEquals(1, file.closed, "the file was not closed after being refused mid-read")
            assertEquals(0, probe.asked)
        }

    @Test
    fun `a file that cannot be opened is unreadable, which is not the same as unparseable`() =
        runBlocking<Unit> {
            val file = FakeBackupInput(ByteArray(4), failToOpen = true)
            assertEquals(BackupProblem.UNREADABLE, refusalOf(file))
        }

    @Test
    fun `a read that fails part way through is unreadable, and the file is closed`() =
        runBlocking<Unit> {
            val file = FakeBackupInput(documentOf().encodeToByteArray(), failAfter = 0)

            assertEquals(BackupProblem.UNREADABLE, refusalOf(file))
            assertEquals(1, file.closed, "the file was left open after a failed read")
        }

    @Test
    fun `the file is closed when the reading went perfectly well`() =
        runBlocking<Unit> {
            val file = fileOf(documentOf())

            assertTrue(reader.read(file) is BackupReadResult.Valid)
            assertEquals(1, file.opened)
            assertEquals(1, file.closed)
        }

    @Test
    fun `bytes that are not UTF-8 are refused rather than repaired`() =
        runBlocking<Unit> {
            // A lone continuation byte in the middle of a game's name. The
            // tolerant reading every platform offers would turn it into a
            // replacement character and hand back a document that parses,
            // carrying a name the user never wrote.
            val document = documentOf().encodeToByteArray()
            val broken = document.copyOf()
            broken[broken.size / 2] = 0x80.toByte()

            assertEquals(BackupProblem.INVALID_UTF8, refusalOf(FakeBackupInput(broken)))
        }

    @Test
    fun `a truncated multi byte character is refused`() =
        runBlocking<Unit> {
            val turkish = "\"Şükrü\"".encodeToByteArray()
            assertEquals(BackupProblem.INVALID_UTF8, refusalOf(FakeBackupInput(turkish.copyOf(turkish.size - 2))))
        }

    @Test
    fun `one byte order mark at the front is skipped, as the CSV reader skips one`() =
        runBlocking<Unit> {
            val marked = BYTE_ORDER_MARK + documentOf()

            assertTrue(reader.read(fileOf(marked)) is BackupReadResult.Valid, "a marked file was refused")
        }

    @Test
    fun `a second byte order mark is not skipped and the document is not JSON`() =
        runBlocking<Unit> {
            val twice = "" + BYTE_ORDER_MARK + BYTE_ORDER_MARK + documentOf()
            assertEquals(BackupProblem.MALFORMED_JSON, refusalOf(fileOf(twice)))
        }

    @Test
    fun `a UTF-16 file is refused rather than read as something else`() =
        runBlocking<Unit> {
            // The little endian mark is not valid UTF-8, so this never gets as
            // far as looking like JSON.
            val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + interleaveWithNulls(documentOf())

            assertEquals(BackupProblem.INVALID_UTF8, refusalOf(FakeBackupInput(utf16)))
        }

    @Test
    fun `a UTF-16 file without a mark is not JSON either`() =
        runBlocking<Unit> {
            // Every second byte is a NUL, which is valid UTF-8 on its own and is
            // not anything JSON allows outside a string. So this one gets past
            // the decoding and is stopped by not being a document.
            val problem = refusalOf(FakeBackupInput(interleaveWithNulls("{\"format\":\"pnp-tracker-backup\"}")))
            assertEquals(BackupProblem.MALFORMED_JSON, problem)
        }

    @Test
    fun `Turkish letters and characters outside the basic plane survive the reading`() =
        runBlocking<Unit> {
            val awkward = "Şükrü'nün işi 👨‍👩‍👧‍👦 🇹🇷"
            val data = aWholeBackup().let { it.copy(games = it.games.map { game -> game.copy(name = awkward) }) }

            val result = reader.read(fileOf(documentOf(data)))

            val read = (result as BackupReadResult.Valid).backup
            assertEquals(
                awkward,
                read.data.games
                    .single()
                    .name,
            )
        }

    private suspend fun refusalOf(file: FakeBackupInput): BackupProblem = (reader.read(file) as BackupReadResult.Refused).rejection.problem

    private fun interleaveWithNulls(text: String): ByteArray =
        ByteArray(text.length * 2) { at -> if (at % 2 == 0) text[at / 2].code.toByte() else 0 }
}
