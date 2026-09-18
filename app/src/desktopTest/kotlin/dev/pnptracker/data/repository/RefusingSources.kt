package dev.pnptracker.data.repository

import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.games.GameTableRow
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.pools.PoolSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// The three observed readings of Dilim 3, each able to fail on demand. A real
// database refusing a real statement is what the records are measured against
// (see the diagnostics tests); these are for the other half of the question —
// what a screen does with a defect, a cancellation, or a refusal it cannot make
// a real file produce on cue.

/** A table whose reading throws whatever it was given. */
class RefusingTable(
    private val failure: Throwable,
) : GameTableSource {
    override fun observeTable(): Flow<List<GameTableRow>> = flow { throw failure }
}

/** A catalogue whose reading throws; nothing else on it is ever reached. */
class RefusingCatalogue(
    private val failure: Throwable,
) : ColorCatalogue {
    override fun observeColors(): Flow<List<ColorSummary>> = flow { throw failure }

    override suspend fun colorsUsingHex(hex: String) = error("not reached")

    override suspend fun createColor(
        canonicalName: String,
        hex: String,
    ) = error("not reached")

    override suspend fun editColor(
        id: EntityId,
        expectedName: String,
        expectedHex: String,
        canonicalName: String,
        hex: String,
    ) = error("not reached")

    override suspend fun usageOf(id: EntityId) = error("not reached")

    override suspend fun deleteColor(id: EntityId) = error("not reached")

    override suspend fun previewBaseColorRestore() = error("not reached")

    override suspend fun restoreMissingBaseColors() = error("not reached")
}

/** A pool whose reading throws; the sidebar's own reading is never asked for. */
class RefusingPool(
    private val failure: Throwable,
) : PoolSource {
    override fun observePool(poolType: PoolType): Flow<PoolSnapshot> = flow { throw failure }

    override fun observeNavigationSummary() = error("not reached")
}
