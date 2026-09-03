package dev.pnptracker.domain.export

import dev.pnptracker.domain.csv.BYTE_ORDER_MARK
import dev.pnptracker.domain.csv.CsvDelimiter
import dev.pnptracker.domain.csv.parseCsvRecords
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.PoolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each of the eight columns holds, for one task at a time.
 *
 * Read back through the CSV parser wherever the point is that a value survived,
 * because "the field looks right in the file" and "the field comes back" are two
 * different claims and only the second one matters to whoever opens it.
 */
class TaskCsvTest {
    private val names =
        TaskExportNames(
            pools =
                mapOf(
                    PoolType.THREE_D to "3D Baskı",
                    PoolType.CARD to "Kart",
                    PoolType.BOARD to "Mukavva",
                    PoolType.SPECIAL to "Özel",
                ),
            columns =
                mapOf(
                    CellColumnType.THREE_D to "3D Baskı",
                    CellColumnType.CARD to "Kart",
                    CellColumnType.BOARD to "Mukavva",
                    CellColumnType.SPECIAL to "Özel",
                    CellColumnType.NOTES to "Notlar",
                ),
        )

    private fun task(
        gameName: String = "Harmonies",
        columnType: CellColumnType = CellColumnType.THREE_D,
        taskName: String = "Kırmızı ev",
        poolType: PoolType = PoolType.THREE_D,
        colorNames: List<String> = emptyList(),
        requiredQuantity: Int? = 12,
        status: TaskExportStatus = TaskExportStatus.OPEN,
        notes: String? = null,
    ) = ExportedTask(gameName, columnType, taskName, poolType, colorNames, requiredQuantity, status, notes)

    private fun rowsOf(vararg tasks: ExportedTask): List<List<String>> =
        parseCsvRecords(taskCsvOf(tasks.toList(), names).removePrefix(BYTE_ORDER_MARK.toString()), CsvDelimiter.COMMA)
            .map { it.fields }

    private fun cellsOf(task: ExportedTask): Map<String, String> = TASK_EXPORT_HEADER.zip(rowsOf(task)[1]).toMap()

    // ----------------------------------------------------------- the heading

    @Test
    fun `the heading is the eight columns PLAN names, in PLAN's order`() {
        assertEquals(
            listOf("game", "column", "task", "pool", "colors", "required_quantity", "status", "notes"),
            TASK_EXPORT_HEADER,
        )
        assertEquals(TASK_EXPORT_HEADER, rowsOf(task()).first())
    }

    @Test
    fun `a file with no tasks is still a file with a heading`() {
        assertEquals(BYTE_ORDER_MARK + "game,column,task,pool,colors,required_quantity,status,notes\r\n", taskCsvOf(emptyList(), names))
    }

    // ------------------------------------------------------------ the fields

    @Test
    fun `an ordinary task fills every column`() {
        val cells =
            cellsOf(
                task(
                    gameName = "Harmonies",
                    taskName = "Kırmızı ev",
                    colorNames = listOf("Kırmızı"),
                    requiredQuantity = 12,
                    notes = "sonra bakılacak",
                ),
            )

        assertEquals("Harmonies", cells["game"])
        assertEquals("3D Baskı", cells["column"])
        assertEquals("Kırmızı ev", cells["task"])
        assertEquals("3D Baskı", cells["pool"])
        assertEquals("Kırmızı", cells["colors"])
        assertEquals("12", cells["required_quantity"])
        assertEquals("açık", cells["status"])
        assertEquals("sonra bakılacak", cells["notes"])
    }

    @Test
    fun `the three statuses are written in the three words PLAN names`() {
        assertEquals("açık", cellsOf(task(status = TaskExportStatus.OPEN))["status"])
        assertEquals("tamamlandı", cellsOf(task(status = TaskExportStatus.COMPLETED))["status"])
        assertEquals("bilgi eksik", cellsOf(task(status = TaskExportStatus.NEEDS_INFO))["status"])
    }

    @Test
    fun `a count is digits and nothing else`() {
        assertEquals("1000", cellsOf(task(requiredQuantity = 1000))["required_quantity"])
        assertEquals("", cellsOf(task(requiredQuantity = null))["required_quantity"], "an unknown count is not a zero")
    }

    @Test
    fun `no note and an empty note are both an empty cell`() {
        assertEquals("", cellsOf(task(notes = null))["notes"])
        assertEquals("", cellsOf(task(notes = ""))["notes"])
    }

    @Test
    fun `a note keeps its commas, its quotes and its line endings`() {
        val note = "kırmızı, mavi\r\n\"iki\" satır"

        assertEquals(note, cellsOf(task(notes = note))["notes"])
    }

    @Test
    fun `a note is not trimmed`() {
        assertEquals("  boşluklu  ", cellsOf(task(notes = "  boşluklu  "))["notes"])
    }

    // ------------------------------------------------------------- colours

    @Test
    fun `a task with no colour has an empty colours cell`() {
        assertEquals("", cellsOf(task(colorNames = emptyList()))["colors"], "`Renk seçilecek` is a heading, not a colour")
    }

    @Test
    fun `a task with one colour has that colour and nothing else`() {
        assertEquals("Kırmızı", cellsOf(task(colorNames = listOf("Kırmızı")))["colors"])
    }

    @Test
    fun `a task in three colours is one row, in the user's own order`() {
        val rows = rowsOf(task(taskName = "Yarasa", colorNames = listOf("Kırmızı", "Sarı", "Siyah"), requiredQuantity = 10))

        assertEquals(2, rows.size, "a multi-coloured task was written once per colour")
        val cells = TASK_EXPORT_HEADER.zip(rows[1]).toMap()
        assertEquals("Kırmızı|Sarı|Siyah", cells["colors"])
        assertEquals("Yarasa", cells["task"])
        assertEquals("10", cells["required_quantity"], "the count was repeated per colour")
    }

    @Test
    fun `a colour named with a bar keeps it, and keeps it apart from the separator`() {
        val cells = cellsOf(task(colorNames = listOf("Mavi|Yeşil", "Sarı")))

        assertEquals("Mavi\\|Yeşil|Sarı", cells["colors"])
        assertEquals(listOf("Mavi|Yeşil", "Sarı"), colorNamesIn(cells.getValue("colors")))
    }

    @Test
    fun `a colour named with a backslash keeps it`() {
        val cells = cellsOf(task(colorNames = listOf("A\\B", "C")))

        assertEquals("A\\\\B|C", cells["colors"])
        assertEquals(listOf("A\\B", "C"), colorNamesIn(cells.getValue("colors")))
    }

    @Test
    fun `a colour named with both survives being read back`() {
        val awkward = listOf("a\\|b", "c|d", "e\\f")

        val cells = cellsOf(task(colorNames = awkward))

        assertEquals(awkward, colorNamesIn(cells.getValue("colors")))
    }

    /** Reads a `colors` cell back, which is what any reader of the file has to do. */
    private fun colorNamesIn(cell: String): List<String> {
        if (cell.isEmpty()) return emptyList()
        val names = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < cell.length) {
            val character = cell[index]
            when {
                character == '\\' && index + 1 < cell.length -> {
                    current.append(cell[index + 1])
                    index += 2
                }

                character == '|' -> {
                    names += current.toString()
                    current.clear()
                    index++
                }

                else -> {
                    current.append(character)
                    index++
                }
            }
        }
        names += current.toString()
        return names
    }

    // -------------------------------------------------- the spreadsheet guard

    @Test
    fun `a task named like a formula is written as text`() {
        val cells = cellsOf(task(gameName = "=1+1", taskName = "+SUM(A1:A2)", notes = "-2+3", colorNames = listOf("@IMPORT")))

        assertEquals("'=1+1", cells["game"])
        assertEquals("'+SUM(A1:A2)", cells["task"])
        assertEquals("'-2+3", cells["notes"])
        assertEquals("'@IMPORT", cells["colors"])
    }

    @Test
    fun `the count is a number and is never given a marker`() {
        assertTrue(!cellsOf(task(requiredQuantity = 12)).getValue("required_quantity").startsWith("'"))
    }

    @Test
    fun `ordinary words get no marker`() {
        val cells = cellsOf(task(gameName = "Harmonies", taskName = "Kırmızı ev", notes = "5 adet"))

        listOf("game", "task", "notes", "pool", "column", "status").forEach { column ->
            assertTrue(!cells.getValue(column).startsWith("'"), "`$column` was marked without needing to be")
        }
    }

    // -------------------------------------------------------------- the file

    @Test
    fun `the same tasks always give the same bytes`() {
        val tasks =
            listOf(
                task(gameName = "Harmonies", taskName = "Kırmızı ev", colorNames = listOf("Kırmızı")),
                task(gameName = "Wingspan", taskName = "Yuva", columnType = CellColumnType.CARD, poolType = PoolType.CARD),
            )

        assertEquals(taskCsvOf(tasks, names), taskCsvOf(tasks, names))
    }

    @Test
    fun `the order the tasks arrive in is the order they are written in`() {
        val rows =
            rowsOf(
                task(gameName = "Bir"),
                task(gameName = "İki"),
                task(gameName = "Üç"),
            )

        assertEquals(listOf("Bir", "İki", "Üç"), rows.drop(1).map { it.first() })
    }
}
