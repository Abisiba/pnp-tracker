package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.importprep.ImportPreparationException
import dev.pnptracker.domain.importprep.PreparedImportDraft
import dev.pnptracker.domain.importprep.PreparedRawBlock
import dev.pnptracker.domain.importprep.unpackArgb
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.SheetVisibility
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

/** A clock that never moves, so every row of one import can be compared exactly. */
private class FixedClock(
    private val fixed: Instant,
) : Clock {
    var readCount = 0
        private set

    override fun now(): Instant {
        readCount++
        return fixed
    }
}

class ImportDraftStoreTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ImportDraftStore
    private lateinit var clock: FixedClock
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_760_000_000_000)

    @BeforeTest
    fun openTemporaryDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        clock = FixedClock(moment)
        store = ImportDraftStore(database.importDao(), IdGenerator.Random, clock)
    }

    @AfterTest
    fun closeAndRemove() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private fun draft(
        sha256: String = SHA_A,
        fileName: String = "ornek.xlsx",
        sheetName: String = "Sayfa1",
        blocks: List<PreparedRawBlock> = defaultBlocks(),
        sourceFormat: ImportSourceFormat = ImportSourceFormat.XLSX,
    ) = PreparedImportDraft(
        fileName = fileName,
        sha256 = sha256,
        sourceFormat = sourceFormat,
        sheetName = sheetName,
        sheetVisibility = SheetVisibility.VISIBLE,
        startRowIndex = blocks.minOf { it.rowIndex },
        endRowIndex = blocks.maxOf { it.rowIndex },
        startColumnIndex = blocks.minOf { it.columnIndex },
        endColumnIndex = blocks.maxOf { it.columnIndex },
        blocks = blocks,
    )

    private fun defaultBlocks() =
        listOf(
            PreparedRawBlock(1, 0, SourceColumnType.GAME, "Örnek Oyun A", -11622610, HintDecision.PENDING),
            PreparedRawBlock(1, 1, SourceColumnType.THREE_D, "12 KIRMIZI**\n8 MAVİ", null, HintDecision.NONE),
            PreparedRawBlock(2, 0, SourceColumnType.GAME, "Örnek Oyun B", null, HintDecision.NONE),
        )

    @Test
    fun `a saved import is a complete draft batch`() =
        runBlocking<Unit> {
            val summary = store.save(draft())

            val batch = assertNotNull(database.importDao().batchById(summary.batchId))
            assertEquals(ImportBatchStatus.DRAFT, batch.status)
            assertEquals("ornek.xlsx", batch.fileName)
            assertEquals(SHA_A, batch.sha256)
            assertEquals("Sayfa1", batch.sheetName)
            assertEquals(1, batch.startRowIndex)
            assertEquals(2, batch.endRowIndex)
            assertEquals(0, batch.startColumnIndex)
            assertEquals(1, batch.endColumnIndex)
        }

    @Test
    fun `reading a file creates no games and no tasks`() =
        runBlocking<Unit> {
            val summary = store.save(draft())

            val batch = assertNotNull(database.importDao().batchById(summary.batchId))
            assertEquals(0, batch.createdGameCount, "an import records what a file said, it does not create games")
            assertEquals(0, batch.createdTaskCount)
            assertEquals(3, batch.rawBlockCount)
            assertEquals(3, summary.rawBlockCount)
        }

    @Test
    fun `every prepared cell becomes a raw block, unchanged`() =
        runBlocking<Unit> {
            val summary = store.save(draft())

            val blocks = database.importDao().rawBlocksOfBatch(summary.batchId)
            assertEquals(3, blocks.size)
            assertEquals(
                listOf("Örnek Oyun A", "12 KIRMIZI**\n8 MAVİ", "Örnek Oyun B"),
                blocks.map { it.rawText },
            )
            assertEquals("FF4EA72E", unpackArgb(blocks[0].fillColorArgb))
            assertEquals(HintDecision.PENDING, blocks[0].gameCompletionHint)
            assertEquals(HintDecision.NONE, blocks[1].gameCompletionHint)
            assertTrue(blocks.none { it.isProcessed }, "nothing has been worked through yet")
            assertTrue(blocks.all { it.sheetName == "Sayfa1" })
        }

    @Test
    fun `every row of one import shares a single reading of the clock`() =
        runBlocking<Unit> {
            val summary = store.save(draft())

            val batch = assertNotNull(database.importDao().batchById(summary.batchId))
            val blocks = database.importDao().rawBlocksOfBatch(summary.batchId)

            assertEquals(1, clock.readCount, "the clock should be read once per import")
            assertEquals(moment, batch.importedAt)
            assertEquals(moment, batch.updatedAt)
            blocks.forEach { block ->
                assertEquals(moment, block.createdAt)
                assertEquals(block.createdAt, block.updatedAt)
            }
        }

    @Test
    fun `identifiers are unique across the batch and its cells`() =
        runBlocking<Unit> {
            val summary = store.save(draft())

            val blocks = database.importDao().rawBlocksOfBatch(summary.batchId)
            val ids: List<EntityId> = blocks.map { it.id } + summary.batchId

            assertEquals(ids.size, ids.toSet().size, "identifiers must not repeat")
            assertTrue(blocks.all { it.importBatchId == summary.batchId })
        }

    @Test
    fun `a cell that cannot be written takes the whole import back with it`() =
        runBlocking<Unit> {
            // Two cells claiming the same coordinates: the unique index refuses
            // the second one, and the batch must not survive on its own.
            val clashing =
                listOf(
                    PreparedRawBlock(1, 0, SourceColumnType.GAME, "Örnek Oyun A", null, HintDecision.NONE),
                    PreparedRawBlock(1, 0, SourceColumnType.GAME, "Aynı yer", null, HintDecision.NONE),
                )

            // Deliberately turned in Dilim 3. This used to catch the driver's own
            // `SQLiteException` and read "UNIQUE" out of its message — which is
            // precisely the escape that slice closed: the exception travelled out
            // of the controller's coroutine and the screen sat on "kaydediliyor".
            // The transaction half of the claim is unchanged and is what this
            // test is really for.
            val refused = assertFailsWith<ImportPreparationException> { store.save(draft(blocks = clashing)) }

            assertEquals(ImportFailure.COULD_NOT_SAVE, refused.failure)
            // Stronger than the old claim rather than weaker: the typed answer
            // carries no SQL, no table and no coordinates at all (PLAN 14.7.1).
            val words = refused.message.orEmpty()
            listOf("UNIQUE", "INSERT", "raw_import_blocks", "import_batches", "Aynı yer").forEach {
                assertFalse(it in words, "`$it` reached the typed answer: $words")
            }
            assertEquals(emptyList(), database.importDao().allBatches(), "no half written batch may be left")
        }

    @Test
    fun `nothing outside the import tables is touched`() =
        runBlocking<Unit> {
            store.save(draft())

            assertEquals(0, database.gameDao().activeCount())
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
            assertEquals(12, database.colorDao().allColors().size, "the seed colours are untouched")
        }

    @Test
    fun `a draft survives closing and reopening the database`() =
        runBlocking<Unit> {
            val summary = store.save(draft())
            database.close()

            val reopened = DatabaseFactory().open(directory.databaseFile)
            try {
                val batch = assertNotNull(reopened.importDao().batchById(summary.batchId))
                val blocks = reopened.importDao().rawBlocksOfBatch(summary.batchId)

                assertEquals(ImportBatchStatus.DRAFT, batch.status)
                assertEquals("12 KIRMIZI**\n8 MAVİ", blocks[1].rawText, "the raw text must come back exactly")
                assertEquals(HintDecision.PENDING, blocks[0].gameCompletionHint)
            } finally {
                reopened.close()
                database = DatabaseFactory().open(directory.databaseFile)
            }
        }

    @Test
    fun `a file that has not been imported before has no earlier imports`() =
        runBlocking<Unit> {
            assertEquals(emptyList(), store.earlierImportsOf(SHA_A))
        }

    @Test
    fun `an earlier import of the same file is found by its fingerprint`() =
        runBlocking<Unit> {
            store.save(draft(sha256 = SHA_A, fileName = "birinci.xlsx"))

            val earlier = store.earlierImportsOf(SHA_A)

            assertEquals(1, earlier.size)
            assertEquals("birinci.xlsx", earlier.single().fileName)
            assertEquals(ImportBatchStatus.DRAFT, earlier.single().status)
        }

    @Test
    fun `the same file under another name is still the same file`() =
        runBlocking<Unit> {
            store.save(draft(sha256 = SHA_A, fileName = "birinci.xlsx"))

            assertEquals(1, store.earlierImportsOf(SHA_A).size)
        }

    @Test
    fun `a different file with the same name is not an earlier import`() =
        runBlocking<Unit> {
            store.save(draft(sha256 = SHA_A, fileName = "kitap.xlsx"))

            assertEquals(emptyList(), store.earlierImportsOf(SHA_B), "sameness is about content, not about names")
        }

    @Test
    fun `the same file can be imported twice on purpose, as two separate batches`() =
        runBlocking<Unit> {
            val first = store.save(draft(sha256 = SHA_A))
            val second = store.save(draft(sha256 = SHA_A, sheetName = "Sayfa2"))

            assertTrue(first.batchId != second.batchId, "each import is its own batch")
            assertEquals(2, store.earlierImportsOf(SHA_A).size)
            assertEquals(2, database.importDao().allBatches().size)
        }

    @Test
    fun `being a repeat is worked out from the table, not written into it`() =
        runBlocking<Unit> {
            store.save(draft(sha256 = SHA_A))
            store.save(draft(sha256 = SHA_A, sheetName = "Sayfa2"))

            val fields =
                dev.pnptracker.data.database.entity.ImportBatchEntity::class
                    .java
                    .declaredFields
                    .map { it.name.lowercase() }

            assertTrue(
                fields.none { it.contains("duplicate") },
                "a stored duplicate flag would be wrong as soon as the next import ran",
            )
            assertEquals(2, store.earlierImportsOf(SHA_A).size)
        }
}
