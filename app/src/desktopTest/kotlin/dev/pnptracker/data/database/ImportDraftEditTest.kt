package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
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
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Making a draft out of a cell, and then saying what it is.
 *
 * Two rules run through all of it. The whole form is one answer, so it is
 * written together or not at all and a refusal leaves the draft exactly as the
 * user left it, colours included. And a decision is a decision: PLAN 11.4.3 has
 * a review closed and reopened, so everything answered here has to be found
 * again afterwards — these tests reopen the database to prove it.
 */
class ImportDraftEditTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

    /** A clock that says how often it was read, for proving a no-op reads none. */
    private class CountingClock(
        private val fixed: Instant,
    ) : Clock {
        var reads: Int = 0
            private set

        override fun now(): Instant {
            reads++
            return fixed
        }
    }

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

    private val importDao get() = database.importDao()

    private class Fixture(
        val batchId: EntityId,
        val blockId: EntityId,
        val gameId: EntityId,
        val cellId: EntityId,
        val cardCellId: EntityId,
        val notesCellId: EntityId,
    )

    private suspend fun given(
        rawText: String = "  12 KIRMIZI**\n8 MAVİ  ",
        columnIndex: Int = 1,
        sourceColumnType: SourceColumnType = SourceColumnType.THREE_D,
        status: ImportBatchStatus = ImportBatchStatus.DRAFT,
    ): Fixture {
        val game = aGame(name = "Harmonies")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        val cardCell = aCell(gameId = game.id, columnType = CellColumnType.CARD)
        val notesCell = aCell(gameId = game.id, columnType = CellColumnType.NOTES)
        database.gameDao().insert(game)
        listOf(cell, cardCell, notesCell).forEach { database.gameCellDao().insert(it) }

        val batch = anImportBatch(status = status, rawBlockCount = 1)
        val block =
            aRawImportBlock(
                batch.id,
                rawText = rawText,
                rowIndex = 1,
                columnIndex = columnIndex,
                sourceColumnType = sourceColumnType,
            )
        importDao.insertBatch(batch)
        importDao.insertRawBlock(block)
        return Fixture(batch.id, block.id, game.id, cell.id, cardCell.id, notesCell.id)
    }

    private suspend fun cutOut(
        fixture: Fixture,
        start: Int,
        end: Int,
        clock: Clock = StoppedClock(moment),
    ) = importDao.createDraftFromSelectionUnderReview(IdGenerator.Random.newId(), fixture.blockId, start, end, clock)

    private suspend fun colorIds(count: Int): List<EntityId> =
        database
            .colorDao()
            .allColors()
            .take(count)
            .map { it.id }

    private suspend fun stored(draftId: EntityId) = assertNotNull(importDao.draftTaskById(draftId))

    private suspend fun save(
        draftId: EntityId,
        name: String = "Kırmızı token",
        targetCellId: EntityId? = null,
        poolType: PoolType? = null,
        trackingMode: TrackingMode? = null,
        requiredQuantity: Int? = null,
        notes: String? = null,
        isMissing: Boolean = false,
        isBorrowed: Boolean = false,
        needsInfo: Boolean = false,
        needsClassification: Boolean = false,
        // Left out means "say nothing new about the marker": PLAN 11.5 makes
        // whether there was one a fact about the file, not something a form
        // decides.
        completionHint: HintDecision? = null,
        colorIds: List<EntityId> = emptyList(),
        clock: Clock = StoppedClock(moment),
    ): Boolean =
        importDao.editDraftUnderReview(
            draftTaskId = draftId,
            name = name,
            targetCellId = targetCellId,
            poolType = poolType,
            trackingMode = trackingMode,
            requiredQuantity = requiredQuantity,
            notes = notes,
            isMissing = isMissing,
            isBorrowed = isBorrowed,
            needsInfo = needsInfo,
            needsClassification = needsClassification,
            completionHint = completionHint ?: importDao.draftTaskById(draftId)?.completionHint ?: HintDecision.NONE,
            colorIds = colorIds,
            clock = clock,
        )

    // ------------------------------------------ cutting a draft out of a cell

    @Test
    fun `a draft cut from a cell keeps the trimmed offsets and the cleared name`() =
        runBlocking<Unit> {
            val fixture = given()

            val draft = cutOut(fixture, start = 2, end = 15)

            assertEquals("12 KIRMIZI", draft.name)
            assertEquals(2, draft.selectionStartIndex)
            assertEquals(14, draft.selectionEndIndex)
            assertEquals(12, draft.requiredQuantity)
            assertEquals(HintDecision.PENDING, draft.completionHint)
            assertEquals(PoolType.THREE_D, draft.suggestedPoolType)
            assertNull(draft.selectedPoolType, "a suggestion is never a decision")
        }

    @Test
    fun `cutting a draft out does not change the cell text or its review mark`() =
        runBlocking<Unit> {
            val text = "  12 KIRMIZI**\n8 MAVİ  "
            val fixture = given(rawText = text)

            cutOut(fixture, 2, 15)

            val block = assertNotNull(importDao.rawBlockById(fixture.blockId))
            assertEquals(text, block.rawText)
            assertTrue(!block.isProcessed)
        }

    @Test
    fun `a selection that cuts a character in half is refused with nothing written`() =
        runBlocking<Unit> {
            val fixture = given(rawText = "aile 👨‍👩‍👧‍👦 kartı")

            assertEquals(
                ImportReviewFailure.SELECTION_SPLITS_A_CHARACTER,
                assertFailsWith<ImportReviewException> { cutOut(fixture, 5, 8) }.failure,
            )
            assertEquals(emptyList(), importDao.draftTasksOfBlock(fixture.blockId))
        }

    @Test
    fun `a selection reaching past the stored text is refused with nothing written`() =
        runBlocking<Unit> {
            val fixture = given(rawText = "kısa")

            assertEquals(
                ImportReviewFailure.INVALID_SELECTION,
                assertFailsWith<ImportReviewException> { cutOut(fixture, 0, 40) }.failure,
            )
            assertEquals(emptyList(), importDao.draftTasksOfBlock(fixture.blockId))
        }

    @Test
    fun `a selection against a cell that is gone is refused with nothing written`() =
        runBlocking<Unit> {
            given()

            assertEquals(
                ImportReviewFailure.RAW_BLOCK_NOT_FOUND,
                assertFailsWith<ImportReviewException> {
                    importDao.createDraftFromSelectionUnderReview(
                        IdGenerator.Random.newId(),
                        IdGenerator.Random.newId(),
                        0,
                        3,
                        StoppedClock(moment),
                    )
                }.failure,
            )
        }

    @Test
    fun `a confirmed import takes no new draft, cut or typed`() =
        runBlocking<Unit> {
            val fixture = given(status = ImportBatchStatus.CONFIRMED)

            assertEquals(
                ImportReviewFailure.BATCH_NOT_A_DRAFT,
                assertFailsWith<ImportReviewException> { cutOut(fixture, 2, 15) }.failure,
            )
            assertEquals(
                ImportReviewFailure.BATCH_NOT_A_DRAFT,
                assertFailsWith<ImportReviewException> {
                    importDao.createDraftByHandUnderReview(
                        IdGenerator.Random.newId(),
                        fixture.blockId,
                        "Elle",
                        StoppedClock(moment),
                    )
                }.failure,
            )
            assertEquals(emptyList(), importDao.draftTasksOfBlock(fixture.blockId))
        }

    @Test
    fun `overlapping cuts make two independent drafts`() =
        runBlocking<Unit> {
            val fixture = given(rawText = "kırmızı token")

            val whole = cutOut(fixture, 0, 13)
            val part = cutOut(fixture, 8, 13)

            assertEquals(listOf("kırmızı token", "token"), listOf(whole.name, part.name))
            assertTrue(whole.id != part.id)
            assertEquals(2, importDao.draftTasksOfBlock(fixture.blockId).size)
        }

    @Test
    fun `a draft typed by hand carries no selection at all`() =
        runBlocking<Unit> {
            val fixture = given()

            val draft =
                importDao.createDraftByHandUnderReview(
                    IdGenerator.Random.newId(),
                    fixture.blockId,
                    "  Elle yazılan  ",
                    StoppedClock(moment),
                )

            assertEquals("Elle yazılan", draft.name)
            assertNull(draft.selectionStartIndex)
            assertNull(draft.selectionEndIndex)
            assertNull(draft.requiredQuantity)
        }

    // -------------------------------------------------- saying what it is

    @Test
    fun `a whole form is saved together and read back after reopening`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)
            val colors = colorIds(3)

            assertTrue(
                save(
                    draft.id,
                    name = "Kırmızı ev",
                    targetCellId = fixture.cellId,
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 12,
                    notes = "ikinci baskı",
                    needsInfo = true,
                    completionHint = HintDecision.ACCEPTED,
                    colorIds = colors,
                ),
            )

            database.close()
            database = DatabaseFactory().open(directory.databaseFile)

            val saved = stored(draft.id)
            assertEquals("Kırmızı ev", saved.name)
            assertEquals(fixture.cellId, saved.targetCellId)
            assertEquals(PoolType.THREE_D, saved.selectedPoolType)
            assertEquals(TrackingMode.THREE_D_BATCH, saved.selectedTrackingMode)
            assertEquals(12, saved.requiredQuantity)
            assertEquals("ikinci baskı", saved.notes)
            assertTrue(saved.needsInfo)
            assertEquals(HintDecision.ACCEPTED, saved.completionHint)
            assertEquals(colors, importDao.draftColorsOf(draft.id).map { it.colorId })
            assertEquals(listOf(0, 1, 2), importDao.draftColorsOf(draft.id).map { it.slotIndex })
        }

    @Test
    fun `an unknown total is stored as unknown and never as a stand-in number`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)

            save(draft.id, requiredQuantity = null, needsInfo = true)

            assertNull(stored(draft.id).requiredQuantity)
        }

    @Test
    fun `a total of nought or below is refused and the draft is left alone`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)
            save(draft.id, requiredQuantity = 12)

            listOf(0, -1).forEach { bad ->
                assertEquals(
                    ImportReviewFailure.INVALID_REQUIRED_QUANTITY,
                    assertFailsWith<ImportReviewException> { save(draft.id, requiredQuantity = bad) }.failure,
                )
            }
            assertEquals(12, stored(draft.id).requiredQuantity)
        }

    @Test
    fun `a name that says nothing is refused and the old name stands`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)

            assertEquals(
                ImportReviewFailure.TASK_NAME_EMPTY,
                assertFailsWith<ImportReviewException> { save(draft.id, name = "   ") }.failure,
            )
            assertEquals("12 KIRMIZI", stored(draft.id).name)
        }

    @Test
    fun `a name running across a line ending is refused`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)

            assertEquals(
                ImportReviewFailure.SELECTION_CONTAINS_LINE_BREAK,
                assertFailsWith<ImportReviewException> { save(draft.id, name = "iki\nsatır") }.failure,
            )
        }

    @Test
    fun `the user's note is kept exactly as it was written`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)

            save(draft.id, notes = "  iki yedek  ")

            assertEquals("  iki yedek  ", stored(draft.id).notes)
        }

    @Test
    fun `a pool takes only the ways of tracking it allows`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)

            assertEquals(
                ImportReviewFailure.TRACKING_MODE_NOT_ALLOWED,
                assertFailsWith<ImportReviewException> {
                    save(draft.id, poolType = PoolType.THREE_D, trackingMode = TrackingMode.PIPELINE)
                }.failure,
            )
            assertNull(stored(draft.id).selectedPoolType)
        }

    @Test
    fun `a cell of a different column than the pool is refused`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)

            assertEquals(
                ImportReviewFailure.TARGET_CELL_WRONG_COLUMN,
                assertFailsWith<ImportReviewException> {
                    save(
                        draft.id,
                        targetCellId = fixture.cardCellId,
                        poolType = PoolType.THREE_D,
                        trackingMode = TrackingMode.THREE_D_BATCH,
                    )
                }.failure,
            )
            assertNull(stored(draft.id).targetCellId)
        }

    @Test
    fun `the notes column holds no task`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)

            assertEquals(
                ImportReviewFailure.TARGET_CELL_NOT_TASK_CAPABLE,
                assertFailsWith<ImportReviewException> { save(draft.id, targetCellId = fixture.notesCellId) }.failure,
            )
        }

    @Test
    fun `a cell that is gone is refused`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)

            assertEquals(
                ImportReviewFailure.TARGET_CELL_NOT_AVAILABLE,
                assertFailsWith<ImportReviewException> {
                    save(draft.id, targetCellId = IdGenerator.Random.newId())
                }.failure,
            )
        }

    // ------------------------------------------------------------ the marks

    @Test
    fun `missing and borrowed together are refused and the draft is left alone`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)
            save(draft.id, isMissing = true)

            assertEquals(
                ImportReviewFailure.MISSING_AND_BORROWED,
                assertFailsWith<ImportReviewException> {
                    save(draft.id, isMissing = true, isBorrowed = true)
                }.failure,
            )
            val saved = stored(draft.id)
            assertTrue(saved.isMissing)
            assertTrue(!saved.isBorrowed)
        }

    @Test
    fun `typing a total does not clear the information mark by itself`() =
        runBlocking<Unit> {
            val fixture = given(rawText = "sayısına bakılacak")
            val draft = cutOut(fixture, 0, 18)
            assertTrue(draft.needsInfo, "no count was written, so the draft asks for one")

            // The user fills the count in but says nothing about the mark.
            save(draft.id, name = draft.name, requiredQuantity = 12, needsInfo = true)

            assertTrue(stored(draft.id).needsInfo, "PLAN 11.7 leaves the judgement to the user")

            save(draft.id, name = draft.name, requiredQuantity = 12, needsInfo = false)
            assertTrue(!stored(draft.id).needsInfo, "and takes it off when they say so")
        }

    // --------------------------------------------------------- the colours

    @Test
    fun `no colours, one colour and three colours are all valid answers`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)
            val three = colorIds(3)

            save(draft.id, colorIds = emptyList())
            assertEquals(emptyList(), importDao.draftColorsOf(draft.id))

            save(draft.id, colorIds = three.take(1))
            assertEquals(three.take(1), importDao.draftColorsOf(draft.id).map { it.colorId })

            save(draft.id, colorIds = three)
            assertEquals(three, importDao.draftColorsOf(draft.id).map { it.colorId })
        }

    @Test
    fun `the order of the colours is the answer and is stored as given`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)
            val three = colorIds(3)
            save(draft.id, colorIds = three)

            assertTrue(save(draft.id, colorIds = three.reversed()))

            assertEquals(three.reversed(), importDao.draftColorsOf(draft.id).map { it.colorId })
            assertEquals(listOf(0, 1, 2), importDao.draftColorsOf(draft.id).map { it.slotIndex })
        }

    @Test
    fun `the same colour twice is refused before anything is written`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)
            val two = colorIds(2)
            save(draft.id, colorIds = two)

            assertEquals(
                ImportReviewFailure.DUPLICATE_COLOR,
                assertFailsWith<ImportReviewException> {
                    save(draft.id, name = "Değişti", colorIds = listOf(two[0], two[1], two[0]))
                }.failure,
            )
            assertEquals(two, importDao.draftColorsOf(draft.id).map { it.colorId })
            assertEquals("Kırmızı token", stored(draft.id).name, "the name change went with the refusal")
        }

    @Test
    fun `a colour that is no longer in the catalogue is refused and nothing changes`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)
            val two = colorIds(2)
            save(draft.id, colorIds = two)

            assertEquals(
                ImportReviewFailure.COLOR_NOT_AVAILABLE,
                assertFailsWith<ImportReviewException> {
                    save(draft.id, name = "Değişti", colorIds = two + IdGenerator.Random.newId())
                }.failure,
            )
            assertEquals(two, importDao.draftColorsOf(draft.id).map { it.colorId })
            assertEquals("Kırmızı token", stored(draft.id).name)
        }

    // ------------------------------------------------------- saying nothing new

    @Test
    fun `saving the same answer changes nothing and does not read the clock`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)
            val colors = colorIds(2)
            save(
                draft.id,
                name = "Kırmızı ev",
                targetCellId = fixture.cellId,
                poolType = PoolType.THREE_D,
                trackingMode = TrackingMode.THREE_D_BATCH,
                requiredQuantity = 12,
                notes = "not",
                completionHint = HintDecision.ACCEPTED,
                colorIds = colors,
            )
            val before = stored(draft.id)

            val clock = CountingClock(moment)
            val changed =
                save(
                    draft.id,
                    name = "Kırmızı ev",
                    targetCellId = fixture.cellId,
                    poolType = PoolType.THREE_D,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    requiredQuantity = 12,
                    notes = "not",
                    completionHint = HintDecision.ACCEPTED,
                    colorIds = colors,
                    clock = clock,
                )

            assertTrue(!changed)
            assertEquals(0, clock.reads, "nothing changed, so no moment was needed")
            assertEquals(before, stored(draft.id))
        }

    @Test
    fun `a confirmed import refuses every change to its drafts`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = cutOut(fixture, 2, 15)
            markBatchConfirmedInPlace(fixture.batchId)

            assertEquals(
                ImportReviewFailure.BATCH_NOT_A_DRAFT,
                assertFailsWith<ImportReviewException> { save(draft.id, name = "Değişti") }.failure,
            )
            assertEquals("12 KIRMIZI", stored(draft.id).name)
        }

    @Test
    fun `a draft that is gone is refused rather than silently created`() =
        runBlocking<Unit> {
            given()

            assertEquals(
                ImportReviewFailure.DRAFT_TASK_NOT_FOUND,
                assertFailsWith<ImportReviewException> { save(IdGenerator.Random.newId()) }.failure,
            )
        }

    /** Confirms a batch by hand, for the read-only cases; no task is produced. */
    private suspend fun markBatchConfirmedInPlace(batchId: EntityId) {
        database.useWriterConnection { transactor ->
            transactor.usePrepared("UPDATE import_batches SET status = 'CONFIRMED' WHERE id = ?") { statement ->
                statement.bindText(1, batchId.toString())
                statement.step()
            }
        }
    }
}
