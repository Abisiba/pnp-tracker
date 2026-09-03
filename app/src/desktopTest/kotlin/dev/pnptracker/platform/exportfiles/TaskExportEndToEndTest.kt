package dev.pnptracker.platform.exportfiles

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.data.repository.TaskExportStore
import dev.pnptracker.domain.csv.BYTE_ORDER_MARK
import dev.pnptracker.domain.csv.CsvDelimiter
import dev.pnptracker.domain.csv.parseCsvRecords
import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.TASK_EXPORT_HEADER
import dev.pnptracker.domain.export.TaskExportNames
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.ui.feature.export.ExportController
import dev.pnptracker.ui.feature.export.ExportScreenState
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A real database, a real save dialog stand-in, a real file on a real disk.
 *
 * What is checked here is the join: that what the store reads, the writer writes
 * and a reader can get back out again — the same round trip anybody opening the
 * file in a spreadsheet is about to make.
 */
class TaskExportEndToEndTest {
    private lateinit var fileDirectory: Path
    private lateinit var databaseDirectory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private class FixedPicker(
        private val destination: Path?,
    ) : ExportFilePicker {
        var calls = 0
            private set

        override suspend fun chooseDestination(suggestedName: String): Path? {
            calls++
            return destination
        }
    }

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

    @BeforeTest
    fun setUp() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        fileDirectory = Files.createTempDirectory("pnp-export-e2e")
        databaseDirectory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(databaseDirectory.databaseFile)
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

    private fun controllerFor(destination: Path?): ExportController =
        ExportController(
            gateway = DesktopExportFileGateway(FixedPicker(destination)),
            tasks = TaskExportStore(database.taskExportDao()),
            names = { names },
        )

    private var madeColors = 0

    private suspend fun aColor(name: String): EntityId {
        database
            .colorDao()
            .allColors()
            .firstOrNull { it.canonicalName == name }
            ?.let { return it.id }
        val at = madeColors++
        val color = ColorEntity.of(IdGenerator.Random.newId(), name, "#%06X".format(at * 1237 + 1), 300 + at)
        database.colorDao().insert(color)
        return color.id
    }

    private suspend fun aGameWithCells(name: String): Map<CellColumnType, EntityId> {
        val game = aGame(name = name)
        database.gameDao().insert(game)
        return CellColumnType.entries.associateWith { column ->
            val cell = aCell(gameId = game.id, columnType = column)
            database.gameCellDao().insert(cell)
            cell.id
        }
    }

    private suspend fun aTaskIn(
        cellId: EntityId,
        name: String,
        poolType: PoolType = PoolType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        quantity: Int? = 12,
        notes: String? = null,
        isCompleted: Boolean = false,
        needsInfo: Boolean = false,
        colorIds: List<EntityId> = emptyList(),
    ): EntityId {
        val task =
            aTask(poolType = poolType, trackingMode = trackingMode, name = name, requiredQuantity = quantity)
                .copy(
                    notes = notes,
                    isCompleted = isCompleted,
                    completedAt = if (isCompleted) updatedAt else null,
                    needsInfo = needsInfo,
                )
        database.taskDao().addTaskToCell(task, cellId, IdGenerator.Random.newId(), createdAt)
        colorIds.forEach { database.taskColorDao().addColorToTask(task.id, it) }
        return task.id
    }

    private fun target(name: String = "gorevler.csv"): Path = fileDirectory.resolve(name)

    private fun recordsIn(file: Path): List<List<String>> {
        val text = Files.readString(file, StandardCharsets.UTF_8)
        assertTrue(text.startsWith(BYTE_ORDER_MARK), "the file has no byte order mark")
        return parseCsvRecords(text.removePrefix(BYTE_ORDER_MARK.toString()), CsvDelimiter.COMMA).map { it.fields }
    }

    private fun cellsIn(
        file: Path,
        taskName: String,
    ): Map<String, String> {
        val records = recordsIn(file)
        val row = records.drop(1).first { TASK_EXPORT_HEADER.zip(it).toMap()["task"] == taskName }
        return TASK_EXPORT_HEADER.zip(row).toMap()
    }

    // ------------------------------------------------------- the whole journey

    @Test
    fun `a real library becomes a real file that reads back`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            val red = aColor("Kırmızı")
            val yellow = aColor("Sarı")
            val black = aColor("Siyah")
            aTaskIn(cells.getValue(CellColumnType.THREE_D), "Yarasa", quantity = 10, colorIds = listOf(red, yellow, black))
            aTaskIn(cells.getValue(CellColumnType.THREE_D), "Renksiz ev", quantity = 3)
            aTaskIn(
                cells.getValue(CellColumnType.CARD),
                "Deste",
                PoolType.CARD,
                TrackingMode.PIPELINE,
                quantity = 55,
                notes = "kırmızı, mavi\r\n\"iki\" satır",
                isCompleted = true,
            )
            val file = target()
            val controller = controllerFor(file)

            controller.exportTasks()

            val written = assertIs<ExportScreenState.Written>(controller.state)
            assertEquals(3, written.taskCount)
            assertEquals("gorevler.csv", written.fileName)

            val records = recordsIn(file)
            assertEquals(TASK_EXPORT_HEADER, records.first())
            assertEquals(4, records.size, "one heading and one row per task")

            val yarasa = cellsIn(file, "Yarasa")
            assertEquals("Harmonies", yarasa["game"])
            assertEquals("3D Baskı", yarasa["column"])
            assertEquals("3D Baskı", yarasa["pool"])
            assertEquals("Kırmızı|Sarı|Siyah", yarasa["colors"])
            assertEquals("10", yarasa["required_quantity"])
            assertEquals("açık", yarasa["status"])
            assertEquals("", yarasa["notes"])

            assertEquals("", cellsIn(file, "Renksiz ev")["colors"])

            val deste = cellsIn(file, "Deste")
            assertEquals("Kart", deste["column"])
            assertEquals("tamamlandı", deste["status"])
            assertEquals("kırmızı, mavi\r\n\"iki\" satır", deste["notes"], "the note did not survive the round trip")
        }

    @Test
    fun `Turkish letters and emoji survive being written and read`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("IŞIKLI ŞEHİR 😀")
            aTaskIn(
                cells.getValue(CellColumnType.THREE_D),
                "İsim ığüşöç 👨‍👩‍👧‍👦 🇹🇷",
                notes = "not: 👍🏽",
            )
            val file = target()

            controllerFor(file).exportTasks()

            val row = recordsIn(file)[1]
            assertEquals("IŞIKLI ŞEHİR 😀", row[0])
            assertEquals("İsim ığüşöç 👨‍👩‍👧‍👦 🇹🇷", row[2])
            assertEquals("not: 👍🏽", row[7])
        }

    @Test
    fun `a task named like a formula is written as text`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("=OYUN")
            aTaskIn(cells.getValue(CellColumnType.THREE_D), "+SUM(A1:A2)", notes = "-2+3")
            val file = target()

            controllerFor(file).exportTasks()

            val row = recordsIn(file)[1]
            assertEquals("'=OYUN", row[0])
            assertEquals("'+SUM(A1:A2)", row[2])
            assertEquals("'-2+3", row[7])
        }

    @Test
    fun `a deleted task is not in the file`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            val threeD = cells.getValue(CellColumnType.THREE_D)
            aTaskIn(threeD, "Kalan")
            val gone = aTaskIn(threeD, "Silinen")
            database.taskDao().softDelete(gone, updatedAt)
            val file = target()

            controllerFor(file).exportTasks()

            assertEquals(listOf("Kalan"), recordsIn(file).drop(1).map { it[2] })
        }

    @Test
    fun `the same database always writes the same bytes`() =
        runBlocking<Unit> {
            val cells = aGameWithCells("Harmonies")
            repeat(8) { at -> aTaskIn(cells.getValue(CellColumnType.THREE_D), "Parça $at") }
            val first = target("bir.csv")
            val second = target("iki.csv")

            controllerFor(first).exportTasks()
            controllerFor(second).exportTasks()

            assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second))
        }

    // ------------------------------------------------------------ the file name

    @Test
    fun `a name with no extension is written as a csv`() =
        runBlocking<Unit> {
            aTaskIn(aGameWithCells("Harmonies").getValue(CellColumnType.THREE_D), "Ev")

            val controller = controllerFor(target("gorevler"))
            controller.exportTasks()

            assertEquals("gorevler.csv", assertIs<ExportScreenState.Written>(controller.state).fileName)
            assertTrue(Files.exists(target("gorevler.csv")))
            assertTrue(!Files.exists(target("gorevler")), "a file without the extension was made as well")
        }

    @Test
    fun `an extension in capitals is not doubled`() =
        runBlocking<Unit> {
            aTaskIn(aGameWithCells("Harmonies").getValue(CellColumnType.THREE_D), "Ev")

            val controller = controllerFor(target("GOREVLER.CSV"))
            controller.exportTasks()

            assertEquals("GOREVLER.CSV", assertIs<ExportScreenState.Written>(controller.state).fileName)
            assertTrue(!Files.exists(target("GOREVLER.CSV.csv")))
        }

    @Test
    fun `a name this does not write is refused, and nothing is made`() =
        runBlocking<Unit> {
            aTaskIn(aGameWithCells("Harmonies").getValue(CellColumnType.THREE_D), "Ev")

            val controller = controllerFor(target("gorevler.xlsx"))
            controller.exportTasks()

            assertEquals(ExportFailure.UNSUPPORTED_FILE_TYPE, assertIs<ExportScreenState.Failed>(controller.state).failure)
            assertEquals(emptyList(), Files.list(fileDirectory).use { it.toList() })
        }

    @Test
    fun `changing one's mind makes no file at all`() =
        runBlocking<Unit> {
            aTaskIn(aGameWithCells("Harmonies").getValue(CellColumnType.THREE_D), "Ev")

            val controller = controllerFor(destination = null)
            controller.exportTasks()

            assertEquals(ExportScreenState.Idle, controller.state)
            assertEquals(emptyList(), Files.list(fileDirectory).use { it.toList() })
        }

    // ------------------------------------------------------------ overwriting

    @Test
    fun `an existing file is kept until the user agrees to replace it`() =
        runBlocking<Unit> {
            aTaskIn(aGameWithCells("Harmonies").getValue(CellColumnType.THREE_D), "Ev")
            val file = target()
            Files.write(file, "eski".toByteArray(StandardCharsets.UTF_8))
            val before = Files.readAllBytes(file)
            val controller = controllerFor(file)

            controller.exportTasks()

            assertIs<ExportScreenState.ConfirmingOverwrite>(controller.state)
            assertContentEquals(before, Files.readAllBytes(file), "the file was replaced before anybody agreed")

            controller.cancelOverwrite()
            assertContentEquals(before, Files.readAllBytes(file), "saying no still replaced the file")

            controller.exportTasks()
            controller.confirmOverwrite()
            assertIs<ExportScreenState.Written>(controller.state)
            assertEquals(listOf("Ev"), recordsIn(file).drop(1).map { it[2] })
        }

    // --------------------------------------------------------- nothing to write

    @Test
    fun `an empty library makes no file and says why`() =
        runBlocking<Unit> {
            val controller = controllerFor(target())

            controller.exportTasks()

            assertEquals(ExportFailure.NOTHING_TO_EXPORT, assertIs<ExportScreenState.Failed>(controller.state).failure)
            assertEquals(emptyList(), Files.list(fileDirectory).use { it.toList() }, "an empty file was made")
        }

    @Test
    fun `an empty library leaves a file that is already there alone`() =
        runBlocking<Unit> {
            val file = target()
            Files.write(file, "eski".toByteArray(StandardCharsets.UTF_8))
            val before = Files.readAllBytes(file)

            controllerFor(file).exportTasks()

            assertContentEquals(before, Files.readAllBytes(file))
            assertEquals(listOf(file), Files.list(fileDirectory).use { it.toList() }, "a temporary file was left behind")
        }

    @Test
    fun `a message never carries a path`() =
        runBlocking<Unit> {
            val controller = controllerFor(target("gorevler.xlsx"))

            controller.exportTasks()

            val failed = assertIs<ExportScreenState.Failed>(controller.state)
            assertEquals(ExportFailure.UNSUPPORTED_FILE_TYPE, failed.failure)
            // Nothing in the state carries anywhere: the state holds a reason and
            // the handle holds a name, and neither holds a directory.
            assertTrue(fileDirectory.toString() !in failed.toString())
        }
}
