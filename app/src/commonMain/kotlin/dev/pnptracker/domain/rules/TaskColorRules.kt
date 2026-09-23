package dev.pnptracker.domain.rules

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType

/**
 * Which pools a colour means anything in.
 *
 * Three dimensional printing alone. PLAN 5.10 makes the colour the filament a
 * thing is printed in, and a card, a board piece or a special task is not
 * printed in a filament: they are worked through stages or counted, and a colour
 * on one of them was never anything the pools, the table or the export could
 * use.
 */
fun poolHoldsColors(poolType: PoolType): Boolean = poolType == PoolType.THREE_D

/**
 * @throws IllegalArgumentException if [colorIds] cannot be given to [poolType].
 *
 * A programming mistake rather than something a user is told about, exactly like
 * a tracking mode a pool does not allow: no screen offers a colour outside the
 * printing pool, so a colour arriving for one came from code and not from
 * anybody's choice. Nothing here looks at colours a task already carries — an
 * older record or a restored backup may hold some, and taking those away is not
 * this rule's business.
 */
fun requireColorsAllowed(
    poolType: PoolType,
    colorIds: List<EntityId>,
) {
    require(colorIds.isEmpty() || poolHoldsColors(poolType)) {
        "$poolType tasks have no colour; PLAN 5.10 gives colours to ${PoolType.THREE_D} alone"
    }
}
