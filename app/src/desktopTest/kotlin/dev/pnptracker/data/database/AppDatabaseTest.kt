package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
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
import kotlin.time.Instant

/**
 * Covers opening, reopening and the shape of schema v1. Every test works inside
 * its own temporary directory; the real `~/.local/share/pnp-tracker/pnp.db` is
 * never opened.
 */
class AppDatabaseTest {
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

    private fun reopen() {
        database.close()
        database = DatabaseFactory().open(directory.databaseFile)
    }

    private suspend fun queryScalars(sql: String): List<String> =
        database.useReaderConnection { transactor ->
            transactor.usePrepared(sql) { statement ->
                buildList {
                    while (statement.step()) {
                        add(statement.getText(0))
                    }
                }
            }
        }

    @Test
    fun `the current schema version is created in the temporary file`() =
        runBlocking {
            database.gameDao().activeCount()

            assertTrue(Files.exists(directory.databaseFile), "database file was not created")
            val version =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared("PRAGMA user_version") { statement ->
                        statement.step()
                        statement.getLong(0)
                    }
                }
            assertEquals(3L, version)
        }

    @Test
    fun `the expected tables exist`() =
        runBlocking {
            database.gameDao().activeCount()

            val tables = queryScalars("SELECT name FROM sqlite_master WHERE type = 'table'")

            assertContains(tables, "games")
            assertContains(tables, "items")
        }

    @Test
    fun `the schema declares no autoincrement identity`() =
        runBlocking {
            database.gameDao().activeCount()

            val definitions =
                queryScalars(
                    "SELECT sql FROM sqlite_master WHERE type = 'table' " +
                        "AND name IN ('games', 'items', 'colors', 'color_aliases', 'tasks', 'task_colors', " +
                        "'import_batches', 'raw_import_blocks', 'draft_tasks')",
                )

            assertEquals(9, definitions.size)
            definitions.forEach { definition ->
                assertFalse(definition.uppercase().contains("AUTOINCREMENT"), "unexpected autoincrement in: $definition")
            }
        }

    @Test
    fun `the expected indices and foreign key exist`() =
        runBlocking {
            database.gameDao().activeCount()

            val indices = queryScalars("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'")
            val foreignKeys =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared("PRAGMA foreign_key_list('items')") { statement ->
                        buildList {
                            while (statement.step()) {
                                val columnNames = statement.getColumnNames()
                                add(
                                    columnNames.indices.associate { index ->
                                        columnNames[index] to statement.getText(index)
                                    },
                                )
                            }
                        }
                    }
                }

            assertContains(indices, "index_games_deleted_at")
            assertContains(indices, "index_items_game_id")
            assertContains(indices, "index_items_deleted_at")
            assertEquals(1, foreignKeys.size)
            val foreignKey = foreignKeys.single()
            assertEquals("games", foreignKey["table"])
            assertEquals("game_id", foreignKey["from"])
            assertEquals("id", foreignKey["to"])
            assertEquals("RESTRICT", foreignKey["on_delete"])
        }

    @Test
    fun `the database can be closed and opened again`() =
        runBlocking {
            database.gameDao().activeCount()

            reopen()

            assertEquals(0, database.gameDao().activeCount())
        }

    @Test
    fun `a game survives a close and reopen`() =
        runBlocking {
            val game = aGame(name = "Wingspan", notes = "170 kart")
            database.gameDao().insert(game)

            reopen()

            assertEquals(listOf(game), database.gameDao().activeGames())
        }

    @Test
    fun `the item to game relation survives a close and reopen`() =
        runBlocking {
            val game = aGame()
            val item = anItem(gameId = game.id, name = "Kilic")
            database.gameDao().insert(game)
            database.itemDao().insert(item)

            reopen()

            val restored = database.itemDao().activeItemsOfGame(game.id)
            assertEquals(listOf(item), restored)
            assertEquals(game.id, restored.single().gameId)
        }

    @Test
    fun `a manually completed game keeps its completion after a reopen`() =
        runBlocking {
            val completion = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_UPDATED)
            val game = aGame(name = "Root", isManuallyCompleted = true, completedAt = completion)
            database.gameDao().insert(game)

            reopen()

            val restored = assertNotNull(database.gameDao().activeGameById(game.id))
            assertTrue(restored.isManuallyCompleted)
            assertEquals(completion, restored.completedAt)
        }

    @Test
    fun `nullable timestamps and notes round trip as null`() =
        runBlocking {
            val game = aGame(notes = null, completedAt = null)
            database.gameDao().insert(game)

            reopen()

            val restored = assertNotNull(database.gameDao().gameByIdIncludingDeleted(game.id))
            assertNull(restored.notes)
            assertNull(restored.completedAt)
            assertNull(restored.deletedAt)
            assertFalse(restored.isManuallyCompleted)
        }

    @Test
    fun `an identifier is stored as canonical text`() =
        runBlocking {
            val game = aGame()
            database.gameDao().insert(game)

            val storedIds = queryScalars("SELECT id FROM games")

            assertEquals(listOf(game.id.toString()), storedIds)
            assertEquals(game.id, EntityId.parse(storedIds.single()))
            assertEquals(
                listOf("TEXT"),
                database.useReaderConnection { transactor ->
                    transactor.usePrepared("SELECT typeof(id) FROM games") { statement ->
                        buildList {
                            while (statement.step()) add(statement.getText(0).uppercase())
                        }
                    }
                },
            )
        }

    @Test
    fun `timestamps are stored as integer epoch milliseconds`() =
        runBlocking {
            val game = aGame()
            database.gameDao().insert(game)

            val storedTypesAndValues =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared("SELECT typeof(created_at), created_at FROM games") { statement ->
                        statement.step()
                        statement.getText(0).uppercase() to statement.getLong(1)
                    }
                }

            assertEquals("INTEGER", storedTypesAndValues.first)
            assertEquals(EPOCH_MILLISECONDS_CREATED, storedTypesAndValues.second)
        }

    @Test
    fun `inserting the same identifier twice is rejected`() =
        runBlocking {
            val game = aGame()
            database.gameDao().insert(game)

            val failure =
                assertFailsWith<SQLiteException> {
                    database.gameDao().insert(game.copy(name = "Baska isim"))
                }

            assertContains(failure.message.orEmpty().uppercase(), "UNIQUE")
            assertEquals(1, database.gameDao().activeCount())
            assertEquals("Harmonies", assertNotNull(database.gameDao().activeGameById(game.id)).name)
        }

    @Test
    fun `an item pointing at an unknown game is rejected by the foreign key`() =
        runBlocking {
            val orphan = anItem(gameId = IdGenerator.Random.newId())

            val failure = assertFailsWith<SQLiteException> { database.itemDao().insert(orphan) }

            assertContains(failure.message.orEmpty().uppercase(), "FOREIGN KEY")
            assertEquals(emptyList(), database.itemDao().allItemsIncludingDeleted())
        }

    @Test
    fun `hard deleting a game that still has items is rejected by the foreign key`() =
        runBlocking {
            val game = aGame()
            database.gameDao().insert(game)
            database.itemDao().insert(anItem(gameId = game.id))

            val failure =
                assertFailsWith<SQLiteException> {
                    database.useWriterConnection { transactor ->
                        transactor.usePrepared("DELETE FROM games WHERE id = ?") { statement ->
                            statement.bindText(1, game.id.toString())
                            statement.step()
                        }
                    }
                }

            assertContains(failure.message.orEmpty().uppercase(), "FOREIGN KEY")
            assertEquals(1, database.gameDao().activeCount())
        }
}
