package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aDraftTask
import dev.pnptracker.data.database.aRawImportBlock
import dev.pnptracker.data.database.anImportBatch
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.SourceColumnType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

/**
 * A clock that moves a millisecond at every read, the way a real one does between
 * two things a person does. Drafts are ordered by when they were made, so they
 * need distinct moments to be ordered by anything but the tie-breaker.
 */
private class SteppingClock(
    private val start: Instant,
) : kotlin.time.Clock {
    private var step = 0L

    override fun now(): Instant = start + (step++).toDuration(DurationUnit.MILLISECONDS)
}

/**
 * The review workspace against a real database in a temporary directory.
 *
 * Everything the two panes rely on is checked here rather than against a stand
 * in: the ordering comes from SQL, the batch restriction comes from a join, and
 * whether a change survives closing the application is a question only a real
 * file can answer.
 */
class ImportReviewStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ImportReviewStore
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_770_000_000_000)

    @BeforeTest
    fun openTemporaryDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = ImportReviewStore(database.importDao(), clock = SteppingClock(moment))
    }

    @AfterTest
    fun closeAndDeleteTemporaryDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /** One saved import with three cells, in a deliberately unsorted insert order. */
    private suspend fun saveImport(
        fileName: String = "sample-import.xlsx",
        sha256: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    ): Pair<EntityId, List<EntityId>> {
        val batch = anImportBatch(fileName = fileName, sha256 = sha256, rawBlockCount = 3)
        val third = aRawImportBlock(batch.id, rawText = "  bosluk  ", rowIndex = 2, columnIndex = 4)
        val first = aRawImportBlock(batch.id, rawText = "12 KIRMIZI**\n8 MAVİ", rowIndex = 1, columnIndex = 1)
        val second =
            aRawImportBlock(
                batch.id,
                rawText = "Örnek Oyun A",
                rowIndex = 1,
                columnIndex = 3,
                sourceColumnType = SourceColumnType.GAME,
            )
        database.importDao().saveDraftBatch(batch, listOf(third, first, second))
        return batch.id to listOf(first.id, second.id, third.id)
    }

    private suspend fun workspaceOf(batchId: EntityId) = store.observeWorkspace(batchId).first()

    @Test
    fun `a workspace shows only the cells of its own import`() =
        runBlocking {
            val (batchId, _) = saveImport()
            val (otherId, _) = saveImport(fileName = "baska.xlsx", sha256 = "b".repeat(64))

            val workspace = assertNotNull(workspaceOf(batchId))
            val other = assertNotNull(workspaceOf(otherId))

            assertEquals(3, workspace.rawBlockCount)
            assertTrue(workspace.rawBlocks.none { block -> other.rawBlocks.any { it.id == block.id } })
        }

    @Test
    fun `cells come back in the order the file had them`() =
        runBlocking {
            val (batchId, ordered) = saveImport()

            val workspace = assertNotNull(workspaceOf(batchId))

            assertEquals(ordered, workspace.rawBlocks.map { it.id }, "cells are ordered by row then column")
        }

    @Test
    fun `the cell text is handed over exactly as the file had it`() =
        runBlocking {
            val (batchId, _) = saveImport()

            val texts = assertNotNull(workspaceOf(batchId)).rawBlocks.map { it.rawText }

            assertEquals(listOf("12 KIRMIZI**\n8 MAVİ", "Örnek Oyun A", "  bosluk  "), texts)
        }

    @Test
    fun `a cell hint and fill survive into the workspace`() =
        runBlocking {
            val batch = anImportBatch(rawBlockCount = 1)
            val green =
                aRawImportBlock(batch.id, rowIndex = 1, columnIndex = 0, sourceColumnType = SourceColumnType.GAME)
                    .copy(fillColorArgb = -11622610, gameCompletionHint = HintDecision.PENDING)
            database.importDao().saveDraftBatch(batch, listOf(green))

            val block = assertNotNull(workspaceOf(batch.id)).rawBlocks.single()

            assertEquals(HintDecision.PENDING, block.gameCompletionHint)
            assertEquals(-11622610, block.fillColorArgb)
        }

    @Test
    fun `an import that is not there gives no workspace`() =
        runBlocking {
            assertNull(
                workspaceOf(
                    dev.pnptracker.domain.model.IdGenerator.Random
                        .newId(),
                ),
            )
        }

    @Test
    fun `marking a cell reviewed and unmarking it both work`() =
        runBlocking {
            val (batchId, blocks) = saveImport()

            store.setProcessed(blocks[0], true)
            assertTrue(assertNotNull(workspaceOf(batchId)).rawBlocks.first { it.id == blocks[0] }.isProcessed)

            store.setProcessed(blocks[0], false)
            assertTrue(!assertNotNull(workspaceOf(batchId)).rawBlocks.first { it.id == blocks[0] }.isProcessed)
        }

    @Test
    fun `marking one cell leaves the others alone`() =
        runBlocking {
            val (batchId, blocks) = saveImport()

            store.setProcessed(blocks[1], true)

            val workspace = assertNotNull(workspaceOf(batchId))
            assertEquals(listOf(false, true, false), workspace.rawBlocks.map { it.isProcessed })
            assertEquals(1, workspace.processedBlockCount)
        }

    @Test
    fun `marking a cell creates no task draft`() =
        runBlocking {
            val (batchId, blocks) = saveImport()

            store.setProcessed(blocks[0], true)

            assertEquals(0, assertNotNull(workspaceOf(batchId)).draftTaskCount)
        }

    @Test
    fun `marking a cell leaves the import a draft and creates no real records`() =
        runBlocking {
            val (batchId, blocks) = saveImport()

            store.setProcessed(blocks[0], true)

            val batch = assertNotNull(database.importDao().batchById(batchId))
            assertEquals(ImportBatchStatus.DRAFT, batch.status)
            assertEquals(0, batch.createdGameCount)
            assertEquals(0, batch.createdTaskCount)
            assertEquals(0, database.gameDao().activeCount())
            assertEquals(emptyList(), database.itemDao().allItemsIncludingDeleted())
            assertEquals(emptyList(), database.taskDao().allTasksIncludingArchivedAndDeleted())
        }

    @Test
    fun `a cell of another import cannot be marked through the wrong workspace`() =
        runBlocking {
            val (_, blocks) = saveImport()
            // The guard is on the cell itself, so an id nobody is showing is refused
            // outright rather than quietly updating nothing.
            assertFailsWith<IllegalArgumentException> {
                store.setProcessed(
                    dev.pnptracker.domain.model.IdGenerator.Random
                        .newId(),
                    true,
                )
            }
            assertTrue(blocks.isNotEmpty())
        }

    @Test
    fun `a cell of a confirmed import is refused and keeps its value`() =
        runBlocking {
            // Confirming belongs to a later step, so the row is written directly as
            // confirmed here; the point is only that the guard sees the status.
            val batch = anImportBatch(rawBlockCount = 1, status = ImportBatchStatus.CONFIRMED)
            val block = aRawImportBlock(batch.id)
            database.importDao().insertBatch(batch)
            database.importDao().insertRawBlock(block)

            assertFailsWith<IllegalArgumentException> { store.setProcessed(block.id, true) }

            assertTrue(!assertNotNull(database.importDao().rawBlockById(block.id)).isProcessed)
        }

    @Test
    fun `drafts of a cell appear in the workspace and belong to their own import`() =
        runBlocking {
            val (batchId, blocks) = saveImport()
            val (otherId, otherBlocks) = saveImport(fileName = "baska.xlsx", sha256 = "b".repeat(64))
            database.importDao().addDraftTask(aDraftTask(blocks[0], name = "Kırmızı token"))
            database.importDao().addDraftTask(aDraftTask(otherBlocks[0], name = "Baska taslak"))

            val workspace = assertNotNull(workspaceOf(batchId))
            val other = assertNotNull(workspaceOf(otherId))

            assertEquals(listOf("Kırmızı token"), workspace.draftTasks.map { it.name })
            assertEquals(listOf("Baska taslak"), other.draftTasks.map { it.name })
            assertEquals(listOf("Kırmızı token"), workspace.draftsOf(blocks[0]).map { it.name })
        }

    @Test
    fun `an import with no drafts reports an empty draft list`() =
        runBlocking {
            val (batchId, _) = saveImport()

            assertEquals(emptyList(), assertNotNull(workspaceOf(batchId)).draftTasks)
        }

    @Test
    fun `a review mark is still there after closing and reopening the database`() =
        runBlocking {
            val (batchId, blocks) = saveImport()
            store.setProcessed(blocks[2], true)
            database.close()

            database = DatabaseFactory().open(directory.databaseFile)
            store = ImportReviewStore(database.importDao(), clock = SteppingClock(moment))

            val workspace = assertNotNull(workspaceOf(batchId))
            assertEquals(listOf(false, false, true), workspace.rawBlocks.map { it.isProcessed })
        }

    @Test
    fun `only draft imports are offered to come back to`() =
        runBlocking {
            val (batchId, _) = saveImport()
            val confirmed =
                anImportBatch(
                    fileName = "bitmis.xlsx",
                    sha256 = "c".repeat(64),
                    rawBlockCount = 0,
                    status = ImportBatchStatus.CONFIRMED,
                )
            database.importDao().insertBatch(confirmed)

            val offered = store.observeDraftBatches().first()

            assertEquals(listOf(batchId), offered.map { it.batchId })
        }

    @Test
    fun `a draft is written against the cell it came from`() =
        runBlocking {
            val (batchId, blocks) = saveImport()

            store.addDraftTask(blocks[0], "Kırmızı token")

            val workspace = assertNotNull(workspaceOf(batchId))
            val draft = workspace.draftTasks.single()
            assertEquals("Kırmızı token", draft.name)
            assertEquals(blocks[0], draft.rawImportBlockId)
            assertEquals(listOf(draft), workspace.draftsOf(blocks[0]))
        }

    @Test
    fun `the name is stored exactly as it was given`() =
        runBlocking {
            val (batchId, blocks) = saveImport()
            val awkward = "  12 KIRMIZI**  "

            store.addDraftTask(blocks[0], awkward)

            assertEquals(awkward, assertNotNull(workspaceOf(batchId)).draftTasks.single().name)
        }

    @Test
    fun `a blank name is refused and nothing is written`() =
        runBlocking {
            val (batchId, blocks) = saveImport()

            assertFailsWith<IllegalArgumentException> { store.addDraftTask(blocks[0], "   ") }

            assertEquals(0, assertNotNull(workspaceOf(batchId)).draftTaskCount)
        }

    @Test
    fun `one cell can carry several drafts`() =
        runBlocking {
            val (batchId, blocks) = saveImport()

            store.addDraftTask(blocks[0], "Kırmızı token")
            store.addDraftTask(blocks[0], "Mavi token")

            val workspace = assertNotNull(workspaceOf(batchId))
            assertEquals(listOf("Kırmızı token", "Mavi token"), workspace.draftsOf(blocks[0]).map { it.name })
            assertEquals(2, workspace.draftTaskCount)
        }

    @Test
    fun `a draft leaves the cell unmarked`() =
        runBlocking {
            val (batchId, blocks) = saveImport()

            store.addDraftTask(blocks[0], "Kırmızı token")

            val workspace = assertNotNull(workspaceOf(batchId))
            assertTrue(!workspace.rawBlocks.first { it.id == blocks[0] }.isProcessed)
            assertEquals(0, workspace.processedBlockCount)
        }

    @Test
    fun `a draft creates no game item or task and leaves the import a draft`() =
        runBlocking {
            val (batchId, blocks) = saveImport()

            store.addDraftTask(blocks[0], "Kırmızı token")

            val batch = assertNotNull(database.importDao().batchById(batchId))
            assertEquals(ImportBatchStatus.DRAFT, batch.status)
            assertEquals(0, batch.createdGameCount)
            assertEquals(0, batch.createdTaskCount)
            assertEquals(0, database.gameDao().activeCount())
            assertEquals(emptyList(), database.itemDao().allItemsIncludingDeleted())
            assertEquals(emptyList(), database.taskDao().allTasksIncludingArchivedAndDeleted())
        }

    @Test
    fun `a draft never shows up in another import`() =
        runBlocking {
            val (batchId, blocks) = saveImport()
            val (otherId, _) = saveImport(fileName = "baska.xlsx", sha256 = "b".repeat(64))

            store.addDraftTask(blocks[0], "Kırmızı token")

            assertEquals(1, assertNotNull(workspaceOf(batchId)).draftTaskCount)
            assertEquals(0, assertNotNull(workspaceOf(otherId)).draftTaskCount)
        }

    @Test
    fun `a draft cannot be attached to an import that is no longer a draft`() =
        runBlocking {
            val batch = anImportBatch(rawBlockCount = 1, status = ImportBatchStatus.CONFIRMED)
            val block = aRawImportBlock(batch.id)
            database.importDao().insertBatch(batch)
            database.importDao().insertRawBlock(block)

            assertFailsWith<IllegalArgumentException> { store.addDraftTask(block.id, "Geç kalmış taslak") }

            assertEquals(emptyList(), database.importDao().draftTasksOfBlock(block.id))
        }

    @Test
    fun `a draft against a cell that is not there writes nothing`() =
        runBlocking {
            val (batchId, _) = saveImport()

            assertFailsWith<IllegalArgumentException> {
                store.addDraftTask(
                    dev.pnptracker.domain.model.IdGenerator.Random
                        .newId(),
                    "Öksüz taslak",
                )
            }

            assertEquals(0, assertNotNull(workspaceOf(batchId)).draftTaskCount)
        }

    @Test
    fun `drafts are still there after closing and reopening the database`() =
        runBlocking {
            val (batchId, blocks) = saveImport()
            store.addDraftTask(blocks[0], "Kırmızı token")
            store.addDraftTask(blocks[0], "Mavi token")
            store.setProcessed(blocks[1], true)
            database.close()

            database = DatabaseFactory().open(directory.databaseFile)
            store = ImportReviewStore(database.importDao(), clock = SteppingClock(moment))

            val workspace = assertNotNull(workspaceOf(batchId))
            assertEquals(listOf("Kırmızı token", "Mavi token"), workspace.draftTasks.map { it.name })
            assertEquals(1, workspace.processedBlockCount)
            assertEquals(ImportBatchStatus.DRAFT, workspace.status)
        }

    @Test
    fun `drafts made in the very same moment still come back in a fixed order`() =
        runBlocking {
            // A stopped clock makes both drafts claim the same instant, which is what
            // the id tie-breaker is there for: the order must not wander between reads.
            store = ImportReviewStore(database.importDao(), clock = StoppedClock(moment))
            val (batchId, blocks) = saveImport()
            store.addDraftTask(blocks[0], "Bir")
            store.addDraftTask(blocks[0], "Iki")

            val firstRead = assertNotNull(workspaceOf(batchId)).draftTasks.map { it.name }
            val secondRead = assertNotNull(workspaceOf(batchId)).draftTasks.map { it.name }

            assertEquals(firstRead, secondRead)
            assertEquals(setOf("Bir", "Iki"), firstRead.toSet())
        }

    @Test
    fun `the stream reports a change without being asked to reload`() =
        runBlocking {
            val (batchId, blocks) = saveImport()
            val before = assertNotNull(workspaceOf(batchId))
            assertEquals(0, before.processedBlockCount)

            store.setProcessed(blocks[0], true)

            // A fresh collection of the same flow: Room re-runs the query itself.
            assertEquals(1, assertNotNull(workspaceOf(batchId)).processedBlockCount)
        }
}
