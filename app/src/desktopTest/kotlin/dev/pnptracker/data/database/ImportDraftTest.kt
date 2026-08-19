package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How an import is stored before anything of it reaches the production tables. */
class ImportDraftTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun insertBatchWithBlock(
        rawText: String = "15 KIRMIZI** 19 YEŞİL**",
        status: ImportBatchStatus = ImportBatchStatus.DRAFT,
    ): RawImportBlockEntity {
        val batch = anImportBatch(status = status)
        val block = aRawImportBlock(importBatchId = batch.id, rawText = rawText)
        database.importDao().insertBatch(batch)
        database.importDao().insertRawBlock(block)
        return block
    }

    @Test
    fun `a fresh database has the three import tables and they start empty`() =
        runBlocking<Unit> {
            val tables =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
                        buildList { while (statement.step()) add(statement.getText(0)) }
                    }
                }

            listOf("import_batches", "raw_import_blocks", "draft_tasks").forEach { assertContains(tables, it) }
            assertEquals(emptyList(), database.importDao().allBatches())
            assertEquals(emptyList(), database.importDao().draftBatches())
        }

    @Test
    fun `the same file can be imported twice and the fingerprint finds both`() =
        runBlocking<Unit> {
            val first = anImportBatch(sha256 = SHA_256_ONE)
            val second = anImportBatch(sha256 = SHA_256_ONE)
            val other = anImportBatch(sha256 = SHA_256_TWO)
            database.importDao().insertBatch(first)
            database.importDao().insertBatch(second)
            database.importDao().insertBatch(other)

            val repeats = database.importDao().batchesWithFingerprint(SHA_256_ONE)

            assertEquals(setOf(first.id, second.id), repeats.map { it.id }.toSet())
            assertEquals(listOf(other.id), database.importDao().batchesWithFingerprint(SHA_256_TWO).map { it.id })
            assertEquals(
                emptyList(),
                database.importDao().batchesWithFingerprint("a".repeat(64)),
                "a file that was never imported has no earlier batches",
            )
        }

    @Test
    fun `a fingerprint that is not canonical lower case hex is rejected`() =
        runBlocking<Unit> {
            listOf(
                "",
                "abc",
                SHA_256_ONE.uppercase(),
                SHA_256_ONE.dropLast(1),
                SHA_256_ONE + "0",
                SHA_256_ONE.replaceRange(0, 1, "g"),
            ).forEach { invalid ->
                assertFailsWith<IllegalArgumentException>("should have been rejected: '$invalid'") {
                    anImportBatch(sha256 = invalid)
                }
            }
        }

    @Test
    fun `negative counts and broken ranges are rejected`() =
        runBlocking<Unit> {
            assertFailsWith<IllegalArgumentException> { anImportBatch(createdGameCount = -1) }
            assertFailsWith<IllegalArgumentException> { anImportBatch(rawBlockCount = -1) }
            assertFailsWith<IllegalArgumentException> { anImportBatch(createdTaskCount = -1) }
            assertFailsWith<IllegalArgumentException> { anImportBatch(startRowIndex = 5, endRowIndex = 2) }
            assertFailsWith<IllegalArgumentException> { anImportBatch(startColumnIndex = 6, endColumnIndex = 1) }
            assertFailsWith<IllegalArgumentException> { anImportBatch(startRowIndex = -1) }
            assertFailsWith<IllegalArgumentException> { anImportBatch(endColumnIndex = null) }

            val unknownRange =
                anImportBatch(
                    startRowIndex = null,
                    endRowIndex = null,
                    startColumnIndex = null,
                    endColumnIndex = null,
                )
            database.importDao().insertBatch(unknownRange)
            assertNull(assertNotNull(database.importDao().batchById(unknownRange.id)).startRowIndex)
        }

    @Test
    fun `raw text comes back byte for byte`() =
        runBlocking<Unit> {
            val awkward = "  15 KIRMIZI**\n19 YEŞİL**\t\n   66 ADET TURUNCU KÜÇÜK KÜP  "
            val block = insertBatchWithBlock(rawText = awkward)

            val stored = assertNotNull(database.importDao().rawBlockById(block.id))

            assertEquals(awkward, stored.rawText)
            assertTrue(stored.rawText.startsWith("  "), "leading spaces were trimmed")
            assertTrue(stored.rawText.endsWith("  "), "trailing spaces were trimmed")
            assertEquals(2, stored.rawText.count { it == '\n' })
            assertEquals(2, Regex("\\*\\*").findAll(stored.rawText).count(), "the ** markers were stripped")
            assertContains(stored.rawText, "YEŞİL")
        }

    @Test
    fun `the same cell cannot be recorded twice in one batch but may repeat across batches`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock()
            val duplicate = aRawImportBlock(importBatchId = block.importBatchId, rowIndex = 1, columnIndex = 1)

            val failure = assertFailsWith<SQLiteException> { database.importDao().insertRawBlock(duplicate) }
            assertContains(failure.message.orEmpty().uppercase(), "UNIQUE")

            val otherBatch = anImportBatch(sha256 = SHA_256_TWO)
            database.importDao().insertBatch(otherBatch)
            database.importDao().insertRawBlock(
                aRawImportBlock(importBatchId = otherBatch.id, rowIndex = 1, columnIndex = 1),
            )

            assertEquals(1, database.importDao().rawBlocksOfBatch(block.importBatchId).size)
            assertEquals(1, database.importDao().rawBlocksOfBatch(otherBatch.id).size)
        }

    @Test
    fun `a raw cell has only the narrow updates a user really decides`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock()

            // The only ways to touch a raw cell are inserting it, reading it, marking it
            // processed and answering its hint; there is no general text or coordinate
            // update. Reads may be added freely — `observeRawBlocksOfBatch` is one — but
            // every name here that writes must be checked against that rule by hand.
            val rawBlockApi =
                ImportDao::class.java.methods
                    // Value class parameters make Kotlin mangle the JVM name after a dash.
                    .map { it.name.substringBefore('-') }
                    .filter { it.contains("RawBlock", ignoreCase = true) }
                    .toSet()

            assertEquals(
                setOf(
                    "insertRawBlock",
                    "rawBlockById",
                    "rawBlocksOfBatch",
                    "observeRawBlocksOfBatch",
                    "unprocessedRawBlocksOfBatch",
                    // Writes: both only ever set `is_processed`, and the guarded one
                    // reaches the table through the other.
                    "markRawBlockProcessed",
                    "setRawBlockProcessed",
                ),
                rawBlockApi,
            )
            assertEquals(1, database.importDao().markRawBlockProcessed(block.id, true, updatedAt))
            val processed = assertNotNull(database.importDao().rawBlockById(block.id))
            assertTrue(processed.isProcessed)
            assertEquals(block.rawText, processed.rawText, "marking a cell processed must not touch its text")
            assertEquals(block.rowIndex, processed.rowIndex)
            assertEquals(block.sourceColumnType, processed.sourceColumnType)
            assertEquals(emptyList(), database.importDao().unprocessedRawBlocksOfBatch(block.importBatchId))
        }

    @Test
    fun `one raw cell can carry several drafts`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock()

            database.importDao().addDraftTask(aDraftTask(block.id, name = "Kırmızı token"))
            database.importDao().addDraftTask(aDraftTask(block.id, name = "Yeşil token"))
            database.importDao().addDraftTask(aDraftTask(block.id, name = "Sarı token"))

            assertEquals(
                setOf("Kırmızı token", "Yeşil token", "Sarı token"),
                database
                    .importDao()
                    .draftTasksOfBlock(block.id)
                    .map { it.name }
                    .toSet(),
            )
        }

    @Test
    fun `a draft quantity is either unknown or positive and the name cannot be blank`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock()
            val unknown = aDraftTask(block.id)
            database.importDao().addDraftTask(unknown)

            assertNull(assertNotNull(database.importDao().draftTaskById(unknown.id)).requiredQuantity)
            assertFailsWith<IllegalArgumentException> { aDraftTask(block.id).copy(requiredQuantity = 0) }
            assertFailsWith<IllegalArgumentException> { aDraftTask(block.id).copy(requiredQuantity = -2) }
            assertFailsWith<IllegalArgumentException> { aDraftTask(block.id, name = "   ") }
        }

    @Test
    fun `a selection that runs past the end of the cell text is refused and writes nothing`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock(rawText = "15 KIRMIZI")
            val within = aDraftTask(block.id, name = "Kırmızı", selectionStartIndex = 3, selectionEndIndex = 10)
            database.importDao().addDraftTask(within)
            val beyond = aDraftTask(block.id, name = "Taşan", selectionStartIndex = 3, selectionEndIndex = 99)

            val failure = assertFailsWith<IllegalArgumentException> { database.importDao().addDraftTask(beyond) }

            assertContains(failure.message.orEmpty(), "runs past the end")
            assertEquals(listOf(within.id), database.importDao().draftTasksOfBlock(block.id).map { it.id })
            assertFailsWith<IllegalArgumentException> { aDraftTask(block.id, selectionStartIndex = 3) }
            assertFailsWith<IllegalArgumentException> {
                aDraftTask(block.id, selectionStartIndex = 5, selectionEndIndex = 5)
            }
            assertFailsWith<IllegalArgumentException> {
                aDraftTask(block.id, selectionStartIndex = -1, selectionEndIndex = 4)
            }
        }

    @Test
    fun `no hint is accepted until the user says so`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock()
            val draft = aDraftTask(block.id)
            database.importDao().addDraftTask(draft)

            assertEquals(HintDecision.NONE, assertNotNull(database.importDao().rawBlockById(block.id)).gameCompletionHint)
            assertEquals(HintDecision.NONE, assertNotNull(database.importDao().draftTaskById(draft.id)).completionHint)

            database.importDao().updateGameCompletionHint(block.id, HintDecision.PENDING, updatedAt)
            database.importDao().updateDraftCompletionHint(draft.id, HintDecision.PENDING, updatedAt)

            assertEquals(
                HintDecision.PENDING,
                assertNotNull(database.importDao().rawBlockById(block.id)).gameCompletionHint,
            )
            assertEquals(
                HintDecision.PENDING,
                assertNotNull(database.importDao().draftTaskById(draft.id)).completionHint,
            )
            // A pending hint changes nothing about the game itself.
            assertEquals(emptyList(), database.gameDao().allGamesIncludingDeleted())
        }

    @Test
    fun `missing and borrowed stay flags and never become a pool`() =
        runBlocking<Unit> {
            val batch = anImportBatch()
            database.importDao().insertBatch(batch)
            val missingCell =
                aRawImportBlock(
                    importBatchId = batch.id,
                    rowIndex = 2,
                    columnIndex = 5,
                    sourceColumnType = SourceColumnType.MISSING,
                    rawText = "2 eksik kart",
                )
            val borrowedCell =
                aRawImportBlock(
                    importBatchId = batch.id,
                    rowIndex = 2,
                    columnIndex = 6,
                    sourceColumnType = SourceColumnType.BORROWED,
                    rawText = "1 ödünç zar",
                )
            database.importDao().insertRawBlock(missingCell)
            database.importDao().insertRawBlock(borrowedCell)
            val missingDraft =
                aDraftTask(missingCell.id, name = "Eksik kart").copy(isMissing = true, needsClassification = true)
            val borrowedDraft = aDraftTask(borrowedCell.id, name = "Ödünç zar").copy(isBorrowed = true)
            database.importDao().addDraftTask(missingDraft)
            database.importDao().addDraftTask(borrowedDraft)

            val storedMissing = assertNotNull(database.importDao().draftTaskById(missingDraft.id))
            val storedBorrowed = assertNotNull(database.importDao().draftTaskById(borrowedDraft.id))

            assertTrue(storedMissing.isMissing)
            assertTrue(storedMissing.needsClassification)
            assertNull(storedMissing.suggestedPoolType, "a missing cell must not be given a pool")
            assertNull(storedMissing.selectedPoolType)
            assertTrue(storedBorrowed.isBorrowed)
            assertNull(storedBorrowed.suggestedPoolType, "a borrowed cell must not be given a pool")
            assertFalse(PoolType.entries.map { it.name }.contains("MISSING"))
            assertFalse(PoolType.entries.map { it.name }.contains("BORROWED"))
        }

    @Test
    fun `a draft keeps a suggestion apart from the user's choice and honours the tracking rules`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock()
            val suggested = aDraftTask(block.id).copy(suggestedPoolType = PoolType.THREE_D)
            database.importDao().addDraftTask(suggested)

            val stored = assertNotNull(database.importDao().draftTaskById(suggested.id))
            assertEquals(PoolType.THREE_D, stored.suggestedPoolType)
            assertNull(stored.selectedPoolType, "a suggestion is not a decision")

            assertFailsWith<IllegalArgumentException> {
                aDraftTask(block.id).copy(
                    selectedPoolType = PoolType.CARD,
                    selectedTrackingMode = TrackingMode.THREE_D_BATCH,
                )
            }
            val decided =
                aDraftTask(block.id, name = "Bird Cards").copy(
                    selectedPoolType = PoolType.CARD,
                    selectedTrackingMode = TrackingMode.PIPELINE,
                )
            database.importDao().addDraftTask(decided)
            assertEquals(
                TrackingMode.PIPELINE,
                assertNotNull(database.importDao().draftTaskById(decided.id)).selectedTrackingMode,
            )
        }

    @Test
    fun `discarding a draft batch removes only its own cells and drafts`() =
        runBlocking<Unit> {
            val doomed = insertBatchWithBlock()
            database.importDao().addDraftTask(aDraftTask(doomed.id))
            val keptBatch = anImportBatch(sha256 = SHA_256_TWO)
            database.importDao().insertBatch(keptBatch)
            val keptBlock = aRawImportBlock(importBatchId = keptBatch.id)
            database.importDao().insertRawBlock(keptBlock)
            val keptDraft = aDraftTask(keptBlock.id)
            database.importDao().addDraftTask(keptDraft)
            val game = aGame()
            database.gameDao().insert(game)

            database.importDao().discardDraftBatch(doomed.importBatchId)

            assertNull(database.importDao().batchById(doomed.importBatchId))
            assertNull(database.importDao().rawBlockById(doomed.id))
            assertEquals(emptyList(), database.importDao().draftTasksOfBlock(doomed.id))
            assertNotNull(database.importDao().batchById(keptBatch.id))
            assertNotNull(database.importDao().rawBlockById(keptBlock.id))
            assertEquals(listOf(keptDraft.id), database.importDao().draftTasksOfBlock(keptBlock.id).map { it.id })
            assertEquals(listOf(game.id), database.gameDao().activeGames().map { it.id })
        }

    @Test
    fun `a confirmed or rolled back import cannot be discarded`() =
        runBlocking<Unit> {
            val confirmed = insertBatchWithBlock(status = ImportBatchStatus.CONFIRMED)
            val rolledBack = insertBatchWithBlock(status = ImportBatchStatus.ROLLED_BACK).importBatchId

            val confirmedFailure =
                assertFailsWith<IllegalArgumentException> {
                    database.importDao().discardDraftBatch(confirmed.importBatchId)
                }
            val rolledBackFailure =
                assertFailsWith<IllegalArgumentException> { database.importDao().discardDraftBatch(rolledBack) }

            assertContains(confirmedFailure.message.orEmpty(), "CONFIRMED")
            assertContains(rolledBackFailure.message.orEmpty(), "ROLLED_BACK")
            assertNotNull(database.importDao().batchById(confirmed.importBatchId))
            assertNotNull(database.importDao().rawBlockById(confirmed.id))
            assertFailsWith<IllegalArgumentException> {
                database.importDao().discardDraftBatch(IdGenerator.Random.newId())
            }
        }

    @Test
    fun `a batch or cell that a game or task points at cannot be removed`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock()
            val game = aGame().copy(sourceImportBatchId = block.importBatchId)
            database.gameDao().insert(game)
            val task = insertGameItemAndTask(database, gameName = "Wingspan")
            database.useWriterConnection { transactor ->
                transactor.usePrepared("UPDATE tasks SET source_raw_import_block_id = ? WHERE id = ?") { statement ->
                    statement.bindText(1, block.id.toString())
                    statement.bindText(2, task.id.toString())
                    statement.step()
                }
            }

            val batchFailure =
                assertFailsWith<SQLiteException> { database.importDao().discardDraftBatch(block.importBatchId) }

            assertContains(batchFailure.message.orEmpty().uppercase(), "FOREIGN KEY")
            assertNotNull(database.importDao().batchById(block.importBatchId))
            assertNotNull(database.importDao().rawBlockById(block.id))
            assertEquals(
                block.importBatchId,
                assertNotNull(database.gameDao().activeGameById(game.id)).sourceImportBatchId,
            )
        }

    @Test
    fun `one task cannot be claimed by two drafts`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock()
            val task = insertGameItemAndTask(database)
            val first = aDraftTask(block.id, name = "Ilk").copy(materializedTaskId = task.id)
            val second = aDraftTask(block.id, name = "Ikinci").copy(materializedTaskId = task.id)
            database.importDao().addDraftTask(first)

            val failure = assertFailsWith<SQLiteException> { database.importDao().addDraftTask(second) }

            assertContains(failure.message.orEmpty().uppercase(), "UNIQUE")
            assertEquals(listOf(first.id), database.importDao().draftTasksOfBlock(block.id).map { it.id })
        }

    @Test
    fun `import enums are stored as readable text`() =
        runBlocking<Unit> {
            val batch = anImportBatch(sourceFormat = ImportSourceFormat.CSV, sheetName = "")
            database.importDao().insertBatch(batch)
            val block =
                aRawImportBlock(
                    importBatchId = batch.id,
                    sheetName = "",
                    sourceColumnType = SourceColumnType.BOARD,
                )
            database.importDao().insertRawBlock(block)
            database.importDao().updateGameCompletionHint(block.id, HintDecision.PENDING, updatedAt)

            val stored =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared(
                        "SELECT typeof(b.status), b.status, typeof(b.source_format), b.source_format, " +
                            "typeof(r.source_column_type), r.source_column_type, " +
                            "typeof(r.game_completion_hint), r.game_completion_hint " +
                            "FROM import_batches b JOIN raw_import_blocks r ON r.import_batch_id = b.id",
                    ) { statement ->
                        statement.step()
                        (0..7).map { statement.getText(it) }
                    }
                }

            assertEquals(
                listOf("text", "DRAFT", "text", "CSV", "text", "BOARD", "text", "PENDING"),
                stored.mapIndexed { index, value -> if (index % 2 == 0) value.lowercase() else value },
            )
        }

    @Test
    fun `a draft can point at an existing item and refuses an unknown one`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock()
            val task = insertGameItemAndTask(database)
            val linked = aDraftTask(block.id).copy(targetItemId = task.itemId)

            database.importDao().addDraftTask(linked)

            assertEquals(task.itemId, assertNotNull(database.importDao().draftTaskById(linked.id)).targetItemId)
            val orphan = aDraftTask(block.id, name = "Bilinmeyen").copy(targetItemId = IdGenerator.Random.newId())
            val failure = assertFailsWith<SQLiteException> { database.importDao().addDraftTask(orphan) }
            assertContains(failure.message.orEmpty().uppercase(), "FOREIGN KEY")
        }

    @Test
    fun `editing a draft keeps the selection check`() =
        runBlocking<Unit> {
            val block = insertBatchWithBlock(rawText = "15 KIRMIZI")
            val draft = aDraftTask(block.id, selectionStartIndex = 0, selectionEndIndex = 2)
            database.importDao().addDraftTask(draft)

            database.importDao().editDraftTask(draft.copy(name = "Yeni ad", selectionEndIndex = 10))
            assertEquals("Yeni ad", assertNotNull(database.importDao().draftTaskById(draft.id)).name)

            assertFailsWith<IllegalArgumentException> {
                database.importDao().editDraftTask(draft.copy(selectionEndIndex = 40))
            }
            assertEquals(10, assertNotNull(database.importDao().draftTaskById(draft.id)).selectionEndIndex)
        }

    @Test
    fun `a game or task created by hand has no import source`() =
        runBlocking<Unit> {
            val task = insertGameItemAndTask(database)

            val storedTask = assertNotNull(database.taskDao().activeTaskById(task.id))
            val storedGame = database.gameDao().activeGames().single()

            assertNull(storedTask.sourceRawImportBlockId)
            assertNull(storedGame.sourceImportBatchId)
        }
}
