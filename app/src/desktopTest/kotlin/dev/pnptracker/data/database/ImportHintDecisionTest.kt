package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.domain.importconfirm.ImportConfirmationException
import dev.pnptracker.domain.importconfirm.ImportConfirmationFailure
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The two hints the file leaves behind, and what happens once somebody answers
 * them.
 *
 * PLAN 11.5 makes `**` and a green game cell hints and nothing more. So both are
 * questions until the user answers, an unanswered one stops the whole import
 * rather than being guessed at, and the answer is stored so closing and
 * reopening the review finds it again (PLAN 11.4.3).
 *
 * A version 5 database could record an acceptance with nowhere to put the game
 * it was about. That row is kept and shown as the open question it is, and the
 * user can put it right — proved here end to end.
 */
class ImportHintDecisionTest {
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

    private class Fixture(
        val batchId: EntityId,
        val gameBlockId: EntityId,
        val taskBlockId: EntityId,
        val gameId: EntityId,
        val otherGameId: EntityId,
        val cellId: EntityId,
    )

    private suspend fun given(greenFill: Int? = 0xFF92D050.toInt()): Fixture {
        val game = aGame(name = "Wingspan")
        val other = aGame(name = "Harmonies")
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        val otherCell = aCell(gameId = other.id, columnType = CellColumnType.THREE_D)
        listOf(game, other).forEach { database.gameDao().insert(it) }
        listOf(cell, otherCell).forEach { database.gameCellDao().insert(it) }

        val batch = anImportBatch(rawBlockCount = 2)
        val gameBlock =
            aRawImportBlock(
                batch.id,
                rawText = "Wingspan",
                rowIndex = 1,
                columnIndex = 0,
                sourceColumnType = SourceColumnType.GAME,
                fillColorArgb = greenFill,
            )
        val taskBlock =
            aRawImportBlock(
                batch.id,
                rawText = "15 KIRMIZI**",
                rowIndex = 1,
                columnIndex = 1,
                sourceColumnType = SourceColumnType.THREE_D,
            )
        importDao.insertBatch(batch)
        importDao.insertRawBlock(gameBlock)
        importDao.insertRawBlock(taskBlock)
        if (greenFill != null) {
            setGreenHintPending(gameBlock.id)
        }
        return Fixture(batch.id, gameBlock.id, taskBlock.id, game.id, other.id, cell.id)
    }

    /** The state `prepareImportDraft` writes for a green game cell. */
    private suspend fun setGreenHintPending(blockId: EntityId) {
        database.useWriterConnection { transactor ->
            transactor.usePrepared(
                "UPDATE raw_import_blocks SET game_completion_hint = 'PENDING' WHERE id = ?",
            ) { statement ->
                statement.bindText(1, blockId.toString())
                statement.step()
            }
        }
    }

    /** The one shape only a version 5 database can hold: a yes with no game. */
    private suspend fun setLegacyAcceptedWithNoTarget(blockId: EntityId) {
        database.useWriterConnection { transactor ->
            transactor.usePrepared(
                """
                UPDATE raw_import_blocks
                SET game_completion_hint = 'ACCEPTED', completion_target_game_id = NULL
                WHERE id = ?
                """,
            ) { statement ->
                statement.bindText(1, blockId.toString())
                statement.step()
            }
        }
    }

    private suspend fun aReadyDraft(
        fixture: Fixture,
        start: Int = 0,
        end: Int = 12,
    ): EntityId {
        val draft =
            importDao.createDraftFromSelectionUnderReview(
                IdGenerator.Random.newId(),
                fixture.taskBlockId,
                start,
                end,
                StoppedClock(moment),
            )
        importDao.editDraftUnderReview(
            draftTaskId = draft.id,
            name = draft.name,
            targetCellId = fixture.cellId,
            poolType = PoolType.THREE_D,
            trackingMode = TrackingMode.THREE_D_BATCH,
            requiredQuantity = draft.requiredQuantity,
            notes = null,
            isMissing = false,
            isBorrowed = false,
            needsInfo = false,
            needsClassification = false,
            completionHint = draft.completionHint,
            colorIds = emptyList(),
            clock = StoppedClock(moment),
        )
        return draft.id
    }

    private suspend fun confirm(fixture: Fixture): Int = importDao.confirmDraftBatch(fixture.batchId, true, moment, IdGenerator.Random)

    private suspend fun taskOf(draftId: EntityId): dev.pnptracker.data.database.entity.TaskEntity {
        val taskId = assertNotNull(assertNotNull(importDao.draftTaskById(draftId)).materializedTaskId)
        return assertNotNull(database.taskDao().activeTaskById(taskId))
    }

    private suspend fun processEveryBlock(fixture: Fixture) {
        importDao.rawBlocksOfBatch(fixture.batchId).forEach {
            importDao.setRawBlockProcessed(it.id, true, moment)
        }
    }

    // ------------------------------------------------------------ the marker

    @Test
    fun `an unanswered marker stops the whole import and writes nothing`() =
        runBlocking<Unit> {
            val fixture = given(greenFill = null)
            val draft = aReadyDraft(fixture)
            assertEquals(HintDecision.PENDING, assertNotNull(importDao.draftTaskById(draft)).completionHint)

            val refusal = assertFailsWith<ImportConfirmationException> { confirm(fixture) }

            assertEquals(ImportConfirmationFailure.COMPLETION_HINT_UNDECIDED, refusal.failure)
            assertEquals(draft, refusal.draftTaskId, "the refusal names the draft to go and fix")
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
        }

    @Test
    fun `an accepted marker makes a task that is already finished`() =
        runBlocking<Unit> {
            val fixture = given(greenFill = null)
            val draft = aReadyDraft(fixture)
            importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.ACCEPTED, StoppedClock(moment))

            confirm(fixture)

            val task = taskOf(draft)
            assertTrue(task.isCompleted)
            assertEquals(moment, task.completedAt)
            assertTrue(task.primaryBatchCompleted, "a finished 3D task has had its print run made")
        }

    @Test
    fun `a rejected marker makes an ordinary open task`() =
        runBlocking<Unit> {
            val fixture = given(greenFill = null)
            val draft = aReadyDraft(fixture)
            importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.REJECTED, StoppedClock(moment))

            confirm(fixture)

            val task = taskOf(draft)
            assertFalse(task.isCompleted)
            assertNull(task.completedAt)
            assertFalse(task.primaryBatchCompleted)
        }

    @Test
    fun `an answered marker survives the review being closed and reopened`() =
        runBlocking<Unit> {
            val fixture = given(greenFill = null)
            val draft = aReadyDraft(fixture)
            importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.ACCEPTED, StoppedClock(moment))

            database.close()
            database = DatabaseFactory().open(directory.databaseFile)

            assertEquals(HintDecision.ACCEPTED, assertNotNull(importDao.draftTaskById(draft)).completionHint)
        }

    @Test
    fun `a draft the file never marked has no marker to answer`() =
        runBlocking<Unit> {
            val fixture = given(greenFill = null)
            val draft =
                importDao.createDraftByHandUnderReview(
                    IdGenerator.Random.newId(),
                    fixture.taskBlockId,
                    "Elle",
                    StoppedClock(moment),
                )

            assertEquals(
                ImportReviewFailure.COMPLETION_HINT_NOT_ANSWERABLE,
                assertFailsWith<ImportReviewException> {
                    importDao.setDraftCompletionDecisionUnderReview(
                        draft.id,
                        HintDecision.ACCEPTED,
                        StoppedClock(moment),
                    )
                }.failure,
            )
            assertEquals(HintDecision.NONE, assertNotNull(importDao.draftTaskById(draft.id)).completionHint)
        }

    @Test
    fun `answering the same way twice changes nothing and reads no clock`() =
        runBlocking<Unit> {
            val fixture = given(greenFill = null)
            val draft = aReadyDraft(fixture)
            importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.ACCEPTED, StoppedClock(moment))
            val clock = CountingClock(moment)

            val changed = importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.ACCEPTED, clock)

            assertFalse(changed)
            assertEquals(0, clock.reads)
        }

    // -------------------------------------------------------- the green cell

    @Test
    fun `an unanswered green cell stops the whole import and writes nothing`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = aReadyDraft(fixture)
            importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.REJECTED, StoppedClock(moment))

            val refusal = assertFailsWith<ImportConfirmationException> { confirm(fixture) }

            assertEquals(ImportConfirmationFailure.GAME_COMPLETION_HINT_UNDECIDED, refusal.failure)
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())
            assertFalse(assertNotNull(database.gameDao().activeGameById(fixture.gameId)).isManuallyCompleted)
        }

    @Test
    fun `an accepted green cell finishes exactly the game it names, at confirmation`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = aReadyDraft(fixture)
            importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.REJECTED, StoppedClock(moment))
            importDao.setGameCompletionDecisionUnderReview(
                fixture.gameBlockId,
                HintDecision.ACCEPTED,
                fixture.gameId,
                StoppedClock(moment),
            )

            // Storing the answer does not finish the game; confirming does.
            assertFalse(assertNotNull(database.gameDao().activeGameById(fixture.gameId)).isManuallyCompleted)

            confirm(fixture)

            assertTrue(assertNotNull(database.gameDao().activeGameById(fixture.gameId)).isManuallyCompleted)
            assertFalse(
                assertNotNull(database.gameDao().activeGameById(fixture.otherGameId)).isManuallyCompleted,
                "another game was finished by a hint that did not name it",
            )
        }

    @Test
    fun `a rejected green cell leaves every game exactly as it was`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = aReadyDraft(fixture)
            importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.REJECTED, StoppedClock(moment))
            importDao.setGameCompletionDecisionUnderReview(
                fixture.gameBlockId,
                HintDecision.REJECTED,
                null,
                StoppedClock(moment),
            )

            confirm(fixture)

            listOf(fixture.gameId, fixture.otherGameId).forEach { gameId ->
                assertFalse(assertNotNull(database.gameDao().activeGameById(gameId)).isManuallyCompleted)
            }
        }

    @Test
    fun `taking an acceptance back clears the game it named`() =
        runBlocking<Unit> {
            val fixture = given()
            importDao.setGameCompletionDecisionUnderReview(
                fixture.gameBlockId,
                HintDecision.ACCEPTED,
                fixture.gameId,
                StoppedClock(moment),
            )

            importDao.setGameCompletionDecisionUnderReview(
                fixture.gameBlockId,
                HintDecision.REJECTED,
                null,
                StoppedClock(moment),
            )

            val block = assertNotNull(importDao.rawBlockById(fixture.gameBlockId))
            assertEquals(HintDecision.REJECTED, block.gameCompletionHint)
            assertNull(block.completionTargetGameId)
        }

    @Test
    fun `a game that has been deleted cannot be named and the answer stands`() =
        runBlocking<Unit> {
            val fixture = given()
            importDao.setGameCompletionDecisionUnderReview(
                fixture.gameBlockId,
                HintDecision.ACCEPTED,
                fixture.gameId,
                StoppedClock(moment),
            )
            database.gameDao().softDelete(fixture.otherGameId, moment)

            assertEquals(
                ImportReviewFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE,
                assertFailsWith<ImportReviewException> {
                    importDao.setGameCompletionDecisionUnderReview(
                        fixture.gameBlockId,
                        HintDecision.ACCEPTED,
                        fixture.otherGameId,
                        StoppedClock(moment),
                    )
                }.failure,
            )
            assertEquals(
                fixture.gameId,
                assertNotNull(importDao.rawBlockById(fixture.gameBlockId)).completionTargetGameId,
                "the draft kept the answer it had",
            )
        }

    @Test
    fun `a green answer survives the review being closed and reopened`() =
        runBlocking<Unit> {
            val fixture = given()
            importDao.setGameCompletionDecisionUnderReview(
                fixture.gameBlockId,
                HintDecision.ACCEPTED,
                fixture.gameId,
                StoppedClock(moment),
            )

            database.close()
            database = DatabaseFactory().open(directory.databaseFile)

            val block = assertNotNull(importDao.rawBlockById(fixture.gameBlockId))
            assertEquals(HintDecision.ACCEPTED, block.gameCompletionHint)
            assertEquals(fixture.gameId, block.completionTargetGameId)
        }

    // ------------------------------------------------- the version 5 leftover

    @Test
    fun `an acceptance with no game is refused at confirmation and can be put right`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = aReadyDraft(fixture)
            importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.ACCEPTED, StoppedClock(moment))
            setLegacyAcceptedWithNoTarget(fixture.gameBlockId)
            processEveryBlock(fixture)

            // The row can be read and shown; the import simply cannot be confirmed.
            val block = assertNotNull(importDao.rawBlockById(fixture.gameBlockId))
            assertEquals(HintDecision.ACCEPTED, block.gameCompletionHint)
            assertNull(block.completionTargetGameId, "the version 5 shape: a yes with no game")
            assertEquals(
                ImportConfirmationFailure.COMPLETION_TARGET_GAME_REQUIRED,
                assertFailsWith<ImportConfirmationException> {
                    importDao.confirmDraftBatch(fixture.batchId, false, moment, IdGenerator.Random)
                }.failure,
            )
            assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted())

            // The user names the game, and the very same import goes through.
            importDao.setGameCompletionDecisionUnderReview(
                fixture.gameBlockId,
                HintDecision.ACCEPTED,
                fixture.gameId,
                StoppedClock(moment),
            )
            assertEquals(1, importDao.confirmDraftBatch(fixture.batchId, false, moment, IdGenerator.Random))
            assertTrue(assertNotNull(database.gameDao().activeGameById(fixture.gameId)).isManuallyCompleted)
        }

    @Test
    fun `an acceptance with no game can be answered no instead`() =
        runBlocking<Unit> {
            val fixture = given()
            val draft = aReadyDraft(fixture)
            importDao.setDraftCompletionDecisionUnderReview(draft, HintDecision.REJECTED, StoppedClock(moment))
            setLegacyAcceptedWithNoTarget(fixture.gameBlockId)

            importDao.setGameCompletionDecisionUnderReview(
                fixture.gameBlockId,
                HintDecision.REJECTED,
                null,
                StoppedClock(moment),
            )

            assertEquals(1, confirm(fixture))
            assertFalse(assertNotNull(database.gameDao().activeGameById(fixture.gameId)).isManuallyCompleted)
        }

    @Test
    fun `a cell that is not a game name cannot carry the green answer`() =
        runBlocking<Unit> {
            val fixture = given()

            assertEquals(
                ImportReviewFailure.BLOCK_CANNOT_CARRY_GAME_COMPLETION,
                assertFailsWith<ImportReviewException> {
                    importDao.setGameCompletionDecisionUnderReview(
                        fixture.taskBlockId,
                        HintDecision.ACCEPTED,
                        fixture.gameId,
                        StoppedClock(moment),
                    )
                }.failure,
            )
        }

    /** A clock that says how often it was read. */
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
}
