package dev.pnptracker.data.database

import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importreview.ImportReviewException
import dev.pnptracker.domain.importreview.ImportReviewFailure
import dev.pnptracker.domain.model.EntityId
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The colours a user picks while they are still drafting an import.
 *
 * These are the decisions PLAN 11.4 asks for — "kesin rengi veya renkleri seç,
 * ya da görevi renksiz bırak" — kept where they survive the review being closed
 * and opened again (PLAN 11.4.3). They are not the production `task_colors`:
 * that table holds what a real task is made in, and a draft may never become
 * one.
 *
 * What is worth proving here cannot be shown against a stand in. That a refused
 * colour leaves the old list whole, that a colour still in use cannot be deleted
 * out from under a draft, and that discarding an import takes its choices with
 * it are all things only a real database with its real keys can answer.
 */
class DraftTaskColorTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_780_000_000_000)

    /** A clock that says how often it was asked, so a no-op can be shown to cost nothing. */
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
        val draftId: EntityId,
    )

    private suspend fun given(status: ImportBatchStatus = ImportBatchStatus.DRAFT): Fixture {
        val batch = anImportBatch(status = status, rawBlockCount = 1)
        val block = aRawImportBlock(batch.id, rawText = "15 KIRMIZI, 19 YEŞİL")
        importDao.insertBatch(batch)
        importDao.insertRawBlock(block)
        val draft = aDraftTask(block.id, name = "Kırmızı ev")
        // Written straight in, so a confirmed batch can be given a draft the
        // guarded path would rightly refuse to add.
        importDao.addDraftTask(draft)
        return Fixture(batch.id, block.id, draft.id)
    }

    private suspend fun colorId(name: String): EntityId = assertNotNull(database.colorDao().resolve(name)).id

    private suspend fun chosen(draftId: EntityId): List<Pair<EntityId, Int>> =
        importDao.draftColorsOf(draftId).map { it.colorId to it.slotIndex }

    private suspend fun set(
        draftId: EntityId,
        colorIds: List<EntityId>,
        clock: Clock = StoppedClock(moment),
    ): Boolean = importDao.setDraftColorsUnderReview(draftId, colorIds, clock)

    @Test
    fun `a draft with no colours at all is a real answer`() =
        runBlocking<Unit> {
            val fixture = given()

            assertEquals(emptyList(), chosen(fixture.draftId))
            assertFalse(set(fixture.draftId, emptyList()), "choosing nothing where there was nothing changed something")
            assertEquals(emptyList(), chosen(fixture.draftId))
        }

    @Test
    fun `one colour takes the first slot`() =
        runBlocking<Unit> {
            val fixture = given()
            val grey = colorId("Gri")

            assertTrue(set(fixture.draftId, listOf(grey)))

            assertEquals(listOf(grey to 0), chosen(fixture.draftId))
        }

    @Test
    fun `three colours are numbered nought one two, in the order they were chosen`() =
        runBlocking<Unit> {
            val fixture = given()
            val colors = listOf("Gri", "Mavi", "Yeşil").map { colorId(it) }

            assertTrue(set(fixture.draftId, colors))

            assertEquals(colors.mapIndexed { slot, id -> id to slot }, chosen(fixture.draftId))
        }

    @Test
    fun `changing only the order is a real change and is kept`() =
        runBlocking<Unit> {
            val fixture = given()
            val colors = listOf("Gri", "Mavi", "Yeşil").map { colorId(it) }
            set(fixture.draftId, colors)

            val reversed = colors.reversed()
            assertTrue(set(fixture.draftId, reversed), "a reordering was taken for no change at all")

            assertEquals(reversed.mapIndexed { slot, id -> id to slot }, chosen(fixture.draftId))
        }

    @Test
    fun `the same colour twice is refused before anything is written`() =
        runBlocking<Unit> {
            val fixture = given()
            val grey = colorId("Gri")
            val blue = colorId("Mavi")

            val failure =
                assertFailsWith<ImportReviewException> { set(fixture.draftId, listOf(grey, blue, grey)) }

            assertEquals(ImportReviewFailure.DUPLICATE_COLOR, failure.failure)
            assertEquals(emptyList(), chosen(fixture.draftId), "a refused list left rows behind")
        }

    @Test
    fun `a duplicate leaves the list the draft already had exactly as it was`() =
        runBlocking<Unit> {
            val fixture = given()
            val colors = listOf("Gri", "Mavi").map { colorId(it) }
            set(fixture.draftId, colors)
            val yellow = colorId("Sarı")

            assertFailsWith<ImportReviewException> { set(fixture.draftId, listOf(yellow, yellow)) }

            assertEquals(colors.mapIndexed { slot, id -> id to slot }, chosen(fixture.draftId))
        }

    @Test
    fun `a colour that has been deleted takes the whole change back with it`() =
        runBlocking<Unit> {
            val fixture = given()
            val kept = listOf("Gri", "Mavi").map { colorId(it) }
            set(fixture.draftId, kept)
            val doomed = colorId("Turuncu")
            database.colorDao().deleteColorTheUserHasConfirmed(doomed)
            val yellow = colorId("Sarı")

            val failure =
                assertFailsWith<ImportReviewException> { set(fixture.draftId, listOf(yellow, doomed)) }

            assertEquals(ImportReviewFailure.COLOR_NOT_AVAILABLE, failure.failure)
            assertEquals(
                kept.mapIndexed { slot, id -> id to slot },
                chosen(fixture.draftId),
                "half of a refused list was written",
            )
        }

    @Test
    fun `a shorter list leaves no slot behind and no gap in the order`() =
        runBlocking<Unit> {
            val fixture = given()
            val colors = listOf("Gri", "Mavi", "Yeşil", "Sarı").map { colorId(it) }
            set(fixture.draftId, colors)

            val fewer = listOf(colors[3], colors[1])
            assertTrue(set(fixture.draftId, fewer))

            assertEquals(listOf(fewer[0] to 0, fewer[1] to 1), chosen(fixture.draftId))
            assertEquals(listOf(0, 1), importDao.draftColorsOf(fixture.draftId).map { it.slotIndex })
        }

    @Test
    fun `a draft's colours are written to its own table and never to a task's`() =
        runBlocking<Unit> {
            val fixture = given()
            val grey = colorId("Gri")

            set(fixture.draftId, listOf(grey))

            assertEquals(1, CommittedSchema.countRowsOf(directory.databaseFile, "draft_task_colors"))
            assertEquals(
                0,
                CommittedSchema.countRowsOf(directory.databaseFile, "task_colors"),
                "drafting a colour reached the production table",
            )
        }

    @Test
    fun `choosing the list that is already there costs nothing, not even the clock`() =
        runBlocking<Unit> {
            val fixture = given()
            val colors = listOf("Gri", "Mavi").map { colorId(it) }
            set(fixture.draftId, colors)
            val before = assertNotNull(importDao.draftTaskById(fixture.draftId))

            val clock = CountingClock(Instant.fromEpochMilliseconds(1_790_000_000_000))
            assertFalse(set(fixture.draftId, colors, clock))

            assertEquals(0, clock.reads, "a no-op read the clock")
            assertEquals(before, assertNotNull(importDao.draftTaskById(fixture.draftId)), "a no-op moved a timestamp")
            assertEquals(colors.mapIndexed { slot, id -> id to slot }, chosen(fixture.draftId))
        }

    @Test
    fun `a real change moves the draft's own update time`() =
        runBlocking<Unit> {
            val fixture = given()
            val grey = colorId("Gri")

            set(fixture.draftId, listOf(grey), StoppedClock(moment))

            assertEquals(moment, assertNotNull(importDao.draftTaskById(fixture.draftId)).updatedAt)
        }

    @Test
    fun `a confirmed import will not take a colour, and keeps the ones it had`() =
        runBlocking<Unit> {
            val fixture = given(status = ImportBatchStatus.CONFIRMED)
            val grey = colorId("Gri")

            val failure = assertFailsWith<ImportReviewException> { set(fixture.draftId, listOf(grey)) }

            assertEquals(ImportReviewFailure.BATCH_NOT_A_DRAFT, failure.failure)
            assertEquals(emptyList(), chosen(fixture.draftId))
        }

    @Test
    fun `a draft that is not there is named as such rather than quietly ignored`() =
        runBlocking<Unit> {
            given()
            val failure =
                assertFailsWith<ImportReviewException> {
                    set(
                        dev.pnptracker.domain.model.IdGenerator.Random
                            .newId(),
                        emptyList(),
                    )
                }
            assertEquals(ImportReviewFailure.DRAFT_TASK_NOT_FOUND, failure.failure)
        }

    @Test
    fun `removing an import takes the colours of its drafts with it`() =
        runBlocking<Unit> {
            val fixture = given()
            set(fixture.draftId, listOf("Gri", "Mavi").map { colorId(it) })
            assertEquals(2, CommittedSchema.countRowsOf(directory.databaseFile, "draft_task_colors"))

            assertIs<DraftRemovalOutcome.Removed>(importDao.removeDraftBatch(fixture.batchId))

            assertEquals(0, CommittedSchema.countRowsOf(directory.databaseFile, "draft_tasks"))
            assertEquals(
                0,
                CommittedSchema.countRowsOf(directory.databaseFile, "draft_task_colors"),
                "a discarded import left its colour choices behind",
            )
            assertEquals(12, database.colorDao().allColors().size, "discarding an import removed a colour")
        }

    @Test
    fun `a colour a draft is holding cannot be deleted out from under it`() =
        runBlocking<Unit> {
            val fixture = given()
            val grey = colorId("Gri")
            set(fixture.draftId, listOf(grey))

            assertFailsWith<Throwable> { database.colorDao().deleteColorTheUserHasConfirmed(grey) }

            assertEquals(12, database.colorDao().allColors().size, "the colour went anyway")
            assertEquals(listOf(grey to 0), chosen(fixture.draftId), "the draft lost a colour it had chosen")
        }

    @Test
    fun `the choices are still there, in order, when the database is opened again`() =
        runBlocking<Unit> {
            val fixture = given()
            val colors = listOf("Yeşil", "Gri", "Mavi").map { colorId(it) }
            set(fixture.draftId, colors)
            database.close()

            database = DatabaseFactory().open(directory.databaseFile)

            assertEquals(
                colors.mapIndexed { slot, id -> id to slot },
                database.importDao().draftColorsOf(fixture.draftId).map { it.colorId to it.slotIndex },
                "the order the user chose was not what came back",
            )
        }

    @Test
    fun `the whole import's choices come back grouped and in slot order`() =
        runBlocking<Unit> {
            val batch = anImportBatch(rawBlockCount = 2)
            importDao.insertBatch(batch)
            val first = aRawImportBlock(batch.id, rowIndex = 1, columnIndex = 1, sourceColumnType = SourceColumnType.THREE_D)
            val second = aRawImportBlock(batch.id, rowIndex = 2, columnIndex = 1, sourceColumnType = SourceColumnType.THREE_D)
            importDao.insertRawBlock(first)
            importDao.insertRawBlock(second)
            val one = aDraftTask(first.id, name = "Ev")
            val two = aDraftTask(second.id, name = "Ağaç")
            importDao.addDraftTask(one)
            importDao.addDraftTask(two)
            val forOne = listOf("Yeşil", "Gri").map { colorId(it) }
            val forTwo = listOf("Mavi").map { colorId(it) }
            set(one.id, forOne)
            set(two.id, forTwo)

            val all = importDao.draftColorsOfBatch(batch.id)

            val grouped = all.groupBy { it.draftTaskId }.mapValues { (_, rows) -> rows.map { it.colorId } }
            assertEquals(forOne, grouped[one.id], "one draft's colours came back in the wrong order")
            assertEquals(forTwo, grouped[two.id])
            assertTrue(
                all.groupBy { it.draftTaskId }.values.all { rows -> rows.map { it.slotIndex } == rows.indices.toList() },
                "the rows did not arrive in slot order",
            )
        }
}
