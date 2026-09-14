package dev.pnptracker.platform.recovery

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.repository.confirmationStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.retention.IMPORT_SNAPSHOT_PREFIX
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.platform.backupfiles.PathBackupInput
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four writes an import is made of, each killed half way through and each
 * killed just after it finished.
 *
 * PLAN 11.4.5 rests the whole of job 7 on one sentence: a write is one
 * transaction, and SQLite either keeps all of it or none of it. This is that
 * sentence measured, on a real process, rather than believed.
 *
 * How each test goes, and why it can be trusted:
 *
 * ```text
 * 1  the database is set up here, through the gate, and closed
 * 2  a second JVM opens it through the gate, exactly as Main does
 * 3  it says the fingerprint of every table BEFORE the write
 * 4  it starts the write through the real store, and either
 *      stops in front of one chosen statement and says INSIDE <in transaction>
 *        and what that transaction has already written, read on its own connection
 *      or finishes and says COMMITTED <fingerprint after>
 * 5  only after that line has arrived, this test kills it with SIGKILL
 * 6  the database is opened again, through the gate, and
 *      foreign_key_check and integrity_check must be clean
 *      the fingerprint must be BEFORE (killed inside) or AFTER (killed after)
 *      and the rows the write is about must say so on their own
 * ```
 *
 * The fingerprint is the backup's own checksum over all fifteen tables, so
 * "nothing partial" is not a list of the tables somebody thought to look at —
 * it is every row the application has.
 *
 * Nothing waits for time to pass. The child is killed when it has *said* where
 * it is, and it stands still there on a latch rather than a sleep.
 */
class InterruptedWriteTest {
    private lateinit var home: RecoveryHome

    @BeforeTest
    fun makeAHome() {
        home = RecoveryHome()
    }

    @AfterTest
    fun sweepTheHome() {
        home.close()
    }

    // ------------------------------------------------ saving a draft import

    @Test
    fun `a draft import killed between its raw cells leaves no batch and no cell`() {
        home.withDatabase { }

        val (before, after) = killedInside(InterruptedWrite.SAVING_A_DRAFT)

        assertEquals(before, after.fingerprint, "a half-saved import left something behind")
        assertEquals(emptyList(), after.data.importBatches, "a batch survived without its cells")
        assertEquals(emptyList(), after.data.rawImportBlocks, "cells survived without the rest of their batch")
    }

    @Test
    fun `a draft import killed just after it was saved is all there and can be opened again`() {
        home.withDatabase { }

        val (before, committed, after) = killedAfter(InterruptedWrite.SAVING_A_DRAFT)

        assertNotEquals(before, committed, "the save wrote nothing")
        assertEquals(committed, after.fingerprint, "a committed import was lost with the process")
        val batch = after.data.importBatches.single()
        assertEquals(ImportBatchStatus.DRAFT.name, batch.status)
        assertEquals(PREPARED_CELL_COUNT, batch.rawBlockCount)
        assertEquals(0, batch.createdTaskCount)
        assertEquals(PREPARED_CELL_COUNT, after.data.rawImportBlocks.count { it.importBatchId == batch.id })
        // And it is where the user looks for it (PLAN 11.4.3).
        home.withDatabase { database ->
            assertEquals(listOf(batch.id), database.importDao().draftBatches().map { it.id.toString() })
        }
    }

    // ------------------------------------------------------ editing a draft

    @Test
    fun `an edit killed between removing and adding colours leaves the draft as it was`() {
        val colours = home.withDatabase { database -> aReadyDraftImport(database).let { twoColours(database) } }

        val (before, after) = killedInside(InterruptedWrite.EDITING_A_DRAFT)

        assertEquals(before, after.fingerprint, "a half-saved edit left something behind")
        val draft = after.data.draftTasks.single()
        assertEquals("Kırmızı figür", draft.name)
        assertEquals(3, draft.requiredQuantity)
        assertEquals(listOf(colours.first().toString() to 0), coloursOf(after.data))
    }

    @Test
    fun `an edit killed just after it was saved keeps every field and every colour`() {
        val colours = home.withDatabase { database -> aReadyDraftImport(database).let { twoColours(database) } }

        val (before, committed, after) = killedAfter(InterruptedWrite.EDITING_A_DRAFT)

        assertNotEquals(before, committed, "the edit wrote nothing")
        assertEquals(committed, after.fingerprint, "a committed edit was lost with the process")
        val draft = after.data.draftTasks.single()
        assertEquals("Mavi figür", draft.name)
        assertEquals(5, draft.requiredQuantity)
        assertEquals(colours.reversed().mapIndexed { slot, id -> id.toString() to slot }, coloursOf(after.data))
    }

    // ------------------------------------------------- confirming an import

    @Test
    fun `a confirmation killed after the batch was marked leaves the draft and the backup, and can be tried again`() {
        home.withDatabase { database -> aReadyDraftImport(database) }

        val (before, after) = killedInside(InterruptedWrite.CONFIRMING)

        assertEquals(before, after.fingerprint, "a half-confirmed import left something behind")
        assertNothingOfAConfirmationIn(after.data)

        // The backup taken in front of the transaction is still a backup, of
        // exactly the database the transaction never changed (PLAN 14.4.8).
        val snapshot = importSnapshots().single()
        val read = runBlocking { readerFor(home.paths, home::probeDirectory).read(PathBackupInput(snapshot)) }
        assertEquals(before, fingerprintOf(assertIs<BackupReadResult.Valid>(read).backup.data))

        // And the import is still there to confirm, through the same store, in
        // the next run of the application.
        val result =
            home.withDatabase { database ->
                val batchId =
                    database
                        .importDao()
                        .draftBatches()
                        .single()
                        .id
                realConfirmationStore(database, home.paths, home::probeDirectory)
                    .confirm(batchId, acknowledgeUnprocessedBlocks = true)
            }
        assertEquals(1, result.createdTaskCount)
        val confirmed = home.reopen()
        confirmed.assertWhole()
        assertAConfirmationIn(confirmed.data)
        assertEquals(2, importSnapshots().size, "the second attempt did not take a backup of its own")
    }

    @Test
    fun `a confirmation killed just after it committed has every task, piece, snapshot row and history line`() {
        home.withDatabase { database -> aReadyDraftImport(database) }

        val (before, committed, after) = killedAfter(InterruptedWrite.CONFIRMING)

        assertNotEquals(before, committed, "the confirmation wrote nothing")
        assertEquals(committed, after.fingerprint, "a committed confirmation was lost with the process")
        assertAConfirmationIn(after.data)
        assertEquals(1, importSnapshots().size)
    }

    // ------------------------------------------------ rolling an import back

    @Test
    fun `a rollback killed before the batch moved leaves the import confirmed and every task in place`() {
        home.withDatabase { database -> confirmedImport(database) }

        val (before, after) = killedInside(InterruptedWrite.ROLLING_BACK)

        assertEquals(before, after.fingerprint, "a half rollback left something behind")
        assertAConfirmationIn(after.data)
        assertTrue(after.data.tasks.all { it.deletedAt == null }, "a task was tombstoned by a rollback that never happened")
    }

    @Test
    fun `a rollback killed just after it committed has every tombstone, removed piece and history line`() {
        home.withDatabase { database -> confirmedImport(database) }

        val (before, committed, after) = killedAfter(InterruptedWrite.ROLLING_BACK)

        assertNotEquals(before, committed, "the rollback wrote nothing")
        assertEquals(committed, after.fingerprint, "a committed rollback was lost with the process")
        assertEquals(
            ImportBatchStatus.ROLLED_BACK.name,
            after.data.importBatches
                .single()
                .status,
        )
        assertTrue(
            after.data.tasks
                .single()
                .deletedAt != null,
            "the task was not tombstoned",
        )
        assertEquals(emptyList(), after.data.cellSegments, "the import's piece of the cell is still there")
        assertEquals(
            listOf(HistoryEventKind.IMPORT_CONFIRMED, HistoryEventKind.IMPORT_ROLLED_BACK, HistoryEventKind.TASK_ROLLED_BACK)
                .map { it.name }
                .sorted(),
            after.data.historyEvents
                .map { it.kind }
                .sorted(),
        )
    }

    // --------------------------------------------------------------- helpers

    /**
     * Starts [write], waits until the child says it is standing inside the
     * transaction, kills it, and opens the database again.
     *
     * @return the fingerprint the child reported before the write, and what the
     *   database holds now.
     */
    private fun killedInside(write: InterruptedWrite): Pair<String, Reopened> {
        val child = home.start(write, Ending.INSIDE)
        assertEquals(PRODUCTION_DURABILITY, Durability.decoded(child.awaitLine("DURABILITY")))
        val before = child.awaitLine("BEFORE")
        // The proof that the kill lands where it is meant to: inside, and in a transaction.
        assertEquals("${write.name} true ${write.halfDone}", child.awaitLine("INSIDE"))

        child.kill()
        assertTrue(child.exitedBySignal, "the child was not ended by SIGKILL")

        return before to reopenedWhole()
    }

    /** The same, for a child that finished the write and is killed afterwards. */
    private fun killedAfter(write: InterruptedWrite): Triple<String, String, Reopened> {
        val child = home.start(write, Ending.COMMITTED)
        assertEquals(PRODUCTION_DURABILITY, Durability.decoded(child.awaitLine("DURABILITY")))
        val before = child.awaitLine("BEFORE")
        val committed = child.awaitLine("COMMITTED")

        child.kill()
        assertTrue(child.exitedBySignal, "the child was not ended by SIGKILL")
        // What was committed lives in the log and nowhere else: the killed
        // process never checkpointed it into the database file. Reading it back
        // below is SQLite's own recovery doing its job.
        val wal = walOf(home.paths.databaseFile)
        assertTrue(Files.exists(wal) && Files.size(wal) > 0, "the committed write was not waiting in the log")

        return Triple(before, committed, reopenedWhole())
    }

    private fun reopenedWhole(): Reopened =
        home.reopen().also { reopened ->
            reopened.assertWhole()
            assertEquals(PRODUCTION_DURABILITY, reopened.durability)
        }

    private fun importSnapshots() =
        Files
            .list(home.paths.backupsDirectory)
            .use { entries -> entries.filter { it.fileName.toString().startsWith(IMPORT_SNAPSHOT_PREFIX) }.sorted().toList() }

    private suspend fun confirmedImport(database: AppDatabase) {
        val batchId = aReadyDraftImport(database)
        confirmationStore(database).confirm(batchId, acknowledgeUnprocessedBlocks = true)
    }

    private fun coloursOf(data: BackupData): List<Pair<String, Int>> =
        data.draftTaskColors.sortedBy { it.slotIndex }.map { it.colorId to it.slotIndex }

    /** Not one row of what a confirmation writes, anywhere. */
    private fun assertNothingOfAConfirmationIn(data: BackupData) {
        val batch = data.importBatches.single()
        assertEquals(ImportBatchStatus.DRAFT.name, batch.status, "the batch moved without the rest of the confirmation")
        assertEquals(0, batch.createdTaskCount, "the count moved without the tasks")
        assertEquals(emptyList(), data.tasks)
        assertEquals(emptyList(), data.taskColors)
        assertEquals(emptyList(), data.taskStages)
        assertEquals(emptyList(), data.cellSegments)
        assertEquals(emptyList(), data.importBatchCells)
        assertEquals(emptyList(), data.historyEvents)
        assertNull(data.draftTasks.single().materializedTaskId, "a draft points at a task that was never kept")
    }

    /** Exactly what one confirmation of one draft writes, and all of it. */
    private fun assertAConfirmationIn(data: BackupData) {
        val batch = data.importBatches.single()
        assertEquals(ImportBatchStatus.CONFIRMED.name, batch.status)
        assertEquals(1, batch.createdTaskCount)
        val task = data.tasks.single()
        assertEquals(task.id, assertNotNull(data.draftTasks.single().materializedTaskId))
        assertEquals(listOf(task.id), data.cellSegments.mapNotNull { it.taskId })
        assertEquals(1, data.taskColors.count { it.taskId == task.id })
        assertTrue(data.taskStages.all { it.taskId == task.id })
        assertEquals(1, data.importBatchCells.size)
        assertEquals(listOf(HistoryEventKind.IMPORT_CONFIRMED.name), data.historyEvents.map { it.kind })
    }

    /**
     * What each transaction has written, on its own connection, at the moment it
     * is killed — the evidence that there was a half to lose.
     */
    private val InterruptedWrite.halfDone: String
        get() =
            when (this) {
                // The batch row and two of the five cells.
                InterruptedWrite.SAVING_A_DRAFT -> "1 batch, 2 cells"
                // The row already renamed, the old colour already deleted, no new colour yet.
                InterruptedWrite.EDITING_A_DRAFT -> "Mavi figür, 0 colours"
                // Everything but the history line: status, task, piece and cell snapshot.
                InterruptedWrite.CONFIRMING -> "CONFIRMED, 1 task, 1 piece, 1 cell snapshot, 0 history"
                // The tombstone, the removed piece and both of its history lines beside the confirmation's; status not yet moved.
                InterruptedWrite.ROLLING_BACK -> "CONFIRMED, 1 tombstone, 0 piece, 3 history"
            }
}
