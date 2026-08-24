package dev.pnptracker.data.repository

import dev.pnptracker.data.database.dao.GameCellDao
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.GameTableDao
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.projection.CellContentRow
import dev.pnptracker.domain.games.GameTableRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

/**
 * A [GameTableStore] that says how many database reads went into a table.
 *
 * It wraps the real DAOs rather than standing in for them, so what is counted is
 * the store's own behaviour against a real database and not a fake's idea of it.
 * Only the three reads the table is built from are counted; anything the store
 * started doing per game or per cell would show up as a number that grows.
 */
class CountingGameTable(
    gameDao: GameDao,
    gameCellDao: GameCellDao,
    gameTableDao: GameTableDao,
) : GameTableSource {
    var queries: Int = 0
        private set

    private val counted = GameTableStore(Counting(gameDao), CountingCells(gameCellDao), CountingContents(gameTableDao))

    fun reset() {
        queries = 0
    }

    override fun observeTable(): Flow<List<GameTableRow>> = counted.observeTable()

    private fun <T> Flow<T>.counting(): Flow<T> = onStart { queries++ }

    private inner class Counting(
        private val delegate: GameDao,
    ) : GameDao by delegate {
        override fun observeActiveGames(): Flow<List<GameEntity>> = delegate.observeActiveGames().counting()
    }

    private inner class CountingCells(
        private val delegate: GameCellDao,
    ) : GameCellDao by delegate {
        override fun observeCellsOfActiveGames(): Flow<List<GameCellEntity>> = delegate.observeCellsOfActiveGames().counting()
    }

    private inner class CountingContents(
        private val delegate: GameTableDao,
    ) : GameTableDao by delegate {
        override fun observeCellContents(): Flow<List<CellContentRow>> = delegate.observeCellContents().counting()
    }
}
