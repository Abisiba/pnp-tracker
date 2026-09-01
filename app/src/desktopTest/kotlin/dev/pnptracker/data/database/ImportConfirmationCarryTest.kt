package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What a confirmed import really leaves behind.
 *
 * PLAN 11.4.2 turns each draft into exactly one task and one piece of a cell.
 * Everything the user decided while reviewing has to travel with it — the
 * colours in their order, the flags the source columns gave, the `**` they
 * agreed to — because a decision that is not carried is a decision they will
 * have to make again over forty rows.
 *
 * Against a real database rather than a stand in: the colours are foreign keys,
 * the pipeline is a table, and a task born finished has to satisfy the same
 * invariants as one ticked by hand. None of that can be shown by asking a fake
 * what it would have done.
 */
class ImportConfirmationCarryTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

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

    /** One import aimed at one cell of one game, built a draft at a time. */
    private inner class Fixture(
        val gameId: EntityId,
        val cellId: EntityId,
        val batchId: EntityId,
    ) {
        var rows = 0
        var lastDraftId: EntityId? = null

        suspend fun draft(
            name: String = "Kırmızı ev",
            poolType: PoolType = PoolType.THREE_D,
            trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
            quantity: Int? = 20,
            notes: String? = null,
            completionHint: HintDecision = HintDecision.NONE,
            isMissing: Boolean = false,
            isBorrowed: Boolean = false,
            needsInfo: Boolean = false,
            needsClassification: Boolean = false,
            colorIds: List<EntityId> = emptyList(),
            cellId: EntityId = this.cellId,
        ): EntityId {
            rows++
            val block =
                aRawImportBlock(
                    batchId,
                    rowIndex = rows,
                    columnIndex = 1,
                    rawText = "15 KIRMIZI $name",
                    sourceColumnType = SourceColumnType.THREE_D,
                )
            importDao.insertRawBlock(block)
            importDao.setRawBlockProcessed(block.id, true, updatedAt)
            val draft =
                aDraftTask(block.id, name = name).copy(
                    requiredQuantity = quantity,
                    notes = notes,
                    completionHint = completionHint,
                    isMissing = isMissing,
                    isBorrowed = isBorrowed,
                    needsInfo = needsInfo,
                    needsClassification = needsClassification,
                )
            importDao.addDraftTask(draft)
            importDao.setDraftTargetUnderReview(draft.id, cellId, poolType, trackingMode, updatedAt)
            if (colorIds.isNotEmpty()) importDao.setDraftColorsUnderReview(draft.id, colorIds, StoppedClock(updatedAt))
            lastDraftId = draft.id
            return draft.id
        }

        /** A green game cell answered yes about [targetGameId]. */
        suspend fun greenHint(targetGameId: EntityId?): EntityId {
            rows++
            val block =
                aRawImportBlock(
                    batchId,
                    rowIndex = 500 + rows,
                    columnIndex = 0,
                    rawText = "Harmonies",
                    sourceColumnType = SourceColumnType.GAME,
                )
            importDao.insertRawBlock(block)
            importDao.setRawBlockProcessed(block.id, true, updatedAt)
            if (targetGameId != null) {
                importDao.setGameCompletionDecisionUnderReview(
                    block.id,
                    HintDecision.ACCEPTED,
                    targetGameId,
                    StoppedClock(updatedAt),
                )
            }
            return block.id
        }

        /** An accepted green cell with no game, the way version 5 could hold one. */
        suspend fun legacyGreenHint(): EntityId {
            val blockId = greenHint(null)
            database.useWriterConnection { transactor ->
                transactor.usePrepared(
                    "UPDATE raw_import_blocks SET game_completion_hint = 'ACCEPTED' WHERE id = ?",
                ) { statement ->
                    statement.bindText(1, blockId.toString())
                    statement.step()
                }
            }
            return blockId
        }

        suspend fun confirm(): Int =
            importDao.confirmDraftBatch(batchId, acknowledgeUnprocessedBlocks = true, moment = moment, idGenerator = IdGenerator.Random)
    }

    private suspend fun given(columnType: CellColumnType = CellColumnType.THREE_D): Fixture {
        val game = aGame(name = "Harmonies")
        val cell = aCell(gameId = game.id, columnType = columnType)
        database.gameDao().insert(game)
        database.gameCellDao().insert(cell)
        val batch = anImportBatch(rawBlockCount = 1)
        importDao.insertBatch(batch)
        return Fixture(game.id, cell.id, batch.id)
    }

    private suspend fun colorId(name: String): EntityId = assertNotNull(database.colorDao().resolve(name)).id

    private suspend fun taskOf(draftId: EntityId) =
        assertNotNull(
            assertNotNull(importDao.draftTaskById(draftId)).materializedTaskId?.let {
                database.taskDao().taskByIdIncludingDeleted(it)
            },
        )

    // -------------------------------------------------------------- shape

    @Test
    fun `one draft becomes one task and one piece of its cell`() =
        runBlocking<Unit> {
            val fixture = given()
            val draftId = fixture.draft(name = "Gri token", quantity = 14, notes = "iki grup")

            assertEquals(1, fixture.confirm())

            val task = taskOf(draftId)
            assertEquals("Gri token", task.name)
            assertEquals(14, task.requiredQuantity)
            assertEquals("iki grup", task.notes)
            assertEquals(moment, task.createdAt)
            assertEquals(moment, task.updatedAt)
            val segments = database.cellSegmentDao().segmentsOfCell(fixture.cellId)
            assertEquals(listOf(task.id), segments.map { it.taskId })
            assertEquals(listOf(0), segments.map { it.orderIndex })
        }

    // ------------------------------------------------------------ colours

    @Test
    fun `a draft with no colours makes a task with none`() =
        runBlocking<Unit> {
            val fixture = given()
            val draftId = fixture.draft()

            fixture.confirm()

            assertEquals(emptyList(), database.taskColorDao().colorsOfTask(taskOf(draftId).id))
        }

    @Test
    fun `one colour lands in the first slot`() =
        runBlocking<Unit> {
            val fixture = given()
            val grey = colorId("Gri")
            val draftId = fixture.draft(colorIds = listOf(grey))

            fixture.confirm()

            assertEquals(
                listOf(grey to 0),
                database.taskColorDao().colorsOfTask(taskOf(draftId).id).map { it.colorId to it.slotIndex },
            )
        }

    @Test
    fun `three colours keep the order the user chose, and still make one task`() =
        runBlocking<Unit> {
            val fixture = given()
            val colors = listOf("Yeşil", "Gri", "Mavi").map { colorId(it) }
            val draftId = fixture.draft(name = "Ev", colorIds = colors)

            fixture.confirm()

            val task = taskOf(draftId)
            assertEquals(
                colors.mapIndexed { slot, id -> id to slot },
                database.taskColorDao().colorsOfTask(task.id).map { it.colorId to it.slotIndex },
                "the colours did not arrive in the order they were chosen",
            )
            // PLAN 5.10 and 12.7: three colours, one task, one piece of the cell.
            assertEquals(1, database.taskDao().activeTasksOfCell(fixture.cellId).size)
            assertEquals(1, database.cellSegmentDao().segmentsOfCell(fixture.cellId).size)
            assertEquals("Ev", task.name)
        }

    @Test
    fun `a colour a draft holds cannot be deleted, so a confirmation cannot meet a gap`() =
        runBlocking<Unit> {
            val fixture = given()
            val grey = colorId("Gri")
            fixture.draft(colorIds = listOf(grey))

            // PLAN 5.9 deletes a colour only on the user's own say-so, and the
            // draft's choice holds it back. This is the first line of defence:
            // the catalogue check inside the confirmation is the second.
            assertFailsWith<Throwable> { database.colorDao().deleteColorTheUserHasConfirmed(grey) }

            assertEquals(1, fixture.confirm())
            assertEquals(
                listOf(grey),
                database.taskColorDao().colorsOfTask(taskOf(assertNotNull(fixture.lastDraftId)).id).map { it.colorId },
            )
        }

    @Test
    fun `a colour that has gone anyway takes the whole import back`() =
        runBlocking<Unit> {
            val fixture = given()
            val doomed = colorId("Turuncu")
            fixture.draft(name = "Bir", colorIds = listOf(colorId("Gri")))
            fixture.draft(name = "İki", colorIds = listOf(doomed))
            // A database damaged from outside: the key that would have stopped
            // this is stood down for one statement, which is the only way to
            // reach the state the confirmation's own check exists for.
            database.useWriterConnection { transactor ->
                transactor.usePrepared("PRAGMA foreign_keys = OFF") { it.step() }
                transactor.usePrepared("DELETE FROM colors WHERE id = ?") { statement ->
                    statement.bindText(1, doomed.toString())
                    statement.step()
                }
                transactor.usePrepared("PRAGMA foreign_keys = ON") { it.step() }
            }

            val failure = assertFailsWith<ImportConfirmationException> { fixture.confirm() }

            assertEquals(ImportConfirmationFailure.COLOR_NO_LONGER_AVAILABLE, failure.failure)
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted(), "a task survived the refusal")
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "task_colors"))
            assertEquals(ImportBatchStatus.DRAFT, assertNotNull(importDao.batchById(fixture.batchId)).status)
        }

    // -------------------------------------------------------------- flags

    @Test
    fun `all four flags travel from the draft to the task`() =
        runBlocking<Unit> {
            val fixture = given()
            val missing = fixture.draft(name = "Eksik kart", isMissing = true, needsClassification = true)
            val borrowed = fixture.draft(name = "Ödünç zar", isBorrowed = true)
            val unknown = fixture.draft(name = "Sayısına bakılacak", quantity = null, needsInfo = true)
            val plain = fixture.draft(name = "Sıradan")

            fixture.confirm()

            val missingTask = taskOf(missing)
            assertTrue(missingTask.isMissing)
            assertTrue(missingTask.needsClassification)
            assertFalse(missingTask.isBorrowed)
            assertTrue(taskOf(borrowed).isBorrowed)
            assertTrue(taskOf(unknown).needsInfo)
            assertNull(taskOf(unknown).requiredQuantity)
            listOf(plain).forEach { id ->
                val task = taskOf(id)
                assertFalse(task.isMissing || task.isBorrowed || task.needsInfo || task.needsClassification)
            }
        }

    @Test
    fun `a missing flag is not a shortage and writes no history`() =
        runBlocking<Unit> {
            val fixture = given()
            val draftId = fixture.draft(name = "Eksik kart", isMissing = true, needsClassification = true)

            fixture.confirm()

            val task = taskOf(draftId)
            assertTrue(task.isMissing)
            assertEquals(0, task.currentMissingQuantity, "an import flag was read as a debt")
            assertEquals(emptyList(), database.taskProgressDao().progressEventsOfTask(task.id))
            assertEquals(0L, database.taskProgressDao().failureTotalOf(task.id))
        }

    // ---------------------------------------------------- the `**` marker

    @Test
    fun `an accepted marker finishes a 3D task the way finishing one does`() =
        runBlocking<Unit> {
            val fixture = given()
            val draftId = fixture.draft(quantity = 40, completionHint = HintDecision.ACCEPTED)

            fixture.confirm()

            val task = taskOf(draftId)
            assertTrue(task.isCompleted)
            assertEquals(moment, task.completedAt)
            assertTrue(task.primaryBatchCompleted, "a finished 3D task never had its print run made")
            assertEquals(0, task.currentMissingQuantity)
            assertEquals(emptyList(), database.taskProgressDao().progressEventsOfTask(task.id))
        }

    @Test
    fun `an accepted marker counts a card pipeline all the way up`() =
        runBlocking<Unit> {
            val fixture = given(columnType = CellColumnType.CARD)
            val draftId =
                fixture.draft(
                    name = "Bird Cards",
                    poolType = PoolType.CARD,
                    trackingMode = TrackingMode.PIPELINE,
                    quantity = 170,
                    completionHint = HintDecision.ACCEPTED,
                )

            fixture.confirm()

            val task = taskOf(draftId)
            assertTrue(task.isCompleted)
            assertFalse(task.primaryBatchCompleted, "a card task was given a 3D print run")
            assertEquals(
                listOf(ProductionStage.PRINT to 170, ProductionStage.LAMINATE to 170, ProductionStage.CUT to 170),
                database.taskProgressDao().stagesOfTask(task.id).map { it.stage to it.completedQuantity },
            )
        }

    @Test
    fun `a finished card task with no total is left with its pipeline where it stands`() =
        runBlocking<Unit> {
            val fixture = given(columnType = CellColumnType.CARD)
            val draftId =
                fixture.draft(
                    name = "Deste",
                    poolType = PoolType.CARD,
                    trackingMode = TrackingMode.PIPELINE,
                    quantity = null,
                    completionHint = HintDecision.ACCEPTED,
                )

            fixture.confirm()

            val task = taskOf(draftId)
            assertTrue(task.isCompleted)
            // PLAN 6.4: with no total there is nothing for a stage to reach.
            assertEquals(
                listOf(0, 0, 0),
                database.taskProgressDao().stagesOfTask(task.id).map { it.completedQuantity },
            )
        }

    @Test
    fun `an accepted marker counts a board pipeline all the way up`() =
        runBlocking<Unit> {
            val fixture = given(columnType = CellColumnType.BOARD)
            val draftId =
                fixture.draft(
                    name = "Plaj tile",
                    poolType = PoolType.BOARD,
                    trackingMode = TrackingMode.PIPELINE,
                    quantity = 16,
                    completionHint = HintDecision.ACCEPTED,
                )

            fixture.confirm()

            assertEquals(
                listOf(ProductionStage.PRINT to 16, ProductionStage.GLUE to 16, ProductionStage.CUT to 16),
                database.taskProgressDao().stagesOfTask(taskOf(draftId).id).map { it.stage to it.completedQuantity },
            )
        }

    @Test
    fun `an accepted marker finishes a special task without inventing a pipeline`() =
        runBlocking<Unit> {
            val fixture = given(columnType = CellColumnType.SPECIAL)
            val checklist =
                fixture.draft(
                    name = "Zarf",
                    poolType = PoolType.SPECIAL,
                    trackingMode = TrackingMode.CHECKLIST,
                    quantity = null,
                    completionHint = HintDecision.ACCEPTED,
                )
            val counted =
                fixture.draft(
                    name = "Zar",
                    poolType = PoolType.SPECIAL,
                    trackingMode = TrackingMode.COUNTED,
                    quantity = 8,
                    completionHint = HintDecision.ACCEPTED,
                )

            fixture.confirm()

            listOf(checklist, counted).forEach { draftId ->
                val task = taskOf(draftId)
                assertTrue(task.isCompleted)
                assertEquals(moment, task.completedAt)
                assertFalse(task.primaryBatchCompleted)
                assertEquals(emptyList(), database.taskProgressDao().stagesOfTask(task.id))
                assertEquals(emptyList(), database.taskProgressDao().progressEventsOfTask(task.id))
            }
        }

    @Test
    fun `a marker nobody agreed to leaves the task open`() =
        runBlocking<Unit> {
            val fixture = given()
            val pending = fixture.draft(name = "Bekleyen", completionHint = HintDecision.PENDING)
            val rejected = fixture.draft(name = "Reddedilen", completionHint = HintDecision.REJECTED)
            val none = fixture.draft(name = "İpucusuz", completionHint = HintDecision.NONE)

            fixture.confirm()

            listOf(pending, rejected, none).forEach { draftId ->
                val task = taskOf(draftId)
                assertFalse(task.isCompleted, "an unanswered hint finished a task")
                assertNull(task.completedAt)
                assertFalse(task.primaryBatchCompleted)
            }
        }

    @Test
    fun `colours change nothing about being finished, and one task carries one completion`() =
        runBlocking<Unit> {
            val fixture = given()
            val colors = listOf("Gri", "Mavi", "Yeşil").map { colorId(it) }
            val finished = fixture.draft(name = "Bitmiş ev", colorIds = colors, completionHint = HintDecision.ACCEPTED)
            val open = fixture.draft(name = "Açık ev", colorIds = listOf(colorId("Sarı")))

            fixture.confirm()

            val task = taskOf(finished)
            assertTrue(task.isCompleted)
            assertEquals(3, database.taskColorDao().colorCountOfTask(task.id))
            assertFalse(taskOf(open).isCompleted, "one draft's marker reached another draft")
        }

    // ------------------------------------------------- the green game cell

    @Test
    fun `an accepted green cell finishes the game it names and nothing else`() =
        runBlocking<Unit> {
            val fixture = given()
            val draftId = fixture.draft()
            fixture.greenHint(fixture.gameId)

            fixture.confirm()

            val game = assertNotNull(database.gameDao().activeGameById(fixture.gameId))
            assertTrue(game.isManuallyCompleted)
            assertEquals(moment, game.completedAt)
            assertEquals(moment, game.updatedAt)
            // PLAN 5.3: a game's state is the user's statement, not a summary of
            // its work, so the work it holds is untouched.
            assertFalse(taskOf(draftId).isCompleted, "a green cell finished the tasks as well")
        }

    @Test
    fun `three cells naming one game finish it once`() =
        runBlocking<Unit> {
            val fixture = given()
            fixture.draft()
            repeat(3) { fixture.greenHint(fixture.gameId) }

            fixture.confirm()

            assertTrue(assertNotNull(database.gameDao().activeGameById(fixture.gameId)).isManuallyCompleted)
        }

    @Test
    fun `a game the user had already finished keeps the date they set`() =
        runBlocking<Unit> {
            val earlier = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val fixture = given()
            database.gameDao().setManuallyCompleted(fixture.gameId, true, earlier, earlier)
            fixture.draft()
            fixture.greenHint(fixture.gameId)

            fixture.confirm()

            val game = assertNotNull(database.gameDao().activeGameById(fixture.gameId))
            assertTrue(game.isManuallyCompleted)
            assertEquals(earlier, game.completedAt, "an import moved a date the user set")
            assertEquals(earlier, game.updatedAt)
        }

    @Test
    fun `a green cell naming a deleted game takes the whole import back`() =
        runBlocking<Unit> {
            val fixture = given()
            val other = aGame(name = "Wingspan")
            database.gameDao().insert(other)
            fixture.draft()
            fixture.greenHint(other.id)
            database.gameDao().softDelete(other.id, deletedAt)

            val failure = assertFailsWith<ImportConfirmationException> { fixture.confirm() }

            assertEquals(ImportConfirmationFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE, failure.failure)
            assertEquals(emptyList(), database.taskDao().activeTasks(), "a task survived a refused confirmation")
            assertEquals(ImportBatchStatus.DRAFT, assertNotNull(importDao.batchById(fixture.batchId)).status)
        }

    @Test
    fun `an acceptance with no game is refused and nothing at all is written`() =
        runBlocking<Unit> {
            val fixture = given()
            fixture.draft()
            fixture.legacyGreenHint()

            val failure = assertFailsWith<ImportConfirmationException> { fixture.confirm() }

            assertEquals(ImportConfirmationFailure.COMPLETION_TARGET_GAME_REQUIRED, failure.failure)
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "task_colors"))
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "task_stages"))
            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "cell_segments"))
            assertFalse(assertNotNull(database.gameDao().activeGameById(fixture.gameId)).isManuallyCompleted)
            val batch = assertNotNull(importDao.batchById(fixture.batchId))
            assertEquals(ImportBatchStatus.DRAFT, batch.status)
            assertEquals(0, batch.createdTaskCount)
            assertTrue(importDao.draftTasksOfBatch(fixture.batchId).all { it.materializedTaskId == null })
        }

    @Test
    fun `a clock finer than a millisecond still confirms`() =
        runBlocking<Unit> {
            // The columns hold milliseconds. A clock carrying microseconds — which
            // the system clock does — must not make a confirmation fail on a
            // timestamp it read back rounded.
            val fine = Instant.fromEpochSeconds(1_780_000_000, 123_456_789)
            val fixture = given()
            val draftId = fixture.draft(completionHint = HintDecision.ACCEPTED)
            fixture.greenHint(fixture.gameId)

            importDao.confirmDraftBatch(fixture.batchId, true, fine, IdGenerator.Random)

            assertTrue(assertNotNull(database.gameDao().activeGameById(fixture.gameId)).isManuallyCompleted)
            assertNotNull(assertNotNull(database.gameDao().activeGameById(fixture.gameId)).completedAt)
            assertTrue(taskOf(draftId).isCompleted)
            assertNotNull(taskOf(draftId).completedAt)
        }

    @Test
    fun `confirming still creates no game at all`() =
        runBlocking<Unit> {
            val fixture = given()
            fixture.draft()
            fixture.greenHint(fixture.gameId)

            fixture.confirm()

            assertEquals(0, assertNotNull(importDao.batchById(fixture.batchId)).createdGameCount)
            assertEquals(1, database.gameDao().allGamesIncludingDeleted().size)
        }

    // ------------------------------------------------------- the document

    @Test
    fun `many drafts in one cell are numbered one after another`() =
        runBlocking<Unit> {
            val fixture = given()
            (0..<42).forEach { fixture.draft(name = "Token $it") }

            assertEquals(42, fixture.confirm())

            val segments = database.cellSegmentDao().segmentsOfCell(fixture.cellId)
            assertEquals((0..<42).toList(), segments.map { it.orderIndex }, "the document was left with a gap in it")
            // In the order the drafts themselves come back, which is the order
            // the review screen lists them in; nothing is left to chance.
            assertEquals(
                importDao.draftTasksOfBatch(fixture.batchId).map { it.materializedTaskId },
                segments.map { it.taskId },
            )
        }

    @Test
    fun `what the cell already said is kept, and the tasks come after it`() =
        runBlocking<Unit> {
            val fixture = given()
            database.cellSegmentDao().saveDocumentText(
                fixture.gameId,
                CellColumnType.THREE_D,
                "",
                "Kutu ve kapak",
                StoppedClock(updatedAt),
                IdGenerator.Random,
            )
            fixture.draft(name = "Bir")
            fixture.draft(name = "İki")

            fixture.confirm()

            val segments = database.cellSegmentDao().segmentsOfCell(fixture.cellId)
            assertEquals(listOf(0, 1, 2), segments.map { it.orderIndex })
            assertEquals("Kutu ve kapak", segments.first().text, "the plain text the user wrote was changed")
            assertEquals(
                listOf(null) + importDao.draftTasksOfBatch(fixture.batchId).map { it.materializedTaskId },
                segments.map { it.taskId },
                "the tasks did not follow the text the cell already held",
            )
            assertEquals(setOf("Bir", "İki"), segments.drop(1).mapNotNull { taskName(assertNotNull(it.taskId)) }.toSet())
        }

    @Test
    fun `drafts aimed at different cells are numbered independently`() =
        runBlocking<Unit> {
            val fixture = given()
            val second = aGame(name = "Wingspan")
            val secondCell = aCell(gameId = second.id, columnType = CellColumnType.THREE_D)
            database.gameDao().insert(second)
            database.gameCellDao().insert(secondCell)
            fixture.draft(name = "Bir")
            fixture.draft(name = "İki", cellId = secondCell.id)
            fixture.draft(name = "Üç")

            fixture.confirm()

            assertEquals(listOf(0, 1), database.cellSegmentDao().segmentsOfCell(fixture.cellId).map { it.orderIndex })
            assertEquals(listOf(0), database.cellSegmentDao().segmentsOfCell(secondCell.id).map { it.orderIndex })
        }

    private suspend fun taskName(taskId: EntityId): String? = database.taskDao().taskByIdIncludingDeleted(taskId)?.name

    @Test
    fun `a second confirmation is still a guarded refusal that writes nothing`() =
        runBlocking<Unit> {
            val fixture = given()
            fixture.draft()
            fixture.greenHint(fixture.gameId)
            fixture.confirm()
            val tasksBefore = database.taskDao().allTasksIncludingDeleted()

            val failure = assertFailsWith<ImportConfirmationException> { fixture.confirm() }

            assertEquals(ImportConfirmationFailure.ALREADY_CONFIRMED, failure.failure)
            assertEquals(tasksBefore, database.taskDao().allTasksIncludingDeleted())
            assertEquals(1, CommittedSchema.countRowsOf(directory.databaseFile, "cell_segments"))
        }
}
