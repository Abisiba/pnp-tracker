package dev.pnptracker.data.database

import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.HistoryStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.ui.feature.settings.aSafetySnapshot
import dev.pnptracker.ui.feature.settings.aValidatedBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/**
 * Whether the screens really follow a restore, without the application restarting.
 *
 * PLAN 14.4.3 says they must, and the reason it is worth a test of its own is
 * that the restore writes through raw statements rather than through the DAOs
 * every other write goes through. Room notices a change by way of triggers it
 * installs on the tables somebody is watching, and a replacement made this way
 * fires those triggers exactly as an ordinary write does — but that is a claim
 * about a library, and a claim about a library is worth checking against the
 * library.
 *
 * Each of these watches a real flow, the one a real screen reads through, and
 * asks whether the value it is given after the restore is the restored one. They
 * wait for that value rather than sampling once, because "eventually" is what a
 * flow promises; the timeout is what turns "never" into a failure instead of a
 * hung build.
 */
class RestoreInvalidationTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExisted = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExisted)
        directory.delete()
    }

    @Test
    fun `every screen that was already open is given the restored data`() =
        runBlocking<Unit> {
            fillWithEverything(database)
            val before = BackupStore(database).snapshot().data
            assertTrue(before.games.isNotEmpty() && before.colors.isNotEmpty())

            val pools = PoolStore(database.poolDao())
            coroutineScope {
                // Collected *before* the restore, which is the situation being
                // tested: a screen somebody already had open. Each waits for a
                // value that answers its question rather than sampling once,
                // because a flow's promise is "eventually", and none of them can
                // be answered by the reading they already have — a backup of
                // nothing makes every one of these lists empty, and every one of
                // them is full right now.
                val games =
                    waitingFor(GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()).observeTable()) {
                        it.isEmpty()
                    }
                val colors = waitingFor(ColorCatalogueStore(database.colorDao()).observeColors()) { it.isEmpty() }
                val history = waitingFor(HistoryStore(database.historyDao()).observeHistory()) { it.entries.isEmpty() }
                val imports =
                    waitingFor(
                        ImportRollbackStore(database.importDao(), IdGenerator.Random, StoppedClock(MOMENT)).observeSettledImports(),
                    ) { it.isEmpty() }
                val poolTasks = PoolType.entries.associateWith { waitingFor(pools.observePool(it)) { pool -> pool.tasks.isEmpty() } }
                val summary =
                    waitingFor(pools.observeNavigationSummary()) { counts ->
                        PoolType.entries.all { counts.activeCountOf(it) == 0 }
                    }
                yield()

                val empty = aValidatedBackup(anEmptyBackup())
                assertNull(LiveBackupRestorer(database).restore(empty, aSafetySnapshot(before)))

                assertEquals(emptyList(), games.await(), "the game table kept showing games that had gone")
                assertEquals(emptyList(), colors.await(), "the colours screen kept the old catalogue")
                assertEquals(emptyList(), history.await().entries, "the history kept its old lines")
                assertEquals(emptyList(), imports.await(), "the import list kept batches that had gone")
                poolTasks.forEach { (poolType, waiting) ->
                    assertEquals(emptyList(), waiting.await().tasks, "the $poolType pool kept tasks that had gone")
                }
                val counts = summary.await()
                PoolType.entries.forEach { assertEquals(0, counts.activeCountOf(it), "the sidebar kept counting $it") }
            }
        }

    @Test
    fun `a screen opened after the restore reads the restored data too`() =
        runBlocking<Unit> {
            // The other half: nothing was watching while the restore happened, so
            // there is no invalidation to deliver and the first reading simply
            // has to be right.
            fillWithEverything(database)
            val before = BackupStore(database).snapshot().data

            assertNull(LiveBackupRestorer(database).restore(aValidatedBackup(anEmptyBackup()), aSafetySnapshot(before)))

            val colors = ColorCatalogueStore(database.colorDao()).observeColors()
            assertEquals(emptyList(), withTimeout(10.seconds) { colors.first() })
        }

    /**
     * Starts collecting now, and answers with the first value that suits.
     *
     * Started before the change it is waiting for, so what it proves is that the
     * value arrived rather than that it was there all along. The timeout turns
     * "never" into a failed test instead of a build that hangs.
     */
    private fun <T> CoroutineScope.waitingFor(
        flow: Flow<T>,
        wanted: (T) -> Boolean,
    ): Deferred<T> = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10.seconds) { flow.first(wanted) } }
}
