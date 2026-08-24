package dev.pnptracker.data.database

/**
 * How SQL sorts the columns of the game table.
 *
 * `ORDER BY column_type` sorts the stored text, which puts `BOARD` first and
 * `THREE_D` last — an order that comes from the spelling of the constants rather
 * than from anything the user recognises. This puts them in the order
 * `CellColumnType` declares, which is the order the table is read in.
 *
 * It is a constant so that every query naming it sorts the same way and so that
 * a column added later has one place to be placed in. `CellColumnOrderTest`
 * checks it against `CellColumnType.entries`, so the two cannot drift apart.
 *
 * The column is qualified because every query using this joins `game_cells` to
 * something else.
 */
internal const val CELL_COLUMN_DISPLAY_ORDER: String =
    "CASE game_cells.column_type " +
        "WHEN 'THREE_D' THEN 0 " +
        "WHEN 'CARD' THEN 1 " +
        "WHEN 'BOARD' THEN 2 " +
        "WHEN 'SPECIAL' THEN 3 " +
        "WHEN 'NOTES' THEN 4 " +
        "ELSE 5 END"
