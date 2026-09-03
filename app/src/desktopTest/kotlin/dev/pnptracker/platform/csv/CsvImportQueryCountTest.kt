package dev.pnptracker.platform.csv

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.CountingSqliteDriver
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.domain.csv.CsvDelimiter
import dev.pnptracker.domain.csv.parseCsvRecords
import dev.pnptracker.domain.importprep.readCsvWorkbook
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.platform.importfiles.ImportFilePicker
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What importing a CSV costs the database, counted at the driver.
 *
 * The writes grow with the file, and they are meant to: forty-two rows are
 * forty-two raw blocks and that is the work itself. What may not grow is the
 * number of questions asked to decide anything. A reader that asked "does this
 * game exist?" once per row would look fine on a file of three and be unusable
 * on a file of a thousand, which is the shape PLAN 16 rules out.
 *
 * Counted with a frequency map rather than a set, because a set would fold
 * forty-two runs of one statement into one and report a cost nobody paid.
 */
class CsvImportQueryCountTest {
    private lateinit var fileDirectory: Path
    private lateinit var databaseDirectory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = CountingSqliteDriver()
    private var realDatabaseExistedBefore = false

    private class FixedPicker(
        private val file: Path,
    ) : ImportFilePicker {
        override suspend fun chooseImportFile(): Path = file
    }

    @BeforeTest
    fun setUp() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        fileDirectory = Files.createTempDirectory("pnp-csv-count")
        databaseDirectory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(databaseDirectory.databaseFile)
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        database.close()
        databaseDirectory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        databaseDirectory.delete()
        check(fileDirectory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $fileDirectory, which is not under the temporary directory"
        }
        fileDirectory.deleteRecursively()
    }

    private fun csvOf(rows: Int): String =
        buildString {
            append("game,source_type,raw_text\n")
            repeat(rows) { at -> append("Oyun $at,3d,Parça $at\n") }
        }

    private fun csvFile(
        name: String,
        text: String,
    ): Path = fileDirectory.resolve(name).also { Files.write(it, text.toByteArray(StandardCharsets.UTF_8)) }

    private fun ran(recorded: List<String>): Map<String, Int> =
        recorded
            .filterNot { "room_table_modification" in it.lowercase() }
            .map { it.trimStart().uppercase().replace(Regex("\\s+"), " ") }
            .filterNot { it.startsWith("BEGIN") || it.startsWith("COMMIT") || it.startsWith("END") }
            .filterNot { it.startsWith("ROLLBACK") || it.startsWith("SAVEPOINT") || it.startsWith("RELEASE") }
            .filterNot { it.startsWith("PRAGMA") }
            .groupingBy { statement ->
                when {
                    "CHANGES()" in statement || "LAST_INSERT_ROWID()" in statement -> "write result"
                    statement.startsWith("SELECT") && "FROM IMPORT_BATCHES" in statement -> "SELECT import_batches"
                    statement.startsWith("SELECT") && "FROM RAW_IMPORT_BLOCKS" in statement -> "SELECT raw_import_blocks"
                    statement.startsWith("SELECT") && "FROM GAMES" in statement -> "SELECT games"
                    statement.startsWith("SELECT") && "FROM GAME_CELLS" in statement -> "SELECT game_cells"
                    statement.startsWith("SELECT") && "FROM CELL_SEGMENTS" in statement -> "SELECT cell_segments"
                    statement.startsWith("SELECT") && "FROM TASKS" in statement -> "SELECT tasks"
                    statement.startsWith("SELECT") -> "SELECT other"
                    statement.startsWith("INSERT") && "`RAW_IMPORT_BLOCKS`" in statement -> "INSERT raw_import_blocks"
                    statement.startsWith("INSERT") && "`IMPORT_BATCHES`" in statement -> "INSERT import_batches"
                    statement.startsWith("INSERT") -> "INSERT other"
                    statement.startsWith("UPDATE") -> "UPDATE"
                    else -> "other"
                }
            }.eachCount()

    /** Every question asked to decide something, whatever table it was asked of. */
    private fun decisions(counted: Map<String, Int>) = counted.filterKeys { it.startsWith("SELECT") }

    private suspend fun importOf(rows: Int): Map<String, Int> {
        val file = csvFile("n$rows.csv", csvOf(rows))
        val controller = ImportController(DesktopImportFileGateway(FixedPicker(file)), ImportDraftStore(database.importDao()))
        // Opening the connection and reading the schema is the database waking
        // up, not a question this import asked; it happens before the counting.
        database.importDao().allBatches()
        driver.start()
        controller.chooseFile()
        controller.saveDraft()
        val counted = ran(driver.stop())
        assertIs<ImportScreenState.Saved>(controller.state)
        return counted
    }

    @Test
    fun `one row and forty-two rows ask exactly the same questions`() =
        runBlocking<Unit> {
            val one = importOf(1)
            val fortyTwo = importOf(42)

            assertEquals(
                decisions(one),
                decisions(fortyTwo),
                "reading a longer file asked more questions, so the cost grows with the file",
            )
        }

    @Test
    fun `nothing is asked once per row, or once per game`() =
        runBlocking<Unit> {
            val fortyTwo = importOf(42)

            // Forty-two rows, forty-two different game names, and not one look-up
            // for any of them: the reader never touches the database.
            assertEquals(emptyMap(), decisions(fortyTwo).filterKeys { it != "SELECT import_batches" })
            assertEquals(1, decisions(fortyTwo)["SELECT import_batches"], "the repeat-import check is asked once, not per row")
        }

    @Test
    fun `the writes are the rows themselves and are made in one go`() =
        runBlocking<Unit> {
            val one = importOf(1)
            val fortyTwo = importOf(42)

            // One row is a game cell and a work cell: two blocks.
            assertEquals(2, one["INSERT raw_import_blocks"])
            assertEquals(84, fortyTwo["INSERT raw_import_blocks"])
            assertEquals(1, one["INSERT import_batches"])
            assertEquals(1, fortyTwo["INSERT import_batches"])
        }

    @Test
    fun `reading the game table after a CSV import still costs four statements`() =
        runBlocking<Unit> {
            importOf(42)
            val table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao())

            driver.start()
            table.observeTable().first()
            val counted = ran(driver.stop())

            assertEquals(4, counted.values.sum(), "the game table stopped costing four fixed statements: $counted")
        }

    // -------------------------------------------------------- the reader alone

    @Test
    fun `the reader itself never opens the database`() =
        runBlocking<Unit> {
            val text = csvOf(420)

            driver.start()
            val reading = readCsvWorkbook("büyük.csv", text)
            val counted = ran(driver.stop())

            assertEquals(emptyMap(), counted, "reading a CSV ran statements: $counted")
            assertEquals(CsvDelimiter.COMMA, reading.delimiter)
            assertEquals(
                840,
                reading.workbook.sheets
                    .single()
                    .cells.size - 7,
            )
        }

    @Test
    fun `a file of ten thousand rows is read in time proportional to its length`() {
        val small = csvOf(1_000)
        val large = csvOf(10_000)
        // Warm the code paths so the measurement is of the reading, not of the
        // first time this class was ever run.
        repeat(3) { parseCsvRecords(small, CsvDelimiter.COMMA) }

        val smallNanos = timeOf { parseCsvRecords(small, CsvDelimiter.COMMA) }
        val largeNanos = timeOf { parseCsvRecords(large, CsvDelimiter.COMMA) }

        assertEquals(10_001, parseCsvRecords(large, CsvDelimiter.COMMA).size)
        // Ten times the file for well under a hundred times the work. A quadratic
        // reader — one building each field by repeated concatenation, say — would
        // be a hundredfold here and is what this is watching for; the bound is
        // deliberately loose so a busy machine cannot fail it on its own.
        assertTrue(
            largeNanos < smallNanos * 40 + 200_000_000,
            "reading ten times as much took ${largeNanos / 1_000_000}ms against ${smallNanos / 1_000_000}ms",
        )
    }

    private fun timeOf(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }
}
