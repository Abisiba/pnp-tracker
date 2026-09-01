package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.SourceColumnType
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
 * What the user answers to a green game cell, and which game they meant.
 *
 * PLAN 5.3 lets an accepted import hint change whether a game is finished, and
 * PLAN 11.4 makes accepting or rejecting a hint the user's own act. Two things
 * follow, and both are what these tests are about. The answer and the game it is
 * about are one decision, so neither is stored without the other. And the
 * decision is stored rather than remembered: PLAN 11.4.3 lets a draft be closed
 * and reopened, so an answer that lived only on screen would be gone by then.
 *
 * Nothing here finishes a game. Storing the decision and acting on it are two
 * different moments, and the second one belongs to confirming the import.
 */
class ImportGameCompletionDecisionTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

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
        val gameBlockId: EntityId,
        val taskBlockId: EntityId,
        val gameId: EntityId,
    )

    private suspend fun given(status: ImportBatchStatus = ImportBatchStatus.DRAFT): Fixture {
        val game = aGame(name = "Harmonies")
        database.gameDao().insert(game)
        val batch = anImportBatch(status = status, rawBlockCount = 2)
        val gameBlock =
            aRawImportBlock(
                batch.id,
                rawText = "Harmonies",
                rowIndex = 1,
                columnIndex = 0,
                sourceColumnType = SourceColumnType.GAME,
                fillColorArgb = 0xFF92D050.toInt(),
            )
        val taskBlock =
            aRawImportBlock(
                batch.id,
                rawText = "15 KIRMIZI",
                rowIndex = 1,
                columnIndex = 1,
                sourceColumnType = SourceColumnType.THREE_D,
            )
        importDao.insertBatch(batch)
        importDao.insertRawBlock(gameBlock)
        importDao.insertRawBlock(taskBlock)
        return Fixture(batch.id, gameBlock.id, taskBlock.id, game.id)
    }

    private suspend fun answer(
        blockId: EntityId,
        decision: HintDecision,
        targetGameId: EntityId? = null,
        clock: Clock = StoppedClock(moment),
    ): Boolean = importDao.setGameCompletionDecisionUnderReview(blockId, decision, targetGameId, clock)

    private suspend fun stored(blockId: EntityId) = assertNotNull(importDao.rawBlockById(blockId))

    @Test
    fun `an acceptance and the game it is about are stored together`() =
        runBlocking<Unit> {
            val fixture = given()

            assertTrue(answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId))

            val block = stored(fixture.gameBlockId)
            assertEquals(HintDecision.ACCEPTED, block.gameCompletionHint)
            assertEquals(fixture.gameId, block.completionTargetGameId)
            assertEquals(moment, block.updatedAt)
        }

    @Test
    fun `accepting without saying which game is refused, and nothing is written`() =
        runBlocking<Unit> {
            val fixture = given()

            val failure =
                assertFailsWith<ImportReviewException> { answer(fixture.gameBlockId, HintDecision.ACCEPTED, null) }

            assertEquals(ImportReviewFailure.COMPLETION_TARGET_REQUIRED, failure.failure)
            val block = stored(fixture.gameBlockId)
            assertEquals(HintDecision.NONE, block.gameCompletionHint)
            assertNull(block.completionTargetGameId)
        }

    @Test
    fun `a game that has been deleted cannot be what an acceptance is about`() =
        runBlocking<Unit> {
            val fixture = given()
            database.gameDao().softDelete(fixture.gameId, deletedAt)

            val failure =
                assertFailsWith<ImportReviewException> {
                    answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId)
                }

            assertEquals(ImportReviewFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE, failure.failure)
            assertEquals(HintDecision.NONE, stored(fixture.gameBlockId).gameCompletionHint)
        }

    @Test
    fun `a game that was never there cannot be what an acceptance is about`() =
        runBlocking<Unit> {
            val fixture = given()

            val failure =
                assertFailsWith<ImportReviewException> {
                    answer(fixture.gameBlockId, HintDecision.ACCEPTED, IdGenerator.Random.newId())
                }

            assertEquals(ImportReviewFailure.COMPLETION_TARGET_GAME_NOT_AVAILABLE, failure.failure)
            assertNull(stored(fixture.gameBlockId).completionTargetGameId)
        }

    @Test
    fun `taking an acceptance back clears the game in the same breath`() =
        runBlocking<Unit> {
            val fixture = given()
            answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId)

            assertTrue(answer(fixture.gameBlockId, HintDecision.REJECTED, null))

            val block = stored(fixture.gameBlockId)
            assertEquals(HintDecision.REJECTED, block.gameCompletionHint)
            assertNull(block.completionTargetGameId, "a rejected hint kept the game it used to be about")
        }

    @Test
    fun `going back to pending clears the game too`() =
        runBlocking<Unit> {
            val fixture = given()
            answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId)

            assertTrue(answer(fixture.gameBlockId, HintDecision.PENDING, null))

            assertNull(stored(fixture.gameBlockId).completionTargetGameId)
        }

    @Test
    fun `naming a game for anything but an acceptance is refused`() =
        runBlocking<Unit> {
            val fixture = given()

            listOf(HintDecision.PENDING, HintDecision.REJECTED, HintDecision.NONE).forEach { decision ->
                val failure =
                    assertFailsWith<ImportReviewException>("$decision should not take a game") {
                        answer(fixture.gameBlockId, decision, fixture.gameId)
                    }
                assertEquals(ImportReviewFailure.COMPLETION_TARGET_NOT_ALLOWED, failure.failure)
            }
            assertEquals(HintDecision.NONE, stored(fixture.gameBlockId).gameCompletionHint)
        }

    @Test
    fun `answering the same thing about the same game costs nothing, not even the clock`() =
        runBlocking<Unit> {
            val fixture = given()
            answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId)
            val before = stored(fixture.gameBlockId)

            val clock = CountingClock(Instant.fromEpochMilliseconds(1_790_000_000_000))
            assertFalse(answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId, clock))

            assertEquals(0, clock.reads, "a no-op read the clock")
            assertEquals(before, stored(fixture.gameBlockId), "a no-op moved a timestamp")
        }

    @Test
    fun `the same acceptance about a different game is a real change`() =
        runBlocking<Unit> {
            val fixture = given()
            answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId)
            val other = aGame(name = "Wingspan")
            database.gameDao().insert(other)

            assertTrue(answer(fixture.gameBlockId, HintDecision.ACCEPTED, other.id))

            assertEquals(other.id, stored(fixture.gameBlockId).completionTargetGameId)
        }

    @Test
    fun `only the game name column can carry this answer`() =
        runBlocking<Unit> {
            val fixture = given()

            val failure =
                assertFailsWith<ImportReviewException> {
                    answer(fixture.taskBlockId, HintDecision.ACCEPTED, fixture.gameId)
                }

            assertEquals(ImportReviewFailure.BLOCK_CANNOT_CARRY_GAME_COMPLETION, failure.failure)
            assertEquals(HintDecision.NONE, stored(fixture.taskBlockId).gameCompletionHint)
        }

    @Test
    fun `a confirmed import will not take an answer, and keeps the one it had`() =
        runBlocking<Unit> {
            val fixture = given(status = ImportBatchStatus.CONFIRMED)

            val failure =
                assertFailsWith<ImportReviewException> {
                    answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId)
                }

            assertEquals(ImportReviewFailure.BATCH_NOT_A_DRAFT, failure.failure)
            assertEquals(HintDecision.NONE, stored(fixture.gameBlockId).gameCompletionHint)
        }

    @Test
    fun `a cell that is not there is named as such`() =
        runBlocking<Unit> {
            given()
            val failure =
                assertFailsWith<ImportReviewException> {
                    answer(IdGenerator.Random.newId(), HintDecision.PENDING, null)
                }
            assertEquals(ImportReviewFailure.RAW_BLOCK_NOT_FOUND, failure.failure)
        }

    @Test
    fun `storing the answer does not finish the game`() =
        runBlocking<Unit> {
            val fixture = given()

            answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId)

            val game = assertNotNull(database.gameDao().activeGameById(fixture.gameId))
            assertFalse(game.isManuallyCompleted, "storing a hint answer finished the game")
            assertNull(game.completedAt)
        }

    @Test
    fun `the answer is still there when the database is opened again`() =
        runBlocking<Unit> {
            val fixture = given()
            answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId)
            database.close()

            database = DatabaseFactory().open(directory.databaseFile)

            val block = assertNotNull(database.importDao().rawBlockById(fixture.gameBlockId))
            assertEquals(HintDecision.ACCEPTED, block.gameCompletionHint)
            assertEquals(fixture.gameId, block.completionTargetGameId)
        }

    @Test
    fun `a game an answer names cannot be removed out from under it`() =
        runBlocking<Unit> {
            val fixture = given()
            answer(fixture.gameBlockId, HintDecision.ACCEPTED, fixture.gameId)

            assertFailsWith<Throwable> {
                database.useWriterConnection { transactor ->
                    transactor.usePrepared("DELETE FROM games WHERE id = ?") { statement ->
                        statement.bindText(1, fixture.gameId.toString())
                        statement.step()
                    }
                }
            }

            assertNotNull(database.gameDao().activeGameById(fixture.gameId), "the game went anyway")
            assertEquals(fixture.gameId, stored(fixture.gameBlockId).completionTargetGameId)
        }

    @Test
    fun `an entity that names a game for an unanswered hint cannot be built at all`() =
        runBlocking<Unit> {
            val fixture = given()
            val block = stored(fixture.gameBlockId)

            assertFailsWith<IllegalArgumentException> {
                block.copy(gameCompletionHint = HintDecision.PENDING, completionTargetGameId = fixture.gameId)
            }
            // The other direction is deliberately allowed: version 5 could
            // record an acceptance with nowhere to say what it was about, and
            // such a row has to stay readable.
            block.copy(gameCompletionHint = HintDecision.ACCEPTED, completionTargetGameId = null)
        }
}
