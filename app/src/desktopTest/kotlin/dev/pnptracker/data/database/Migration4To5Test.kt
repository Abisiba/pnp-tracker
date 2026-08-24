package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
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
 * The step that adds progress to the table model.
 *
 * Unlike the walk to version 4, this one keeps everything. Nothing it adds has
 * to be invented: a task nobody marked finished is unfinished, a print run
 * nobody recorded has not been made, and a task nobody reported a shortage on
 * owes nothing. So the fixture below is a database someone really used — four
 * pools, colours, segments, an import and a deleted task — and the tests check
 * that every one of those rows is still saying what it said before.
 */
class Migration4To5Test {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExistedBefore = false

    private val gameId = IdGenerator.Random.newId()
    private val threeDCellId = IdGenerator.Random.newId()
    private val cardCellId = IdGenerator.Random.newId()
    private val boardCellId = IdGenerator.Random.newId()
    private val specialCellId = IdGenerator.Random.newId()
    private val notesCellId = IdGenerator.Random.newId()

    private val threeDTaskId = IdGenerator.Random.newId()
    private val cardTaskId = IdGenerator.Random.newId()
    private val boardTaskId = IdGenerator.Random.newId()
    private val specialTaskId = IdGenerator.Random.newId()
    private val deletedTaskId = IdGenerator.Random.newId()
    private val deletedCardTaskId = IdGenerator.Random.newId()
    private val deletedBoardTaskId = IdGenerator.Random.newId()

    private val plainTextSegmentId = IdGenerator.Random.newId()
    private val batchId = IdGenerator.Random.newId()
    private val blockId = IdGenerator.Random.newId()
    private val draftId = IdGenerator.Random.newId()

    private val greyColorId = seedColors[2].id
    private val blackColorId = seedColors[1].id

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

    /** A version 4 database as someone who had really been using it would leave it. */
    private fun createUsedVersion4Database() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 4) { connection ->
            insertSeedColors(connection)
            insertVersion2ColorAlias(connection, greyColorId, alias = "GRI TON", normalizedAlias = "gri ton")

            insertVersion4Game(connection, gameId)
            insertVersion4GameCell(connection, threeDCellId, gameId, "THREE_D")
            insertVersion4GameCell(connection, cardCellId, gameId, "CARD")
            insertVersion4GameCell(connection, boardCellId, gameId, "BOARD")
            insertVersion4GameCell(connection, specialCellId, gameId, "SPECIAL")
            insertVersion4GameCell(connection, notesCellId, gameId, "NOTES")

            insertVersion4Task(connection, threeDTaskId, "THREE_D", "THREE_D_BATCH", "Gri token", 40)
            insertVersion4Task(connection, cardTaskId, "CARD", "PIPELINE", "Bird Cards", 170, notes = "iki grup")
            insertVersion4Task(connection, boardTaskId, "BOARD", "PIPELINE", "Plaj tile", 16)
            insertVersion4Task(connection, specialTaskId, "SPECIAL", "COUNTED", "Özel zar", 8)
            insertVersion4Task(connection, deletedTaskId, "THREE_D", "THREE_D_BATCH", "Silinmiş", 5, deleted = true)
            // Deleted work in the two pools that *do* have a pipeline, so what
            // the migration does with them is pinned rather than left to the 3D
            // row above, which would pass for having no pipeline at all.
            insertVersion4Task(
                connection,
                deletedCardTaskId,
                "CARD",
                "PIPELINE",
                "Silinmiş kart",
                12,
                deleted = true,
            )
            insertVersion4Task(
                connection,
                deletedBoardTaskId,
                "BOARD",
                "PIPELINE",
                "Silinmiş tile",
                9,
                deleted = true,
            )

            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 0, threeDTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cardCellId, 0, cardTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), boardCellId, 0, boardTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), specialCellId, 0, specialTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 1, deletedTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cardCellId, 1, deletedCardTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), boardCellId, 1, deletedBoardTaskId)
            insertVersion4PlainTextSegment(connection, plainTextSegmentId, notesCellId, 0, "Kutu ölçüsü 30×30")

            // One task with a single colour, one with two in a fixed order.
            insertVersion4TaskColor(connection, threeDTaskId, greyColorId, 0)
            insertVersion4TaskColor(connection, boardTaskId, greyColorId, 0)
            insertVersion4TaskColor(connection, boardTaskId, blackColorId, 1)

            insertVersion3ImportBatch(connection, batchId)
            insertVersion3RawImportBlock(connection, blockId, batchId)
            insertVersion4DraftTask(connection, draftId, blockId, targetCellId = cardCellId)
        }
    }

    private fun version() = CommittedSchema.readVersion(directory.databaseFile)

    private fun rows(table: String) = CommittedSchema.countRowsOf(directory.databaseFile, table)

    private fun <T> withMigratedDatabase(body: suspend (AppDatabase) -> T): T {
        createUsedVersion4Database()
        val database = DatabaseFactory().open(directory.databaseFile)
        return try {
            runBlocking { body(database) }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a used version 4 database reaches version 5 with every row still there`() {
        createUsedVersion4Database()
        assertEquals(4L, version(), "the fixture is not a version 4 database")

        val database = DatabaseFactory().open(directory.databaseFile)
        try {
            runBlocking { database.colorDao().allColors() }
        } finally {
            database.close()
        }

        assertEquals(5L, version())
        assertEquals(1, rows("games"))
        assertEquals(5, rows("game_cells"))
        assertEquals(8, rows("cell_segments"))
        assertEquals(7, rows("tasks"), "a task was dropped by the migration")
        assertEquals(3, rows("task_colors"))
        assertEquals(12, rows("colors"))
        assertEquals(1, rows("color_aliases"))
        assertEquals(1, rows("import_batches"))
        assertEquals(1, rows("raw_import_blocks"))
        assertEquals(1, rows("draft_tasks"))
    }

    @Test
    fun `every task keeps what it said and takes the new defaults`() =
        withMigratedDatabase { database ->
            val expected =
                mapOf(
                    threeDTaskId to Triple(PoolType.THREE_D, "Gri token", 40),
                    cardTaskId to Triple(PoolType.CARD, "Bird Cards", 170),
                    boardTaskId to Triple(PoolType.BOARD, "Plaj tile", 16),
                    specialTaskId to Triple(PoolType.SPECIAL, "Özel zar", 8),
                )
            expected.forEach { (taskId, wanted) ->
                val task = assertNotNull(database.taskDao().activeTaskById(taskId), "task $taskId is gone")
                val (poolType, name, quantity) = wanted
                assertEquals(poolType, task.poolType)
                assertEquals(name, task.name)
                assertEquals(quantity, task.requiredQuantity)
                assertEquals(createdAt, task.createdAt, "the migration moved a creation time")
                assertEquals(updatedAt, task.updatedAt, "the migration moved an update time")
                assertNull(task.deletedAt)

                // The new columns say what was already true of the old row.
                assertFalse(task.isCompleted, "an existing task came back finished")
                assertNull(task.completedAt)
                assertFalse(task.primaryBatchCompleted)
                assertEquals(0, task.currentMissingQuantity)
            }
            assertEquals("iki grup", assertNotNull(database.taskDao().activeTaskById(cardTaskId)).notes)
        }

    @Test
    fun `the deleted task stays deleted and keeps its tombstone`() =
        withMigratedDatabase { database ->
            assertNull(database.taskDao().activeTaskById(deletedTaskId), "a deleted task came back to life")
            val row = assertNotNull(database.taskDao().taskByIdIncludingDeleted(deletedTaskId))
            assertEquals(deletedAt, row.deletedAt)
            assertEquals("Silinmiş", row.name)
            assertFalse(row.isCompleted)
        }

    @Test
    fun `card and board tasks come out of the migration with their pipelines`() =
        withMigratedDatabase { database ->
            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
                database.taskProgressDao().stagesOfTask(cardTaskId).map { it.stage },
            )
            assertEquals(
                listOf(ProductionStage.PRINT, ProductionStage.GLUE, ProductionStage.CUT),
                database.taskProgressDao().stagesOfTask(boardTaskId).map { it.stage },
            )
            // Nothing has been made yet, which is what the old rows meant.
            database.taskProgressDao().stagesOfTask(cardTaskId).forEachIndexed { index, stage ->
                assertEquals(0, stage.completedQuantity)
                assertEquals(index, stage.orderIndex)
            }
        }

    @Test
    fun `3D and special tasks are given no stages at all`() =
        withMigratedDatabase { database ->
            assertEquals(emptyList(), database.taskProgressDao().stagesOfTask(threeDTaskId))
            assertEquals(emptyList(), database.taskProgressDao().stagesOfTask(specialTaskId))
            // Including the deleted one: a pool with no pipeline never gets rows.
            assertEquals(emptyList(), database.taskProgressDao().stagesOfTask(deletedTaskId))
            assertEquals(12, rows("task_stages"), "a stage row was written for a pool with no pipeline")
        }

    @Test
    fun `the history starts empty, because version 4 recorded none`() =
        withMigratedDatabase { database ->
            // Read through Room before counting on a connection of our own: Room
            // opens the file lazily, so the migration has not run until it does.
            assertEquals(emptyList(), database.taskProgressDao().progressEventsOfTask(threeDTaskId))
            assertEquals(0, database.taskProgressDao().failureTotalOf(threeDTaskId))
            assertEquals(0, rows("progress_events"))
        }

    @Test
    fun `every segment still names what it named`() =
        withMigratedDatabase { database ->
            assertEquals(
                listOf(threeDTaskId, deletedTaskId),
                database.cellSegmentDao().segmentsOfCell(threeDCellId).map { it.taskId },
            )
            assertEquals(
                cardCellId,
                assertNotNull(database.cellSegmentDao().segmentOfTaskIncludingDeleted(cardTaskId)).cellId,
            )
            val plainText = database.cellSegmentDao().segmentsOfCell(notesCellId).single()
            assertEquals("Kutu ölçüsü 30×30", plainText.text)
            assertNull(plainText.taskId)
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
                listOf(greyColorId to 0, blackColorId to 1),
                database.taskColorDao().colorsOfTask(boardTaskId).map { it.colorId to it.slotIndex },
            )
            assertEquals(
                listOf(greyColorId to 0),
                database.taskColorDao().colorsOfTask(threeDTaskId).map { it.colorId to it.slotIndex },
            )
        }

    @Test
    fun `the import and its draft are carried across untouched`() =
        withMigratedDatabase { database ->
            val batch = assertNotNull(database.importDao().batchById(batchId))
            assertEquals("Kitap1(1).xlsx", batch.fileName)

            val block = assertNotNull(database.importDao().rawBlockById(blockId))
            assertEquals("15 KIRMIZI**\nBıçak ve kabza ayrı", block.rawText)

            val draft = assertNotNull(database.importDao().draftTaskById(draftId))
            assertEquals("Kırmızı token", draft.name)
            assertEquals(cardCellId, draft.targetCellId, "a draft lost the cell it was aimed at")
            assertEquals(15, draft.requiredQuantity)
            assertEquals("Sayısına bakılacak", draft.notes)
        }

    @Test
    fun `the migrated database is sound and leaves no foreign key dangling`() =
        withMigratedDatabase { database ->
            val problems =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared("PRAGMA foreign_key_check") { statement ->
                        buildList { while (statement.step()) add(statement.getText(0)) }
                    } +
                        transactor
                            .usePrepared("PRAGMA integrity_check") { statement ->
                                buildList { while (statement.step()) add(statement.getText(0)) }
                            }.filterNot { it == "ok" }
                }
            assertEquals(emptyList(), problems)
        }

    @Test
    fun `a database created straight at version 5 opens and reopens`() =
        runBlocking<Unit> {
            val first = DatabaseFactory().open(directory.databaseFile)
            val seeded =
                try {
                    first.colorDao().allColors()
                } finally {
                    first.close()
                }
            assertEquals(12, seeded.size)
            assertEquals(5L, version())

            val second = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(seeded, second.colorDao().allColors())
                assertEquals(emptyList(), second.taskDao().activeTasks())
            } finally {
                second.close()
            }
            assertEquals(5L, version())
        }

    @Test
    fun `an empty version 4 database walks up without inventing anything`() =
        runBlocking<Unit> {
            CommittedSchema.createDatabase(directory.databaseFile, version = 4) { connection ->
                insertSeedColors(connection)
            }

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(12, database.colorDao().allColors().size)
                assertEquals(emptyList(), database.taskDao().activeTasks())
            } finally {
                database.close()
            }

            assertEquals(5L, version())
            assertEquals(0, rows("task_stages"))
            assertEquals(0, rows("progress_events"))
        }

    @Test
    fun `a deleted card or board task keeps its pipeline and stays deleted`() =
        withMigratedDatabase { database ->
            // Deleting a task is soft, so its rows stay exactly as they were and
            // it gets the same pipeline a live one does. That is what a task
            // deleted *after* version 5 looks like too, so the migration and the
            // live path describe the same thing.
            listOf(
                deletedCardTaskId to listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
                deletedBoardTaskId to listOf(ProductionStage.PRINT, ProductionStage.GLUE, ProductionStage.CUT),
            ).forEach { (taskId, pipeline) ->
                val row = assertNotNull(database.taskDao().taskByIdIncludingDeleted(taskId))
                assertEquals(deletedAt, row.deletedAt, "a deleted task lost its tombstone")
                assertFalse(row.isCompleted)
                assertNull(row.completedAt)
                assertFalse(row.primaryBatchCompleted)
                assertEquals(0, row.currentMissingQuantity)

                val stages = database.taskProgressDao().stagesOfTask(taskId)
                assertEquals(pipeline, stages.map { it.stage })
                assertEquals(listOf(0, 0, 0), stages.map { it.completedQuantity })
                assertEquals(listOf(0, 1, 2), stages.map { it.orderIndex })
                // The same timestamps a live task's stages get: the task's own.
                assertEquals(listOf(createdAt, createdAt, createdAt), stages.map { it.createdAt })

                assertEquals(emptyList(), database.taskProgressDao().progressEventsOfTask(taskId))
            }
        }

    @Test
    fun `deleted card and board tasks stay out of every active view`() =
        withMigratedDatabase { database ->
            assertNull(database.taskDao().activeTaskById(deletedCardTaskId))
            assertNull(database.taskDao().activeTaskById(deletedBoardTaskId))
            assertEquals(
                listOf(cardTaskId),
                database.taskDao().activeUnfinishedTasksInPool(PoolType.CARD).map { it.id },
            )
            assertEquals(
                listOf(boardTaskId),
                database.taskDao().activeUnfinishedTasksInPool(PoolType.BOARD).map { it.id },
            )
            assertEquals(
                listOf(cardTaskId),
                database.taskDao().tasksOfCellIncludingCompleted(cardCellId).map { it.id },
                "a deleted task showed up in the cell it used to be written in",
            )
            assertFalse(
                database.taskDao().tasksOfGameIncludingCompleted(gameId).any {
                    it.id == deletedCardTaskId || it.id == deletedBoardTaskId
                },
            )
        }

    @Test
    fun `the tables the progress model needs are all there`() =
        withMigratedDatabase { database ->
            val tables =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
                        buildList { while (statement.step()) add(statement.getText(0)) }
                    }
                }
            assertTrue(tables.contains("task_stages"))
            assertTrue(tables.contains("progress_events"))
            assertFalse(tables.contains("items"), "the item table came back")
        }
}
