package dev.pnptracker.platform.recovery

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * How durable a commit is on this setup — measured, because PLAN 11.4.5 says so.
 *
 * `DatabaseFactory` names neither a journal mode nor a synchronous level, so both
 * are whatever Room and the bundled driver decide. The Room jar carries two
 * pairings (WAL with NORMAL, TRUNCATE with FULL), and which one a desktop build
 * gets is a question with an answer on the machine rather than in the
 * documentation. This asks it, on both kinds of connection Room hands out, on a
 * brand new database and again on one reopened after a close.
 *
 * What the answer means is written in the master context: under WAL with
 * `synchronous = NORMAL`, killing the process loses no committed transaction and
 * power loss can lose the newest ones — and nothing, in either case, leaves half
 * of one. [InterruptedWriteTest] measures the first half of that sentence.
 */
class DurabilityTest {
    private lateinit var home: RecoveryHome

    @BeforeTest
    fun makeAHome() {
        home = RecoveryHome()
    }

    @AfterTest
    fun sweepTheHome() {
        home.close()
    }

    @Test
    fun `a new database runs in write-ahead mode with normal synchronisation on every connection`() {
        val measured = home.withDatabase { database -> durabilityOf(database) }

        assertEquals("wal", measured.writerJournalMode)
        assertEquals("wal", measured.readerJournalMode)
        // 1 is NORMAL: 0 would be OFF, 2 FULL, 3 EXTRA.
        assertEquals(1L, measured.writerSynchronous)
        assertEquals(1L, measured.readerSynchronous)
        assertEquals(PRODUCTION_DURABILITY, measured)
    }

    @Test
    fun `the journal mode is written into the file and a reopened database comes back the same`() {
        home.withDatabase { database -> aReadyDraftImport(database) }

        // Bytes 18 and 19 of an SQLite file are its read and write versions, and
        // 2 in both is how the file itself records write-ahead logging — read
        // straight off the disk, with no connection that could set it again.
        val header = Files.readAllBytes(home.paths.databaseFile).copyOfRange(18, 20).toList()
        assertEquals(listOf<Byte>(2, 2), header)
        // A tidy close checkpoints the log away; nothing is left beside the file.
        assertFalse(Files.exists(walOf(home.paths.databaseFile)), "a closed database left its log behind")

        val reopened = home.reopen()
        reopened.assertWhole()
        assertEquals(PRODUCTION_DURABILITY, reopened.durability)
    }
}
