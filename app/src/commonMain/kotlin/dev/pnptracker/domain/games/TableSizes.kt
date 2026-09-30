package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The version of the table sizes document, which is nobody else's (PLAN 12.17). */
const val TABLE_SIZES_FORMAT_VERSION: Int = 1

/** The name column is wide enough for a real game name before anything wraps. */
const val DEFAULT_GAME_COLUMN_WIDTH_DP: Float = 240f

/** Every cell column, at the width the table has always given one. */
const val DEFAULT_CELL_COLUMN_WIDTH_DP: Float = 200f

/** Narrow enough to be useful, wide enough that a heading and a tick still read. */
const val MINIMUM_COLUMN_WIDTH_DP: Float = 96f

/** The height a row has always had at least, which is the cell's own floor. */
const val MINIMUM_ROW_HEIGHT_DP: Float = 64f

/** As wide as fitting a column to its content may make it (PLAN 12.17). */
const val AUTOMATIC_WIDTH_LIMIT_DP: Float = 480f

/** A column of the table: the name column, and one for each cell. */
enum class TableColumn {
    GAME_NAME,

    /** The unfinished tasks of the four production columns, together (PLAN 12.20). */
    MISSING,
    THREE_D,
    CARD,
    BOARD,
    SPECIAL,
    BORROWED,
    NOTES,
    ;

    /** The cell this column draws. The name and `Eksik` columns hold none and must not be asked. */
    val cellColumnType: CellColumnType
        get() =
            when (this) {
                THREE_D -> CellColumnType.THREE_D
                CARD -> CellColumnType.CARD
                BOARD -> CellColumnType.BOARD
                SPECIAL -> CellColumnType.SPECIAL
                BORROWED -> CellColumnType.BORROWED
                NOTES -> CellColumnType.NOTES
                GAME_NAME, MISSING -> error("$this holds no cell")
            }

    companion object {
        /** The column a cell of [columnType] is drawn in. */
        fun of(columnType: CellColumnType): TableColumn =
            when (columnType) {
                CellColumnType.THREE_D -> THREE_D
                CellColumnType.CARD -> CARD
                CellColumnType.BOARD -> BOARD
                CellColumnType.SPECIAL -> SPECIAL
                CellColumnType.BORROWED -> BORROWED
                CellColumnType.NOTES -> NOTES
            }
    }
}

/** Why the sizes in use are the defaults rather than the ones in the file. */
enum class TableSizesProblem {
    /** The file is there and could not be read at all. */
    COULD_NOT_READ,

    /** What is in it is not the document this writes. */
    NOT_THE_EXPECTED_SHAPE,

    /** It names a version of this format that this build does not know. */
    VERSION_NOT_SUPPORTED,
}

/**
 * The sizes this machine draws the table at (PLAN 12.17).
 *
 * Only what the user actually chose is held. Everything else answers with the
 * default, so a fresh machine and a machine whose file was unreadable draw the
 * same table, and the document stays as small as what somebody really changed.
 */
data class TableSizes(
    val columnWidths: Map<TableColumn, Float> = emptyMap(),
    val rowHeights: Map<EntityId, Float> = emptyMap(),
    val problem: TableSizesProblem? = null,
    /**
     * The order the columns are drawn in, left to right: every column once.
     *
     * A view setting like the widths, and nothing else: moving a column changes
     * where it is drawn, never a game, a cell or a task.
     */
    val columnOrder: List<TableColumn> = DEFAULT_COLUMN_ORDER,
) {
    /** The width [column] is drawn at, which is always usable. */
    fun widthOf(column: TableColumn): Float = columnWidths[column] ?: defaultWidthOf(column)

    /** The height the user gave this row, or null when the content decides. */
    fun heightOf(gameId: EntityId): Float? = rowHeights[gameId]

    /**
     * The same sizes with [column] at [width], no narrower than the minimum.
     *
     * A column put back at its default is dropped rather than written down: the
     * file then says what somebody really changed, and a machine that was reset
     * one column at a time ends up with the document a fresh machine has.
     */
    fun withColumn(
        column: TableColumn,
        width: Float,
    ): TableSizes {
        val held = width.coerceAtLeast(MINIMUM_COLUMN_WIDTH_DP)
        return copy(
            columnWidths =
                if (held == defaultWidthOf(column)) {
                    columnWidths - column
                } else {
                    columnWidths + (column to held)
                },
        )
    }

    /** The same sizes with this row at [height], no shorter than the minimum. */
    fun withRow(
        gameId: EntityId,
        height: Float,
    ): TableSizes = copy(rowHeights = rowHeights + (gameId to height.coerceAtLeast(MINIMUM_ROW_HEIGHT_DP)))

    /**
     * The same sizes with [column] moved to [index] in the order, the columns
     * between shifting over by one. An index past either end is held to it.
     */
    fun withColumnMoved(
        column: TableColumn,
        index: Int,
    ): TableSizes {
        val without = columnOrder - column
        val at = index.coerceIn(0, without.size)
        return copy(columnOrder = without.take(at) + column + without.drop(at))
    }

    /** The same sizes with the columns back in the table's own order, widths and heights kept. */
    fun withDefaultOrder(): TableSizes = copy(columnOrder = DEFAULT_COLUMN_ORDER)

    /** True when the columns are drawn in the table's own order. */
    val hasDefaultOrder: Boolean get() = columnOrder == DEFAULT_COLUMN_ORDER

    /** The same sizes without the rows of games that are not in [games]. */
    fun prunedTo(games: Set<EntityId>): TableSizes = copy(rowHeights = rowHeights.filterKeys { it in games })

    /** True when nothing here differs from the table's own defaults. */
    val isDefault: Boolean get() = hasDefaultSizes && hasDefaultOrder

    /** True when no width and no height differs from the defaults, whatever the order. */
    val hasDefaultSizes: Boolean get() = columnWidths.isEmpty() && rowHeights.isEmpty()

    companion object {
        /** Nothing chosen: the table as it has always been drawn. */
        val Default: TableSizes = TableSizes()
    }
}

/** The order the table has always drawn its columns in: Oyun, Eksik, then the six cells. */
val DEFAULT_COLUMN_ORDER: List<TableColumn> = TableColumn.entries.toList()

/** The width a column is drawn at when nobody has chosen one. */
fun defaultWidthOf(column: TableColumn): Float =
    if (column == TableColumn.GAME_NAME) DEFAULT_GAME_COLUMN_WIDTH_DP else DEFAULT_CELL_COLUMN_WIDTH_DP

/** The width fitting a column to content may use, held between both bounds. */
fun automaticWidthFor(measuredDp: Float): Float = measuredDp.coerceIn(MINIMUM_COLUMN_WIDTH_DP, AUTOMATIC_WIDTH_LIMIT_DP)

/**
 * The sizes file, exactly as it is written and read.
 *
 * The columns are named rather than numbered so a file survives a column being
 * added or reordered, and the rows are keyed by the game's own identifier.
 */
@Serializable
internal data class TableSizesDocumentV1(
    val formatVersion: Int,
    val columnWidths: Map<String, Float>,
    val rowHeights: Map<String, Float>,
    /**
     * The columns in the order they are drawn, by name; absent or null when it
     * is the table's own order. Added to the same version because it is
     * optional: a file written before it existed reads as the default order,
     * and an older build reading a newer file ignores it.
     */
    val columnOrder: List<String>? = null,
)

/**
 * The one reader and writer of the sizes document.
 *
 * The flags are the settings document's, for the settings document's reason
 * (PLAN 14.4.12): this file carries no data of the user's, so a newer build's
 * extra field must not stop an older build from starting. Compact, so what is
 * written is the document the contract shows.
 */
internal val tableSizesJson: Json =
    Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        isLenient = false
        allowComments = false
        allowTrailingComma = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

/**
 * Reads [text] as a sizes document, and says what to draw with.
 *
 * Kept away from any file so the whole decision can be tested without one. Every
 * unusable document ends at the same place — the defaults, with a reason —
 * because there is one safe thing to do with a layout that cannot be understood,
 * and that is to draw the table the way a fresh machine draws it.
 *
 * A size that is inside the document but outside the bounds is brought back
 * inside them. Leaving the file alone is a promise about the disk, not a reason
 * to draw a column four pixels wide.
 */
fun tableSizesIn(text: String): TableSizes {
    val document =
        try {
            tableSizesJson.decodeFromString<TableSizesDocumentV1>(text)
        } catch (notOurDocument: Exception) {
            // Deliberately broad, and deliberately only here: deserialisation
            // answers one question — "is this our document" — with several
            // unrelated exception types, and the answer to all of them is the
            // same.
            return TableSizes(problem = TableSizesProblem.NOT_THE_EXPECTED_SHAPE)
        }
    if (document.formatVersion != TABLE_SIZES_FORMAT_VERSION) {
        return TableSizes(problem = TableSizesProblem.VERSION_NOT_SUPPORTED)
    }
    val columnNames = TableColumn.entries.associateBy { it.name }
    var sizes = TableSizes.Default
    document.columnWidths.forEach { (name, width) ->
        val column = columnNames[name] ?: return TableSizes(problem = TableSizesProblem.NOT_THE_EXPECTED_SHAPE)
        sizes = sizes.withColumn(column, width)
    }
    document.rowHeights.forEach { (id, height) ->
        val gameId =
            try {
                EntityId.parse(id)
            } catch (notAnIdentifier: IllegalArgumentException) {
                return TableSizes(problem = TableSizesProblem.NOT_THE_EXPECTED_SHAPE)
            }
        sizes = sizes.withRow(gameId, height)
    }
    document.columnOrder?.let { names ->
        // Every column exactly once, or the document is not one this writes.
        val order = names.map { name -> columnNames[name] ?: return TableSizes(problem = TableSizesProblem.NOT_THE_EXPECTED_SHAPE) }
        if (order.size != TableColumn.entries.size || order.toSet() != TableColumn.entries.toSet()) {
            return TableSizes(problem = TableSizesProblem.NOT_THE_EXPECTED_SHAPE)
        }
        sizes = sizes.copy(columnOrder = order)
    }
    return sizes
}

/**
 * The document this writes for [sizes], as the file will hold it.
 *
 * Only what somebody chose is written; a column back at its default is already
 * gone by [TableSizes.withColumn], so the file of a table nobody resized holds
 * two empty maps and nothing else.
 */
fun tableSizesDocumentFor(sizes: TableSizes): String =
    tableSizesJson.encodeToString(
        TableSizesDocumentV1(
            formatVersion = TABLE_SIZES_FORMAT_VERSION,
            columnWidths = sizes.columnWidths.entries.associate { (column, width) -> column.name to width },
            rowHeights = sizes.rowHeights.entries.associate { (gameId, height) -> gameId.toString() to height },
            columnOrder = sizes.columnOrder.takeUnless { sizes.hasDefaultOrder }?.map { it.name },
        ),
    )

/**
 * How wide each column is drawn when its content is taken into account (PLAN 12.17).
 *
 * [chosen] is the width the user gave a column, or its default; [needed] is how
 * wide the column would have to be for its longest line to sit on one line. A
 * column is never drawn narrower than what was chosen, and it grows towards what
 * it needs — but only into room the window has left over, [available] wide in
 * all. That room is shared out fairly: a column that needs a little gets all of
 * it, and whatever the columns that need a lot are left with is split evenly
 * between them. So one very long note cannot push every other column off the
 * screen, and when the chosen widths alone are wider than the window nothing
 * grows at all and the table scrolls sideways as it always has.
 *
 * Nothing here is written down. It is recomputed from the content, the window
 * and the text size every time one of them changes.
 */
fun fittedWidths(
    chosen: Map<TableColumn, Float>,
    needed: Map<TableColumn, Float>,
    available: Float,
): Map<TableColumn, Float> {
    val wants =
        chosen
            .mapValues { (column, width) -> (minOf(needed[column] ?: 0f, AUTOMATIC_WIDTH_LIMIT_DP) - width).coerceAtLeast(0f) }
            .filterValues { it > 0f }
    var room = (available - chosen.values.sum()).coerceAtLeast(0f)
    val given = mutableMapOf<TableColumn, Float>()
    val waiting = wants.entries.sortedBy { it.value }.toMutableList()
    while (waiting.isNotEmpty() && room > 0f) {
        val share = room / waiting.size
        val (column, want) = waiting.removeAt(0)
        val granted = minOf(want, share)
        given[column] = granted
        room -= granted
    }
    return chosen.mapValues { (column, width) -> width + (given[column] ?: 0f) }
}
