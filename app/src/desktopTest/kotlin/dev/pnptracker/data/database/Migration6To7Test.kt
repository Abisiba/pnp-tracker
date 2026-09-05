package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The step that gives the application somewhere to keep what happened.
 *
 * Version 6 stored where everything stands. It stored three facts that are really
 * records of something happening — when a task was finished, when a task was
 * removed, when a game was removed — and nothing else about the past at all. So
 * the fixture below is a database someone really used, and these ask two
 * questions of it: that every row of it is still there afterwards, and that
 * exactly those three facts, and nothing invented beside them, come out as
 * history.
 */
class Migration6To7Test {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExistedBefore = false

    private val gameId = IdGenerator.Random.newId()
    private val deletedGameId = IdGenerator.Random.newId()
    private val threeDCellId = IdGenerator.Random.newId()
    private val cardCellId = IdGenerator.Random.newId()
    private val goneCellId = IdGenerator.Random.newId()

    private val openTaskId = IdGenerator.Random.newId()
    private val finishedTaskId = IdGenerator.Random.newId()
    private val deletedTaskId = IdGenerator.Random.newId()
    private val finishedAndDeletedTaskId = IdGenerator.Random.newId()
    private val cardTaskId = IdGenerator.Random.newId()
    private val taskInDeletedGameId = IdGenerator.Random.newId()
    private val strandedTaskId = IdGenerator.Random.newId()
    private val eventId = IdGenerator.Random.newId()

    /** Two different moments, so a finish and a deletion cannot be confused. */
    private val finishedMoment = 1_700_000_900_000L
    private val removedMoment = 1_700_001_500_000L

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

    /** A version 6 database as somebody who had really been using it would leave it. */
    private fun createUsedVersion6Database() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 6) { connection ->
            insertSeedColors(connection)
            insertVersion4Game(connection, gameId)
            insertVersion4Game(connection, deletedGameId, name = "Wingspan", deleted = true)
            insertVersion4GameCell(connection, threeDCellId, gameId, "THREE_D")
            insertVersion4GameCell(connection, cardCellId, gameId, "CARD")
            insertVersion4GameCell(connection, goneCellId, deletedGameId, "THREE_D")

            insertVersion6Task(connection, openTaskId, "THREE_D", "THREE_D_BATCH", "Gri token", 40)
            insertVersion6Task(
                connection,
                finishedTaskId,
                "THREE_D",
                "THREE_D_BATCH",
                "Bitmiş token",
                12,
                completedAt = finishedMoment,
                primaryBatchCompleted = true,
            )
            insertVersion6Task(
                connection,
                deletedTaskId,
                "THREE_D",
                "THREE_D_BATCH",
                "Silinmiş",
                5,
                deletedAt = removedMoment,
            )
            // Finished on one day and taken out of view on another: two things
            // happened to it, so it has to come out with two lines.
            insertVersion6Task(
                connection,
                finishedAndDeletedTaskId,
                "THREE_D",
                "THREE_D_BATCH",
                "Bitmiş sonra silinmiş",
                8,
                completedAt = finishedMoment,
                primaryBatchCompleted = true,
                deletedAt = removedMoment,
            )
            insertVersion6Task(connection, cardTaskId, "CARD", "PIPELINE", "Bird Cards", 170)
            insertVersion6Task(
                connection,
                taskInDeletedGameId,
                "THREE_D",
                "THREE_D_BATCH",
                "Silinmiş oyunun görevi",
                3,
                completedAt = finishedMoment,
            )
            // Written in no cell at all. Nothing the application can do makes one
            // of these, and it must neither be given a made up game nor stop the
            // upgrade.
            insertVersion6Task(
                connection,
                strandedTaskId,
                "THREE_D",
                "THREE_D_BATCH",
                "Çapasız",
                1,
                completedAt = finishedMoment,
                deletedAt = removedMoment,
            )

            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 0, openTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 1, finishedTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 2, deletedTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), threeDCellId, 3, finishedAndDeletedTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cardCellId, 0, cardTaskId)
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), goneCellId, 0, taskInDeletedGameId)

            insertVersion5TaskStage(connection, cardTaskId, "PRINT", 0, 40)
            insertVersion5TaskStage(connection, cardTaskId, "LAMINATE", 1, 10)
            insertVersion5TaskStage(connection, cardTaskId, "CUT", 2, 0)
            insertVersion5ProgressEvent(connection, eventId, openTaskId, "FAILURE_REPORTED", 3, note = "kenar bozuk")
        }
    }

    private fun version() = CommittedSchema.readVersion(directory.databaseFile)

    private fun rows(table: String) = CommittedSchema.countRowsOf(directory.databaseFile, table)

    private fun openAndClose() {
        val database = DatabaseFactory().open(directory.databaseFile)
        runBlocking<Unit> { database.gameDao().activeGames() }
        database.close()
    }

    /** Every history row the walk produced, read without going through Room. */
    private fun historyRows(): List<Row> =
        BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
            connection
                .prepare(
                    "SELECT id, kind, occurred_at, game_id, task_id, stage, previous_quantity, new_quantity " +
                        "FROM history_events ORDER BY occurred_at, kind, id",
                ).use { statement ->
                    buildList {
                        while (statement.step()) {
                            add(
                                Row(
                                    id = statement.getText(0),
                                    kind = statement.getText(1),
                                    occurredAt = statement.getLong(2),
                                    gameId = statement.getText(3),
                                    taskId = if (statement.isNull(4)) null else statement.getText(4),
                                    stage = if (statement.isNull(5)) null else statement.getText(5),
                                    previousQuantity = if (statement.isNull(6)) null else statement.getInt(6),
                                    newQuantity = if (statement.isNull(7)) null else statement.getInt(7),
                                ),
                            )
                        }
                    }
                }
        }

    private data class Row(
        val id: String,
        val kind: String,
        val occurredAt: Long,
        val gameId: String,
        val taskId: String?,
        val stage: String?,
        val previousQuantity: Int?,
        val newQuantity: Int?,
    )

    private fun eventsOf(taskId: EntityId) = historyRows().filter { it.taskId == taskId.toString() }

    // ------------------------------------------------------------ the walk itself

    @Test
    fun `an empty version 6 database walks up without inventing anything`() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 6) { connection ->
            insertSeedColors(connection)
        }

        openAndClose()

        assertEquals(CURRENT_SCHEMA_VERSION, version())
        assertEquals(0, rows("history_events"), "a database with no past was given one")
        assertEquals(12, rows("colors"))
    }

    @Test
    fun `a used version 6 database reaches the current version with every row still there`() {
        createUsedVersion6Database()
        val before = TABLES.associateWith { rows(it) }

        openAndClose()

        assertEquals(CURRENT_SCHEMA_VERSION, version())
        assertEquals(before, TABLES.associateWith { rows(it) }, "the walk changed how many rows a table holds")
    }

    @Test
    fun `a version 1 database can be walked all the way to the current version`() =
        runBlocking<Unit> {
            CommittedSchema.createDatabase(directory.databaseFile, version = 1) { }
            assertEquals(1L, version())

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(12, database.colorDao().allColors().size)
                assertEquals(emptyList(), soundnessProblemsOf(database))
                assertEquals(emptyList(), database.historyDao().allEvents())
            } finally {
                database.close()
            }
            assertEquals(CURRENT_SCHEMA_VERSION, version())
        }

    @Test
    fun `the walk leaves no foreign key dangling and no index missing`() =
        runBlocking<Unit> {
            createUsedVersion6Database()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(emptyList(), soundnessProblemsOf(database))
            } finally {
                database.close()
            }
            assertEquals(
                setOf(
                    "index_history_events_occurred_at",
                    "index_history_events_game_id_occurred_at",
                    "index_history_events_task_id_occurred_at",
                ),
                indexNamesOf("history_events"),
                "the history was left without the indexes its own screen will read it by",
            )
        }

    @Test
    fun `the schema the walk produces is the schema that was committed`() {
        createUsedVersion6Database()
        openAndClose()

        val committed =
            CommittedSchema
                .read(CURRENT_SCHEMA_VERSION.toInt())
                .createStatements
                .single { it.startsWith("CREATE TABLE") && "`history_events`" in it }

        assertEquals(normalised(committed), normalised(createSqlOf("history_events")))
    }

    // --------------------------------------------------------- what is recovered

    @Test
    fun `a task that was finished comes out with the day it was finished on`() {
        createUsedVersion6Database()

        openAndClose()

        val event = eventsOf(finishedTaskId).single()
        assertEquals(HistoryEventKind.TASK_COMPLETED.name, event.kind)
        assertEquals(finishedMoment, event.occurredAt, "the finish was moved to another day")
        assertEquals(gameId.toString(), event.gameId, "the finish was filed under the wrong game")
        assertNull(event.stage)
        assertNull(event.previousQuantity)
        assertNull(event.newQuantity)
    }

    @Test
    fun `a task that was removed comes out with the day it was removed on`() {
        createUsedVersion6Database()

        openAndClose()

        val event = eventsOf(deletedTaskId).single()
        assertEquals(HistoryEventKind.TASK_DELETED.name, event.kind)
        assertEquals(removedMoment, event.occurredAt)
        assertEquals(gameId.toString(), event.gameId)
    }

    @Test
    fun `a game that was removed comes out with the day it was removed on`() {
        createUsedVersion6Database()

        openAndClose()

        val event = historyRows().single { it.kind == HistoryEventKind.GAME_DELETED.name }
        assertEquals(deletedGameId.toString(), event.gameId)
        assertNull(event.taskId, "a game's own removal named a task")
        assertEquals(EPOCH_MILLISECONDS_DELETED, event.occurredAt)
    }

    @Test
    fun `a task that was finished and then removed comes out with both`() {
        createUsedVersion6Database()

        openAndClose()

        val events = eventsOf(finishedAndDeletedTaskId)
        assertEquals(
            listOf(HistoryEventKind.TASK_COMPLETED.name to finishedMoment, HistoryEventKind.TASK_DELETED.name to removedMoment),
            events.map { it.kind to it.occurredAt },
            "two different things happened and did not come out as two lines",
        )
        assertEquals(2, events.map { it.id }.toSet().size, "the two lines were given one identity")
    }

    @Test
    fun `a task inside a removed game keeps its own history`() {
        createUsedVersion6Database()

        openAndClose()

        val event = eventsOf(taskInDeletedGameId).single()
        assertEquals(HistoryEventKind.TASK_COMPLETED.name, event.kind)
        assertEquals(deletedGameId.toString(), event.gameId, "the game it was in was not the game recorded")
    }

    @Test
    fun `a task written in no cell is left without a made up game`() {
        createUsedVersion6Database()

        openAndClose()

        assertEquals(emptyList(), eventsOf(strandedTaskId), "a task with no cell was given a guessed game")
        assertNotNull(
            BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
                connection.prepare("SELECT id FROM tasks WHERE id = ?").use { statement ->
                    statement.bindText(1, strandedTaskId.toString())
                    if (statement.step()) statement.getText(0) else null
                }
            },
            "the task itself was dropped rather than simply left out of the history",
        )
    }

    @Test
    fun `an unfinished task that is still there is not given a past`() {
        createUsedVersion6Database()

        openAndClose()

        assertEquals(emptyList(), eventsOf(openTaskId), "a task nothing had happened to was given history")
    }

    // ----------------------------------------------------- what is deliberately not

    @Test
    fun `the pipeline that was half counted is not turned into movements`() {
        createUsedVersion6Database()

        openAndClose()

        assertEquals(
            emptyList(),
            historyRows().filter { it.kind == HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED.name },
            "a count was read as a movement, on a day nobody knows",
        )
        assertEquals(emptyList(), eventsOf(cardTaskId), "a pipeline standing at forty was called forty movements")
    }

    @Test
    fun `nothing is invented for reopenings or conversions that left no trace`() {
        createUsedVersion6Database()

        openAndClose()

        val kinds = historyRows().map { it.kind }.toSet()
        assertEquals(
            setOf(
                HistoryEventKind.TASK_COMPLETED.name,
                HistoryEventKind.TASK_DELETED.name,
                HistoryEventKind.GAME_DELETED.name,
            ),
            kinds,
            "the walk wrote a kind of event a version 6 database cannot know about",
        )
    }

    @Test
    fun `the events version 6 already kept are carried across untouched`() {
        createUsedVersion6Database()

        openAndClose()

        BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
            connection.prepare("SELECT task_id, kind, quantity, note, recorded_at FROM progress_events").use { statement ->
                assertTrue(statement.step(), "the shortage that was reported is gone")
                assertEquals(openTaskId.toString(), statement.getText(0))
                assertEquals("FAILURE_REPORTED", statement.getText(1))
                assertEquals(3, statement.getInt(2))
                assertEquals("kenar bozuk", statement.getText(3))
                assertEquals(EPOCH_MILLISECONDS_UPDATED, statement.getLong(4))
                assertTrue(!statement.step(), "the walk added a shortage of its own")
            }
        }
    }

    // ------------------------------------------------------------- the identities

    @Test
    fun `every recovered event has a valid and unique identity`() {
        createUsedVersion6Database()

        openAndClose()

        val rows = historyRows()
        // Two tasks finished, one of them later removed, one more removed, one
        // finished inside a game that has since gone, and that game's own removal.
        assertEquals(6, rows.size, "the walk did not recover what the fixture put in: $rows")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "two recovered events share an identity")
        // Canonical, because that is the only form the application will read back.
        rows.forEach { EntityId.parse(it.id) }
    }

    @Test
    fun `a recovered identity can never be one the application would generate`() {
        createUsedVersion6Database()

        openAndClose()

        // The fifteenth character of a canonical UUID is its version. Everything
        // this application generates is a random UUID and carries `4` there, so a
        // recovered event carrying anything else can never collide with one.
        historyRows().forEach { row ->
            assertTrue(
                row.id[14] != '4',
                "a recovered event was given an identity the application could generate: ${row.id}",
            )
        }
    }

    @Test
    fun `running the recovery a second time would collide rather than double the history`() {
        createUsedVersion6Database()
        openAndClose()
        val before = historyRows()

        // The walk cannot really run twice — the version is already 7 — so the
        // statements are replayed by hand. What is being checked is the property
        // that makes that safe: the identities are derived from the rows they are
        // about, so a repeat is refused by the primary key instead of quietly
        // writing the same past again.
        val second =
            runCatching {
                BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
                    connection.execSQL(
                        "INSERT INTO `history_events` " +
                            "(`id`, `kind`, `occurred_at`, `game_id`, `task_id`, `stage`, " +
                            "`previous_quantity`, `new_quantity`) " +
                            "SELECT substr(`tasks`.`id`, 1, 14) || '8' || substr(`tasks`.`id`, 16), " +
                            "'TASK_COMPLETED', `tasks`.`completed_at`, `game_cells`.`game_id`, `tasks`.`id`, " +
                            "NULL, NULL, NULL FROM `tasks` " +
                            "INNER JOIN `cell_segments` ON `cell_segments`.`task_id` = `tasks`.`id` " +
                            "INNER JOIN `game_cells` ON `game_cells`.`id` = `cell_segments`.`cell_id` " +
                            "INNER JOIN `games` ON `games`.`id` = `game_cells`.`game_id` " +
                            "WHERE `tasks`.`completed_at` IS NOT NULL",
                    )
                }
            }

        assertTrue(second.isFailure, "the same past could be written twice")
        assertEquals(before, historyRows(), "a refused repeat still changed the history")
    }

    // ------------------------------------------------------------------- helpers

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

    private fun indexNamesOf(table: String): Set<String> =
        BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
            connection
                .prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?")
                .use { statement ->
                    statement.bindText(1, table)
                    // SQLite's own index behind the primary key is not one this
                    // migration chose, so it is not one this is about.
                    buildSet { while (statement.step()) add(statement.getText(0)) }
                        .filterNot { it.startsWith("sqlite_") }
                        .toSet()
                }
        }

    private fun createSqlOf(table: String): String =
        BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
            connection.prepare("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
                statement.bindText(1, table)
                assertTrue(statement.step(), "there is no table called $table")
                statement.getText(0)
            }
        }

    /** Ignores the difference between `CREATE TABLE` and `CREATE TABLE IF NOT EXISTS`, and spacing. */
    private fun normalised(sql: String): String =
        sql
            .replace("IF NOT EXISTS ", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        val TABLES =
            listOf(
                "games",
                "game_cells",
                "cell_segments",
                "colors",
                "color_aliases",
                "tasks",
                "task_colors",
                "task_stages",
                "progress_events",
                "import_batches",
                "raw_import_blocks",
                "draft_tasks",
                "draft_task_colors",
            )
    }
}

private fun SQLiteConnection.execSQL(sql: String) = prepare(sql).use { it.step() }
