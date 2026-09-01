package dev.pnptracker.data.database.projection

import androidx.room3.ColumnInfo
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId

/**
 * One cell a whole import is aiming at, with everything a confirmation needs to
 * know about it.
 *
 * Read for the batch rather than for a draft. Asking per draft cost three
 * queries a row — is the cell there, what column is it, where does its document
 * end — which is the shape PLAN 16 rules out and the shape a batch of forty-two
 * drafts turned into eighty-five reads. All three answers are properties of the
 * cell, so one row of this says all of them once however many drafts point at it.
 *
 * A cell that has gone, or whose game has been deleted, is simply absent, so the
 * presence of a row is the whole of the availability answer.
 *
 * [nextOrderIndex] is where the next piece of the document goes, and
 * [segmentCount] is how many pieces it already has. Together they say whether
 * the document is sound: pieces numbered `0..N-1` with no gaps have exactly as
 * many rows as their highest number plus one. A cell where the two disagree is
 * damaged, and a confirmation refuses it rather than renumbering somebody's
 * document behind their back.
 */
data class DraftTargetRow(
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    @ColumnInfo(name = "column_type")
    val columnType: CellColumnType,
    @ColumnInfo(name = "game_id")
    val gameId: EntityId,
    @ColumnInfo(name = "next_order_index")
    val nextOrderIndex: Int,
    @ColumnInfo(name = "segment_count")
    val segmentCount: Int,
) {
    /** True when the document is numbered `0..N-1` with nothing missing. */
    val isSound: Boolean get() = nextOrderIndex == segmentCount
}
