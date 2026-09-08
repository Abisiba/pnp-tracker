package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aDraftTask
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The list of imports that have already been confirmed.
 *
 * It is one row per import and it has to stay that way. The obvious way to write
 * it — joining out to the tasks or the cells to say how much each one made —
 * would repeat a batch once per row it touched, and the user would be offered
 * the same import four times with a rollback button on each. So what is asked
 * here is that a batch appears exactly once, that a draft never appears at all,
 * that the order is fixed, and that reading forty-two costs what reading one
 * costs (PLAN 16).
 */
class SettledImportListTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false

    private val importedAt = Instant.fromEpochMilliseconds(1_780_000_000_000)

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private val importDao get() = database.importDao()

    private fun store() = ImportRollbackStore(importDao)

    private var batches = 0

    /**
     * One import of [drafts] tasks, left as a draft or carried through to a
     * confirmation and possibly back out again.
     */
    private suspend fun anImport(
        fileName: String,
        drafts: Int = 1,
        status: ImportBatchStatus = ImportBatchStatus.CONFIRMED,
        at: Instant = importedAt,
    ): EntityId {
        val batch =
            anImportBatch(rawBlockCount = drafts, sha256 = "%064x".format(batches++))
                .copy(fileName = fileName, importedAt = at, updatedAt = at)
        importDao.insertBatch(batch)
        val game = aGame(name = "Oyun ${IdGenerator.Random.newId()}")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        repeat(drafts) { at2 ->
            val block =
                aRawImportBlock(batch.id, rowIndex = at2 + 1, columnIndex = 1, sourceColumnType = SourceColumnType.THREE_D)
            importDao.insertRawBlock(block)
            importDao.setRawBlockProcessed(block.id, true, updatedAt)
            val draft =
                aDraftTask(block.id, name = "Token $at2").copy(requiredQuantity = 5, createdAt = createdAt + (at2 + 1).seconds)
            importDao.addDraftTask(draft)
            importDao.setDraftTargetUnderReview(draft.id, cell.id, PoolType.THREE_D, TrackingMode.THREE_D_BATCH, updatedAt)
        }
        if (status == ImportBatchStatus.DRAFT) return batch.id

        importDao.confirmDraftBatch(batch.id, true, StoppedClock(importedAt), IdGenerator.Random)
        if (status == ImportBatchStatus.ROLLED_BACK) {
            store().rollBack(batch.id)
        }
        return batch.id
    }

    private suspend fun listed() = store().observeSettledImports().first()

    private fun ran(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .map { it.trimStart().uppercase().replace(Regex("\\s+"), " ") }
            .filterNot { it.startsWith("BEGIN") || it.startsWith("COMMIT") || it.startsWith("END") }
            .filterNot { it.startsWith("ROLLBACK") || it.startsWith("SAVEPOINT") || it.startsWith("RELEASE") }
            .filterNot { it.startsWith("PRAGMA") }
            .groupingBy { statement ->
                when {
                    statement.startsWith("SELECT") && "FROM IMPORT_BATCHES" in statement -> "SELECT import_batches"
                    statement.startsWith("SELECT") -> "SELECT other"
                    else -> "other"
                }
            }.eachCount()

    // -------------------------------------------------------------- what is in it

    @Test
    fun `a confirmed import and one taken back are both there, and a draft is not`() =
        runBlocking<Unit> {
            val confirmed = anImport("Onaylı.xlsx")
            val takenBack = anImport("Geri alınmış.xlsx", status = ImportBatchStatus.ROLLED_BACK)
            val draft = anImport("Yarım.csv", status = ImportBatchStatus.DRAFT)

            val listed = listed()

            assertEquals(setOf(confirmed, takenBack), listed.map { it.batchId }.toSet())
            assertTrue(listed.none { it.batchId == draft }, "a draft was offered a way to be taken back")
        }

    @Test
    fun `an import that made forty-two tasks is one row, not forty-two`() =
        runBlocking<Unit> {
            anImport("Büyük.xlsx", drafts = 42)

            val listed = listed()

            // The shape a join to the tasks would produce, and the reason this
            // list is read straight off one table.
            assertEquals(1, listed.size, "the import was repeated once per row it made")
            assertEquals(42, listed.single().createdTaskCount)
            assertEquals("Büyük.xlsx", listed.single().fileName)
        }

    @Test
    fun `only a confirmed import is offered a way back`() =
        runBlocking<Unit> {
            anImport("Onaylı.xlsx")
            anImport("Geri alınmış.xlsx", status = ImportBatchStatus.ROLLED_BACK)

            assertEquals(
                mapOf("Onaylı.xlsx" to true, "Geri alınmış.xlsx" to false),
                listed().associate { it.fileName to it.canBeTakenBack },
            )
        }

    @Test
    fun `the newest is first, and imports of the same moment keep a fixed order`() =
        runBlocking<Unit> {
            val older = anImport("Eski.xlsx", at = importedAt)
            val newer = anImport("Yeni.xlsx", at = importedAt + 60.seconds)
            // Two saved inside the same millisecond, which a quick pair of
            // imports really can be.
            val sameA = anImport("Aynı anda A.xlsx", at = importedAt + 30.seconds)
            val sameB = anImport("Aynı anda B.xlsx", at = importedAt + 30.seconds)

            val once = listed().map { it.batchId }
            val again = listed().map { it.batchId }

            assertEquals(newer, once.first(), "the newest import was not at the top")
            assertEquals(older, once.last(), "the oldest import was not at the bottom")
            assertEquals(once, again, "two readings of one table gave two orders")
            assertEquals(setOf(sameA, sameB), once.drop(1).dropLast(1).toSet())
        }

    @Test
    fun `an import that has just been taken back turns up as such without being asked again`() =
        runBlocking<Unit> {
            val batchId = anImport("Onaylı.xlsx")
            assertEquals(listOf(true), listed().map { it.canBeTakenBack })

            store().rollBack(batchId)

            // The list is observed, so the row the transaction wrote is what the
            // next reading gives back — the screen does not have to be told.
            assertEquals(listOf(false), listed().map { it.canBeTakenBack })
            assertEquals(ImportBatchStatus.ROLLED_BACK, listed().single().status)
        }

    // ------------------------------------------------------------- what it costs

    @Test
    fun `reading forty-two imports asks exactly what reading one asks`() =
        runBlocking<Unit> {
            anImport("Bir.xlsx")
            driver.start()
            listed()
            val forOne = ran(driver.stop())

            repeat(41) { anImport("Dosya $it.xlsx") }
            driver.start()
            val many = listed()
            val forMany = ran(driver.stop())

            assertEquals(42, many.size, "the fixture is not what this test is about")
            assertEquals(forOne, forMany, "a longer list cost more questions")
            assertEquals(1, forMany["SELECT import_batches"], "the list was read one import at a time")
            assertEquals(
                null,
                forMany["SELECT other"],
                "the list reached out to another table and can be multiplied by it: $forMany",
            )
        }

    @Test
    fun `an import of forty-two tasks costs the same as an import of one`() =
        runBlocking<Unit> {
            anImport("Küçük.xlsx", drafts = 1)
            driver.start()
            listed()
            val small = ran(driver.stop())

            anImport("Büyük.xlsx", drafts = 42)
            driver.start()
            listed()
            val large = ran(driver.stop())

            assertEquals(small, large, "a larger import cost the list more to read")
        }
}
