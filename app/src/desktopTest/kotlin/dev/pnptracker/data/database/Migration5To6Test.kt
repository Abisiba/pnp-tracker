package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.ProgressEventKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The step that makes room for what an import review decides.
 *
 * It adds and rewrites nothing: a table for the colours a draft is given, four
 * columns for the flags a task carries out of the source columns, and one column
 * for the game an accepted green cell was about. So the fixture below is a
 * database someone really used — four pools with their pipelines, a history, a
 * deleted task, a draft import and a confirmed one — and every test here asks
 * whether those rows still say exactly what they said.
 *
 * The row worth naming is the last one: a version 5 cell whose green hint was
 * already `ACCEPTED`. Version 5 had nowhere to record which game that meant, so
 * the row arrives with an answer and no target. It is left alone, because
 * turning it back into a question would throw away something the user said and
 * filling it in would put a game into their answer that they never picked.
 */
class Migration5To6Test {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExistedBefore = false

    private val gameId = IdGenerator.Random.newId()
    private val otherGameId = IdGenerator.Random.newId()
    private val threeDCellId = IdGenerator.Random.newId()
    private val cardCellId = IdGenerator.Random.newId()
    private val boardCellId = IdGenerator.Random.newId()
    private val notesCellId = IdGenerator.Random.newId()

    private val openTaskId = IdGenerator.Random.newId()
    private val finishedTaskId = IdGenerator.Random.newId()
    private val multiColorTaskId = IdGenerator.Random.newId()
    private val cardTaskId = IdGenerator.Random.newId()
    private val boardTaskId = IdGenerator.Random.newId()
    private val owingTaskId = IdGenerator.Random.newId()
    private val deletedTaskId = IdGenerator.Random.newId()
    private val importedTaskId = IdGenerator.Random.newId()

    private val plainTextSegmentId = IdGenerator.Random.newId()
    private val draftBatchId = IdGenerator.Random.newId()
    private val confirmedBatchId = IdGenerator.Random.newId()
    private val pendingBlockId = IdGenerator.Random.newId()
    private val acceptedBlockId = IdGenerator.Random.newId()
    private val confirmedBlockId = IdGenerator.Random.newId()
    private val draftId = IdGenerator.Random.newId()
    private val failureEventId = IdGenerator.Random.newId()
    private val resolvedEventId = IdGenerator.Random.newId()

    private val greyColorId = seedColors[2].id
    private val blackColorId = seedColors[1].id
    private val whiteColorId = seedColors[0].id

    @BeforeTest
    fun createTemporaryDirectory() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /** A version 5 database as somebody who had really been using it would leave it. */
    private fun createUsedVersion5Database() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 5) { connection ->
            insertSeedColors(connection)
            insertVersion2ColorAlias(connection, greyColorId, alias = "GRI TON", normalizedAlias = "gri ton")

            insertVersion4Game(connection, gameId)
            insertVersion4Game(connection, otherGameId, name = "Wingspan", isManuallyCompleted = true)
            insertVersion4GameCell(connection, threeDCellId, gameId, "THREE_D")
            insertVersion4GameCell(connection, cardCellId, gameId, "CARD")
            insertVersion4GameCell(connection, boardCellId, gameId, "BOARD")
            insertVersion4GameCell(connection, notesCellId, gameId, "NOTES")

            insertVersion5Task(connection, openTaskId, "THREE_D", "THREE_D_BATCH", "Gri token", 40)
            insertVersion5Task(
                connection,
                finishedTaskId,
                "THREE_D",
                "THREE_D_BATCH",
                "Bitmiş token",
                12,
                isCompleted = true,
                primaryBatchCompleted = true,
            )
            insertVersion5Task(connection, multiColorTaskId, "THREE_D", "THREE_D_BATCH", "Ev", 6)
            insertVersion5Task(connection, cardTaskId, "CARD", "PIPELINE", "Bird Cards", 170, notes = "iki grup")
            insertVersion5Task(connection, boardTaskId, "BOARD", "PIPELINE", "Plaj tile", 16)
            insertVersion5Task(
                connection,
                owingTaskId,
                "THREE_D",
                "THREE_D_BATCH",
                "Eksik çıkan token",
                20,
                primaryBatchCompleted = true,
                currentMissingQuantity = 3,
            )
            insertVersion5Task(connection, deletedTaskId, "THREE_D", "THREE_D_BATCH", "Silinmiş", 5, deleted = true)

            insertVersion5ImportBatch(connection, draftBatchId)
            insertVersion5ImportBatch(
                connection,
                confirmedBatchId,
                fileName = "Kitap2.xlsx",
                sha256 = "1".repeat(64),
                status = "CONFIRMED",
                createdTaskCount = 1,
            )
            insertVersion5RawImportBlock(
                connection,
                pendingBlockId,
                draftBatchId,
                rawText = "Harmonies",
                sourceColumnType = "GAME",
                gameCompletionHint = "PENDING",
                rowIndex = 1,
                columnIndex = 0,
                fillColorArgb = 0xFF92D050.toInt(),
            )
            // The legacy shape: answered yes, with nowhere to say about what.
            insertVersion5RawImportBlock(
                connection,
                acceptedBlockId,
                draftBatchId,
                rawText = "Wingspan",
                sourceColumnType = "GAME",
                gameCompletionHint = "ACCEPTED",
                rowIndex = 2,
                columnIndex = 0,
                fillColorArgb = 0xFF92D050.toInt(),
                isProcessed = true,
            )
            insertVersion5RawImportBlock(connection, confirmedBlockId, confirmedBatchId, rowIndex = 4, columnIndex = 2)
            insertVersion5Task(
                connection,
                importedTaskId,
                "CARD",
                "PIPELINE",
                "İçe aktarılmış deste",
                24,
                sourceRawImportBlockId = confirmedBlockId,
            )
            insertVersion4DraftTask(connection, draftId, pendingBlockId, targetCellId = cardCellId)

            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 0, openTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 1, finishedTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 2, multiColorTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 3, owingTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 4, deletedTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cardCellId, 0, cardTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cardCellId, 1, importedTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), boardCellId, 0, boardTaskId)
            insertVersion4PlainTextSegment(connection, plainTextSegmentId, notesCellId, 0, "Kutu ölçüsü 30×30")

            insertVersion4TaskColor(connection, openTaskId, greyColorId, 0)
            insertVersion4TaskColor(connection, multiColorTaskId, greyColorId, 0)
            insertVersion4TaskColor(connection, multiColorTaskId, blackColorId, 1)
            insertVersion4TaskColor(connection, multiColorTaskId, whiteColorId, 2)

            insertVersion5TaskStage(connection, cardTaskId, "PRINT", 0, 170)
            insertVersion5TaskStage(connection, cardTaskId, "LAMINATE", 1, 90)
            insertVersion5TaskStage(connection, cardTaskId, "CUT", 2, 0)
            insertVersion5TaskStage(connection, boardTaskId, "PRINT", 0, 16)
            insertVersion5TaskStage(connection, boardTaskId, "GLUE", 1, 4)
            insertVersion5TaskStage(connection, boardTaskId, "CUT", 2, 0)
            insertVersion5TaskStage(connection, importedTaskId, "PRINT", 0, 0)
            insertVersion5TaskStage(connection, importedTaskId, "LAMINATE", 1, 0)
            insertVersion5TaskStage(connection, importedTaskId, "CUT", 2, 0)

            insertVersion5ProgressEvent(connection, failureEventId, owingTaskId, "FAILURE_REPORTED", 5, "yamuk çıktı")
            insertVersion5ProgressEvent(connection, resolvedEventId, owingTaskId, "SHORTAGE_RESOLVED", 2)
        }
    }

    private fun version() = CommittedSchema.readVersion(directory.databaseFile)

    private fun rows(table: String) = CommittedSchema.countRowsOf(directory.databaseFile, table)

    private fun <T> withMigratedDatabase(body: suspend (AppDatabase) -> T): T {
        createUsedVersion5Database()
        val database = DatabaseFactory().open(directory.databaseFile)
        return try {
            runBlocking { body(database) }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a used version 5 database reaches version 6 with every row still there`() {
        createUsedVersion5Database()
        assertEquals(5L, version(), "the fixture is not a version 5 database")

        val database = DatabaseFactory().open(directory.databaseFile)
        try {
            runBlocking { database.colorDao().allColors() }
        } finally {
            database.close()
        }

        assertEquals(6L, version())
        assertEquals(2, rows("games"))
        assertEquals(4, rows("game_cells"))
        assertEquals(9, rows("cell_segments"))
        assertEquals(8, rows("tasks"), "a task was dropped by the migration")
        assertEquals(4, rows("task_colors"))
        assertEquals(9, rows("task_stages"))
        assertEquals(2, rows("progress_events"))
        assertEquals(12, rows("colors"))
        assertEquals(1, rows("color_aliases"))
        assertEquals(2, rows("import_batches"))
        assertEquals(3, rows("raw_import_blocks"))
        assertEquals(1, rows("draft_tasks"))
        // The new table starts empty, which is the truth: version 5 recorded no
        // colour for any draft, so there is none to carry across and none to
        // make up.
        assertEquals(0, rows("draft_task_colors"))
    }

    @Test
    fun `an empty version 5 database walks up without inventing anything`() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 5) { connection ->
            insertSeedColors(connection)
        }
        val database = DatabaseFactory().open(directory.databaseFile)
        val colors =
            try {
                runBlocking { database.colorDao().allColors() }
            } finally {
                database.close()
            }

        assertEquals(6L, version())
        assertEquals(12, colors.size, "the seed colours were disturbed")
        assertEquals(0, rows("tasks"))
        assertEquals(0, rows("draft_task_colors"))
    }

    @Test
    fun `every task keeps what it said and takes the four new flags as false`() =
        withMigratedDatabase { database ->
            val open = assertNotNull(database.taskDao().activeTaskById(openTaskId))
            assertEquals("Gri token", open.name)
            assertEquals(40, open.requiredQuantity)
            assertEquals(createdAt, open.createdAt, "the migration moved a creation time")
            assertEquals(updatedAt, open.updatedAt, "the migration moved an update time")
            assertFalse(open.isCompleted)

            val finished = assertNotNull(database.taskDao().activeTaskById(finishedTaskId))
            assertTrue(finished.isCompleted, "a finished task came back unfinished")
            assertEquals(updatedAt, finished.completedAt)
            assertTrue(finished.primaryBatchCompleted)

            val owing = assertNotNull(database.taskDao().activeTaskById(owingTaskId))
            assertEquals(3, owing.currentMissingQuantity, "what a task owed was changed")
            assertTrue(owing.primaryBatchCompleted)

            assertEquals("iki grup", assertNotNull(database.taskDao().activeTaskById(cardTaskId)).notes)
            assertEquals(
                confirmedBlockId,
                assertNotNull(database.taskDao().activeTaskById(importedTaskId)).sourceRawImportBlockId,
                "a task lost the imported cell it came from",
            )

            // The new columns say what was already true: version 5 had nowhere
            // to record any of this, so no task carried it.
            listOf(openTaskId, finishedTaskId, cardTaskId, boardTaskId, owingTaskId, importedTaskId).forEach { id ->
                val task = assertNotNull(database.taskDao().activeTaskById(id))
                assertFalse(task.isMissing, "task $id came back missing")
                assertFalse(task.isBorrowed, "task $id came back borrowed")
                assertFalse(task.needsInfo, "task $id came back needing information")
                assertFalse(task.needsClassification, "task $id came back needing classification")
            }
        }

    @Test
    fun `the deleted task stays deleted and keeps its tombstone`() =
        withMigratedDatabase { database ->
            assertNull(database.taskDao().activeTaskById(deletedTaskId), "a deleted task came back to life")
            val row = assertNotNull(database.taskDao().taskByIdIncludingDeleted(deletedTaskId))
            assertEquals(deletedAt, row.deletedAt)
            assertEquals("Silinmiş", row.name)
            assertFalse(row.isMissing)
        }

    @Test
    fun `every pipeline comes across at exactly the count it was at`() =
        withMigratedDatabase { database ->
            assertEquals(
                listOf(ProductionStage.PRINT to 170, ProductionStage.LAMINATE to 90, ProductionStage.CUT to 0),
                database.taskProgressDao().stagesOfTask(cardTaskId).map { it.stage to it.completedQuantity },
            )
            assertEquals(
                listOf(ProductionStage.PRINT to 16, ProductionStage.GLUE to 4, ProductionStage.CUT to 0),
                database.taskProgressDao().stagesOfTask(boardTaskId).map { it.stage to it.completedQuantity },
            )
            assertEquals(emptyList(), database.taskProgressDao().stagesOfTask(openTaskId))
        }

    @Test
    fun `the history is carried across event for event`() =
        withMigratedDatabase { database ->
            val events = database.taskProgressDao().progressEventsOfTask(owingTaskId)
            assertEquals(
                listOf(
                    ProgressEventKind.FAILURE_REPORTED to 5,
                    ProgressEventKind.SHORTAGE_RESOLVED to 2,
                ),
                events.map { it.kind to it.quantity }.sortedBy { it.second }.reversed(),
            )
            assertEquals("yamuk çıktı", assertNotNull(events.firstOrNull { it.id == failureEventId }).note)
            assertEquals(5L, database.taskProgressDao().failureTotalOf(owingTaskId))
        }

    @Test
    fun `colours, aliases and the order they were picked in all survive`() =
        withMigratedDatabase { database ->
            assertEquals(
                seedColors.map { it.id to it.canonicalName },
                database.colorDao().allColors().map { it.id to it.canonicalName },
            )
            assertEquals(greyColorId, assertNotNull(database.colorDao().resolve("GRI TON")).id)
            assertEquals(
                listOf(greyColorId to 0, blackColorId to 1, whiteColorId to 2),
                database.taskColorDao().colorsOfTask(multiColorTaskId).map { it.colorId to it.slotIndex },
            )
        }

    @Test
    fun `every segment still names what it named, in the order it named it`() =
        withMigratedDatabase { database ->
            assertEquals(
                listOf(openTaskId, finishedTaskId, multiColorTaskId, owingTaskId, deletedTaskId),
                database.cellSegmentDao().segmentsOfCell(threeDCellId).map { it.taskId },
            )
            assertEquals(
                listOf(0, 1, 2, 3, 4),
                database.cellSegmentDao().segmentsOfCell(threeDCellId).map { it.orderIndex },
            )
            val plainText = database.cellSegmentDao().segmentsOfCell(notesCellId).single()
            assertEquals("Kutu ölçüsü 30×30", plainText.text)
            assertNull(plainText.taskId)
        }

    @Test
    fun `both imports and the draft are carried across untouched`() =
        withMigratedDatabase { database ->
            val draftBatch = assertNotNull(database.importDao().batchById(draftBatchId))
            assertEquals("Kitap1(1).xlsx", draftBatch.fileName)
            val confirmed = assertNotNull(database.importDao().batchById(confirmedBatchId))
            assertEquals(1, confirmed.createdTaskCount)

            val draft = assertNotNull(database.importDao().draftTaskById(draftId))
            assertEquals("Kırmızı token", draft.name)
            assertEquals(cardCellId, draft.targetCellId, "a draft lost the cell it was aimed at")
            assertEquals(15, draft.requiredQuantity)
            assertEquals("Sayısına bakılacak", draft.notes)
            assertEquals(3, draft.selectionStartIndex)
            assertEquals(10, draft.selectionEndIndex)
            assertTrue(draft.isMissing)
            assertTrue(draft.needsInfo)
            // Nothing was chosen for it in version 5, so nothing is claimed now.
            assertEquals(emptyList(), database.importDao().draftColorsOf(draftId))
        }

    @Test
    fun `a hint that was already accepted keeps its answer and gains no game`() =
        withMigratedDatabase { database ->
            val accepted = assertNotNull(database.importDao().rawBlockById(acceptedBlockId))
            assertEquals(HintDecision.ACCEPTED, accepted.gameCompletionHint, "an answer the user gave was rewritten")
            assertNull(accepted.completionTargetGameId, "the migration invented a game the user never picked")
            assertEquals("Wingspan", accepted.rawText)
            assertTrue(accepted.isProcessed)

            val pending = assertNotNull(database.importDao().rawBlockById(pendingBlockId))
            assertEquals(HintDecision.PENDING, pending.gameCompletionHint)
            assertNull(pending.completionTargetGameId)
        }

    @Test
    fun `the workspace shows a legacy acceptance as a decision still missing its game`() =
        withMigratedDatabase { database ->
            val blocks = database.importDao().rawBlocksOfBatch(draftBatchId)
            val accepted = assertNotNull(blocks.firstOrNull { it.id == acceptedBlockId })
            // What 18B will refuse, and what the review screen can ask about.
            assertEquals(HintDecision.ACCEPTED, accepted.gameCompletionHint)
            assertNull(accepted.completionTargetGameId)
        }

    @Test
    fun `neither game was finished or reopened by the walk`() =
        withMigratedDatabase { database ->
            assertFalse(assertNotNull(database.gameDao().activeGameById(gameId)).isManuallyCompleted)
            assertTrue(
                assertNotNull(database.gameDao().activeGameById(otherGameId)).isManuallyCompleted,
                "a finished game was reopened by the migration",
            )
        }

    @Test
    fun `the migrated database is sound and leaves no foreign key dangling`() =
        withMigratedDatabase { database ->
            assertEquals(emptyList(), soundnessProblemsOf(database))
        }

    @Test
    fun `a version 1 database can be walked all the way to version 6`() =
        runBlocking<Unit> {
            // Version 1 had no colour catalogue at all, so there is nothing to
            // seed by hand: the walk brings the table and the callback fills it.
            CommittedSchema.createDatabase(directory.databaseFile, version = 1) { }
            assertEquals(1L, version())

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(12, database.colorDao().allColors().size)
                assertEquals(emptyList(), soundnessProblemsOf(database))
                // The two things version 6 adds are there and empty, which is
                // what a database that never held an import review means.
                assertEquals(emptyList(), database.importDao().draftColorsOfBatch(IdGenerator.Random.newId()))
            } finally {
                database.close()
            }
            assertEquals(6L, version())
            assertEquals(0, rows("draft_task_colors"))
        }

    private suspend fun soundnessProblemsOf(database: AppDatabase): List<String> =
        database.useReaderConnection { transactor ->
            transactor.usePrepared("PRAGMA foreign_key_check") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            } +
                transactor
                    .usePrepared("PRAGMA integrity_check") { statement ->
                        buildList { while (statement.step()) add(statement.getText(0)) }
                    }.filterNot { it == "ok" }
        }

    @Test
    fun `foreign keys are on after the walk, so the new links are enforced`() =
        withMigratedDatabase { database ->
            val enabled =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared("PRAGMA foreign_keys") { statement ->
                        statement.step()
                        statement.getLong(0)
                    }
                }
            assertEquals(1L, enabled, "foreign keys are off, so nothing holds the new links")
        }

    @Test
    fun `a task can be given the new flags once the walk is done`() =
        withMigratedDatabase { database ->
            val cellId: EntityId = threeDCellId
            val task =
                aTask(poolType = dev.pnptracker.domain.model.PoolType.THREE_D, name = "Ödünç zar")
                    .copy(isBorrowed = true, needsClassification = true)
            database.taskDao().addTaskToCell(task, cellId, IdGenerator.Random.newId(), createdAt)

            val stored = assertNotNull(database.taskDao().activeTaskById(task.id))
            assertTrue(stored.isBorrowed)
            assertTrue(stored.needsClassification)
            assertFalse(stored.isMissing)
            assertFalse(stored.needsInfo)
        }
}
