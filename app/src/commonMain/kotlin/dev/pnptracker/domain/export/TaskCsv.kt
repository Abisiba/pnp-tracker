package dev.pnptracker.domain.export

import dev.pnptracker.domain.csv.csvDocument
import dev.pnptracker.domain.csv.spreadsheetSafeText
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.PoolType

/** The eight columns PLAN 11.8 names, in the order PLAN names them. */
val TASK_EXPORT_HEADER: List<String> =
    listOf("game", "column", "task", "pool", "colors", "required_quantity", "status", "notes")

/** Separates the colours of one task inside the single `colors` cell. */
private const val COLOR_SEPARATOR = "|"

private const val ESCAPE = "\\"

/**
 * The words this file calls the pools and the columns by.
 *
 * Handed in rather than looked up, because the words belong to the interface's
 * own catalogue and reading that catalogue needs a coroutine and a resource
 * loader — neither of which belongs in a function whose whole value is that it
 * is pure and can be compared byte for byte in a test.
 *
 * There is no second dictionary here: what fills this comes from
 * `poolNameOf` and `columnNameOf`, the same two the game table and the pool
 * screens are drawn from.
 */
data class TaskExportNames(
    val pools: Map<PoolType, String>,
    val columns: Map<CellColumnType, String>,
) {
    init {
        require(PoolType.entries.all { it in pools }) { "Every pool needs a word before a file can name it" }
        require(CellColumnType.entries.all { it in columns }) { "Every column needs a word before a file can name it" }
    }
}

/**
 * The whole file, as text.
 *
 * The same tasks and the same words always give the same bytes: there is no
 * clock in it, no identifier, nothing from the file system and nothing that
 * depends on the order a map happened to be built in.
 *
 * Every text cell goes through [spreadsheetSafeText] before it is quoted, because
 * quoting is how CSV says where a field ends and says nothing at all about what
 * a spreadsheet will do with the value afterwards. `required_quantity` is a
 * number and is left alone.
 */
fun taskCsvOf(
    tasks: List<ExportedTask>,
    names: TaskExportNames,
): String =
    csvDocument(
        listOf(TASK_EXPORT_HEADER) +
            tasks.map { task ->
                listOf(
                    spreadsheetSafeText(task.gameName),
                    spreadsheetSafeText(names.columns.getValue(task.columnType)),
                    spreadsheetSafeText(task.taskName),
                    spreadsheetSafeText(names.pools.getValue(task.poolType)),
                    spreadsheetSafeText(colorsCellOf(task.colorNames)),
                    // A count is digits or nothing: no separator, no `×`, no
                    // unit. A reader that has to strip decoration is a reader
                    // that will one day strip a digit.
                    task.requiredQuantity?.toString().orEmpty(),
                    spreadsheetSafeText(task.status.word),
                    spreadsheetSafeText(task.notes.orEmpty()),
                )
            },
    )

/**
 * The colours of one task in one cell, and reversibly.
 *
 * A colour may be called anything the user likes, `Kırmızı | Sarı` and `A\B`
 * included, so the two characters that would otherwise be read as structure are
 * escaped: the backslash first, then the bar. Doing it the other way round would
 * escape the escape and lose the difference between `a|b` and `a\|b`.
 *
 * A task with no colour gives an empty cell. `Renk seçilecek` is what the pool
 * screen calls that group; it is a heading on a screen and not a colour, and
 * writing it here would put a word into a data file that names nothing.
 */
private fun colorsCellOf(colorNames: List<String>): String =
    colorNames.joinToString(COLOR_SEPARATOR) { name ->
        name.replace(ESCAPE, ESCAPE + ESCAPE).replace(COLOR_SEPARATOR, ESCAPE + COLOR_SEPARATOR)
    }
