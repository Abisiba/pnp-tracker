package dev.pnptracker.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.pnptracker.data.database.CELL_COLUMN_DISPLAY_ORDER
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Reads and writes the cells of the game table. */
@Dao
interface GameCellDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(cell: GameCellEntity)

    @Query("SELECT * FROM game_cells WHERE id = :id")
    suspend fun cellById(id: EntityId): GameCellEntity?

    /** One game's cells, left to right across the table. */
    @Query("SELECT * FROM game_cells WHERE game_id = :gameId ORDER BY " + CELL_COLUMN_DISPLAY_ORDER)
    suspend fun cellsOfGame(gameId: EntityId): List<GameCellEntity>

    @Query("SELECT * FROM game_cells WHERE game_id = :gameId AND column_type = :columnType")
    suspend fun cellOfGame(
        gameId: EntityId,
        columnType: CellColumnType,
    ): GameCellEntity?

    /** Every active game's cells, each game's in table order. */
    @Query(
        """
        SELECT game_cells.* FROM game_cells
        INNER JOIN games ON games.id = game_cells.game_id
        WHERE games.deleted_at IS NULL
        ORDER BY games.name, games.id, """ + CELL_COLUMN_DISPLAY_ORDER + """
        """,
    )
    fun observeCellsOfActiveGames(): Flow<List<GameCellEntity>>

    /** 1 while the game exists and has not been deleted, 0 otherwise. */
    @Query("SELECT COUNT(*) FROM games WHERE id = :gameId AND deleted_at IS NULL")
    suspend fun activeGameCount(gameId: EntityId): Int

    /**
     * Hands back the game's cell for one column, making it the first time it is
     * asked for.
     *
     * The lookup and the insert are in one transaction because two callers asking
     * at once would otherwise both find nothing and both write; the unique index
     * would refuse the second, turning an ordinary first use into an error.
     *
     * @throws IllegalArgumentException if the game is gone; nothing is written.
     */
    @Transaction
    suspend fun cellFor(
        gameId: EntityId,
        columnType: CellColumnType,
        id: EntityId,
        moment: Instant,
    ): GameCellEntity {
        require(activeGameCount(gameId) == 1) { "There is no game $gameId to open a cell in." }
        cellOfGame(gameId, columnType)?.let { return it }
        val cell =
            GameCellEntity(
                id = id,
                gameId = gameId,
                columnType = columnType,
                createdAt = moment,
                updatedAt = moment,
            )
        insert(cell)
        return cell
    }
}
