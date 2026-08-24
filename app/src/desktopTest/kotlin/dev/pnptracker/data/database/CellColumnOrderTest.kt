package dev.pnptracker.data.database

import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant

/**
 * The order the columns of the game table are read in.
 *
 * `ORDER BY column_type` sorts the stored text, so it hands back `BOARD, CARD,
 * NOTES, SPECIAL, THREE_D` — an alphabet from the spelling of the constants,
 * which is not a language the screen is written in and not an order the user has
 * ever seen. The queries name [CELL_COLUMN_DISPLAY_ORDER] instead.
 *
 * The first test is the one that keeps this honest over time: it compares the
 * SQL against the enum, so a column added to one and forgotten in the other
 * fails here rather than turning up sideways on a screen.
 */
class CellColumnOrderTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private val moment = Instant.fromEpochMilliseconds(1_700_000_000_000)

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

    @Test
    fun `the sql order and the enum order are the same order`() {
        val fromSql =
            Regex("""WHEN '(\w+)' THEN (\d+)""")
                .findAll(CELL_COLUMN_DISPLAY_ORDER)
                .map { it.groupValues[1] to it.groupValues[2].toInt() }
                .sortedBy { it.second }
                .map { it.first }
                .toList()
        assertEquals(
            CellColumnType.entries.map { it.name },
            fromSql,
            "the sql order and CellColumnType.entries have drifted apart",
        )
    }

    @Test
    fun `the user's order is not the alphabet the constants would give`() {
        // Stated outright so the point of the CASE is not lost later: sorting the
        // stored text is not merely a different order, it is the wrong one.
        assertNotEquals(
            CellColumnType.entries.map { it.name },
            CellColumnType.entries.map { it.name }.sorted(),
        )
    }

    @Test
    fun `a game's cells come back in table order however they were opened`() =
        runBlocking {
            // Written in the reverse of the order they are read in, so a query
            // that simply kept insertion order would fail here too.
            val gameId = insertGame()
            CellColumnType.entries.reversed().forEach { insertCell(gameId, it) }

            assertEquals(
                CellColumnType.entries.toList(),
                database.gameCellDao().cellsOfGame(gameId).map { it.columnType },
            )
        }

    @Test
    fun `the stream every screen reads is in table order too`() =
        runBlocking {
            val gameId = insertGame()
            CellColumnType.entries.reversed().forEach { insertCell(gameId, it) }

            val streamed = database.gameCellDao().observeCellsOfActiveGames().first()

            assertEquals(CellColumnType.entries.toList(), streamed.map { it.columnType })
        }

    private suspend fun insertGame(): EntityId {
        val game =
            GameEntity(
                id = IdGenerator.Random.newId(),
                name = "Harmonies",
                createdAt = moment,
                updatedAt = moment,
            )
        database.gameDao().insert(game)
        return game.id
    }

    private suspend fun insertCell(
        gameId: EntityId,
        columnType: CellColumnType,
    ) {
        database.gameCellDao().insert(
            GameCellEntity(
                id = IdGenerator.Random.newId(),
                gameId = gameId,
                columnType = columnType,
                createdAt = moment,
                updatedAt = moment,
            ),
        )
    }
}
