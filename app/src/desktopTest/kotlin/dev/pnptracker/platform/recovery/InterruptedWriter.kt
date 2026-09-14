package dev.pnptracker.platform.recovery

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.files.XdgAppPathsResolver
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** Every line this process says to the test starts with this, so nothing else it prints can be mistaken for one. */
const val PROTOCOL = "RECOVERY:"

/** The four writes a process can be killed in the middle of. */
enum class InterruptedWrite(
    /** The statement the process stops in front of, in [normalisedSql] form. */
    val stopsAt: (String) -> Boolean,
    /** Which run of that statement. */
    val nth: Int,
    /**
     * Asked on the transaction's own connection at the moment it stops: how much
     * of the write is already in. What the test is shown is therefore not a
     * transaction that had not begun, but one caught with its work half done.
     */
    val soFar: String,
) {
    /** `ImportDraftStore.save`: the third of five raw cells, after the batch and two cells are in. */
    SAVING_A_DRAFT(
        stopsAt = { it.startsWith("INSERT") && " INTO RAW_IMPORT_BLOCKS " in "$it " },
        nth = 3,
        soFar = "SELECT (SELECT COUNT(*) FROM import_batches) || ' batch, ' || (SELECT COUNT(*) FROM raw_import_blocks) || ' cells'",
    ),

    /** `ImportReviewStore.saveDraft`: the first new colour, after the row is updated and the old colours deleted. */
    EDITING_A_DRAFT(
        stopsAt = { it.startsWith("INSERT") && " INTO DRAFT_TASK_COLORS " in "$it " },
        nth = 1,
        soFar = "SELECT (SELECT name FROM draft_tasks) || ', ' || (SELECT COUNT(*) FROM draft_task_colors) || ' colours'",
    ),

    /**
     * `ImportConfirmationStore.confirm`: the history line, which is the last
     * write before the postcondition — tasks, colours, stages, pieces, cell
     * snapshots, the drafts' links and the batch's status are all written by then.
     */
    CONFIRMING(
        stopsAt = { it.startsWith("INSERT") && " INTO HISTORY_EVENTS " in "$it " },
        nth = 1,
        soFar =
            "SELECT (SELECT status FROM import_batches) || ', ' || (SELECT COUNT(*) FROM tasks) || ' task, ' || " +
                "(SELECT COUNT(*) FROM cell_segments) || ' piece, ' || (SELECT COUNT(*) FROM import_batch_cells) || " +
                "' cell snapshot, ' || (SELECT COUNT(*) FROM history_events) || ' history'",
    ),

    /**
     * `ImportRollbackStore.rollBack`: the batch's move to rolled back, which is
     * the last write — tombstones, history lines and removed pieces are in.
     */
    ROLLING_BACK(
        stopsAt = { it.startsWith("UPDATE IMPORT_BATCHES SET STATUS = 'ROLLED_BACK'") },
        nth = 1,
        soFar =
            "SELECT (SELECT status FROM import_batches) || ', ' || " +
                "(SELECT COUNT(*) FROM tasks WHERE deleted_at IS NOT NULL) || ' tombstone, ' || " +
                "(SELECT COUNT(*) FROM cell_segments) || ' piece, ' || (SELECT COUNT(*) FROM history_events) || ' history'",
    ),
}

/** How far the process is allowed to get. */
enum class Ending {
    /** It stops at the statement, inside the transaction, and waits to be killed. */
    INSIDE,

    /** It finishes the write, says so, and waits to be killed. */
    COMMITTED,

    /** It finishes the write, closes the database the way the window does, and exits. */
    CLOSED,
}

/**
 * The application, started by a test, doing one write and then being killed.
 *
 * Built the way `Main` builds it: the paths come from this process's own
 * `XDG_DATA_HOME` and `XDG_CONFIG_HOME` — which the test points at a temporary
 * home — the database is opened through [dev.pnptracker.platform.startup.StartupGate],
 * and the write is made through the real store the screen calls. The only thing
 * added is [StoppingSqliteDriver], and it adds nothing but a place to stand still.
 *
 * It says, in order and on lines of their own:
 *
 * ```text
 * RECOVERY:DURABILITY <journal/synchronous of both connections>
 * RECOVERY:BEFORE <fingerprint of every table before the write>
 * RECOVERY:INSIDE <write> <in a transaction?> <what it has written so far>
 *                                                   then waits for ever, or
 * RECOVERY:COMMITTED <fingerprint after the write>  then waits for ever, or
 * RECOVERY:CLOSED <fingerprint after the write>     then exits
 * ```
 *
 * Waiting is a latch nobody counts down, not a sleep: the process has nothing to
 * wait *for*, and being killed is how it ends. A write that goes past the chosen
 * statement without stopping says `MISSED` and exits, so a test can never kill a
 * process that was not where it thinks it was.
 */
fun main(args: Array<String>) {
    val write = InterruptedWrite.valueOf(args[0])
    val ending = Ending.valueOf(args[1])
    val paths = XdgAppPathsResolver().resolve()

    val driver =
        StoppingSqliteDriver(
            stopsAt = if (ending == Ending.INSIDE) write.stopsAt else { _ -> false },
            nth = write.nth,
            onReached = { connection ->
                val written =
                    connection.prepare(write.soFar).use { statement ->
                        check(statement.step()) { "the question about the transaction returned no row" }
                        statement.getText(0)
                    }
                say("INSIDE ${write.name} ${connection.inTransaction()} $written")
                waitToBeKilled()
            },
        )
    val database = gateFor(paths, DatabaseFactory(driver = driver)).open().database

    runBlocking {
        say("DURABILITY ${durabilityOf(database).encoded()}")
        say("BEFORE ${fingerprintOf(everythingIn(database))}")
        make(write, database, paths)
        if (ending == Ending.INSIDE) {
            say("MISSED ${write.name}")
            exitProcess(2)
        }
        val after = fingerprintOf(everythingIn(database))
        if (ending == Ending.CLOSED) {
            database.close()
            say("CLOSED $after")
            exitProcess(0)
        }
        say("COMMITTED $after")
    }
    waitToBeKilled()
}

private suspend fun make(
    write: InterruptedWrite,
    database: AppDatabase,
    paths: XdgAppPaths,
) {
    val importDao = database.importDao()
    when (write) {
        InterruptedWrite.SAVING_A_DRAFT -> ImportDraftStore(importDao).save(aPreparedImport())

        InterruptedWrite.EDITING_A_DRAFT -> {
            val block = importDao.rawBlocksOfBatch(importDao.draftBatches().single().id).first()
            val draft = importDao.draftTasksOfBlock(block.id).single()
            val colours = twoColours(database)
            ImportReviewStore(importDao, database.gameDao(), database.colorDao()).saveDraft(
                aDraftEdit(
                    draftId = draft.id,
                    cellId = checkNotNull(draft.targetCellId),
                    name = "Mavi figür",
                    quantity = 5,
                    colours = colours.reversed(),
                ),
            )
        }

        InterruptedWrite.CONFIRMING -> {
            val batchId = importDao.draftBatches().single().id
            // The test gives this process a temporary directory inside its own
            // home, so even the throwaway probe database lands where it is swept.
            realConfirmationStore(database, paths) { Files.createTempDirectory("pnp-tracker-recovery-probe") }
                .confirm(batchId, acknowledgeUnprocessedBlocks = true)
        }

        InterruptedWrite.ROLLING_BACK -> {
            val batchId = importDao.allBatches().single { it.status == ImportBatchStatus.CONFIRMED }.id
            ImportRollbackStore(importDao).rollBack(batchId)
        }
    }
}

private fun say(line: String) {
    println("$PROTOCOL$line")
    System.out.flush()
}

private fun waitToBeKilled(): Nothing {
    val nobodyCountsThisDown = CountDownLatch(1)
    while (true) nobodyCountsThisDown.await()
}
