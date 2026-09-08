package dev.pnptracker.ui

import dev.pnptracker.domain.games.GameTableView
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
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

/**
 * The Turkish name of a table column.
 *
 * The four production columns borrow the names of the pools they feed, so a user
 * reading `Mukavva` in the table and `Mukavva` in the pool is reading about the
 * same thing. Notes is the one column with no pool behind it.
 */
fun columnNameOf(columnType: CellColumnType): StringResource =
    when (columnType) {
        CellColumnType.NOTES -> Strings.Columns.notes
        else -> poolNameOf(requireNotNull(columnType.poolType))
    }

/**
 * The Turkish name of one of the three table views.
 *
 * PLAN 12.4 names them, and the `when` is exhaustive so a fourth view could not
 * reach the screen without being given a word first.
 */
fun viewNameOf(view: GameTableView): StringResource =
    when (view) {
        GameTableView.ONGOING -> Strings.Table.viewOngoing
        GameTableView.COMPLETED -> Strings.Table.viewCompleted
        GameTableView.ALL -> Strings.Table.viewAll
    }

/**
 * Where one import stands, in the user's words.
 *
 * The one place this mapping lives. The duplicate warning used to write the
 * stored value straight onto the screen, which put `CONFIRMED` in front of a
 * Turkish-speaking user in the middle of a Turkish sentence; PLAN 17 keeps the
 * wording in the catalogue, and a status is wording like any other.
 *
 * Exhaustive with no fallback, so a fourth status could not reach a screen
 * without being named first — and PLAN 11.4.4 is explicit that there is no
 * fourth one to add.
 */
fun importStatusNameOf(status: ImportBatchStatus): StringResource =
    when (status) {
        ImportBatchStatus.DRAFT -> Strings.ImportStatus.draft
        ImportBatchStatus.CONFIRMED -> Strings.ImportStatus.confirmed
        ImportBatchStatus.ROLLED_BACK -> Strings.ImportStatus.rolledBack
    }

/**
 * The Turkish name of one production stage.
 *
 * Named for the state the work is in rather than for the act, because that is
 * how PLAN 7 and 8 write the pipelines out — `Basıldı`, not `Bas`. Printing and
 * cutting are the same step in both pipelines and are named once.
 */
fun stageNameOf(stage: ProductionStage): StringResource =
    when (stage) {
        ProductionStage.PRINT -> Strings.Pool.stagePrint
        ProductionStage.LAMINATE -> Strings.Pool.stageLaminate
        ProductionStage.GLUE -> Strings.Pool.stageGlue
        ProductionStage.CUT -> Strings.Pool.stageCut
    }
