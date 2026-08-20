package dev.pnptracker.ui

import dev.pnptracker.domain.model.PoolType
import org.jetbrains.compose.resources.StringResource

/**
 * The one place a production pool turns into words the user reads.
 *
 * The names are the ones PLAN 3.4 gives the four pools, and every screen asks
 * here for them. Two screens working the same mapping out separately is how the
 * board pool ended up with one name in the game detail and a different one in
 * the import review, leaving the user with two words for one thing.
 *
 * The `when` is exhaustive on purpose and has no fallback: a pool added later has
 * to be named here before the code will build, rather than quietly reaching the
 * screen under some general wording.
 */
fun poolNameOf(poolType: PoolType): StringResource =
    when (poolType) {
        PoolType.THREE_D -> Strings.Pools.threeD
        PoolType.CARD -> Strings.Pools.card
        PoolType.BOARD -> Strings.Pools.board
        PoolType.SPECIAL -> Strings.Pools.special
    }
