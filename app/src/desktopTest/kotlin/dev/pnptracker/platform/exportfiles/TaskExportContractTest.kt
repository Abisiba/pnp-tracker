package dev.pnptracker.platform.exportfiles

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.FailingSqliteDriver
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.deletedAt
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.executeRawSql
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
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.ui.feature.export.ExportController
import dev.pnptracker.ui.feature.export.ExportScreenState
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The CSV export contract (master context §25), clause by clause, through the
 * path a user's export really takes: a real database, the real store, the real
 * controller, the real desktop gateway and the real atomic writer.
 *
 * `TaskCsvTest`, `TaskExportSnapshotTest`, `TaskExportStoreTest` and
 * `TaskExportEndToEndTest` already hold most clauses. What is here is what none
 * of them showed on a real file: the exact bytes, fields read from where they
 * are stored *now*, what progress and flags do not do to a row, a deleted game,
 * a library past a thousand tasks, and the failures that must leave the file
 * already at the destination byte for byte as it was.
 */
class TaskExportContractTest {
    private lateinit var fileDirectory: Path
    private lateinit var databaseDirectory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private val driver = FailingSqliteDriver()
    private var realDatabaseExistedBefore = false

    private class FixedPicker(
        private val destination: Path,
    ) : ExportFilePicker {
        override suspend fun chooseDestination(suggestedName: String): Path = destination
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
        fileDirectory = Files.createTempDirectory("pnp-export-contract")
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

    private fun controllerFor(
        file: Path,
        writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = ".csv.part"),
    ): ExportController =
        ExportController(
            gateway = DesktopExportFileGateway(FixedPicker(file), writer),
            tasks = TaskExportStore(database.taskExportDao()),
            names = { names },
        )

    private val target: Path get() = fileDirectory.resolve("gorevler.csv")

    private fun filesInDirectory(): List<Path> = Files.list(fileDirectory).use { stream -> stream.toList().sorted() }

    private var madeColors = 0

    private suspend fun aColor(name: String): EntityId {
        database
            .colorDao()
            .allColors()
            .firstOrNull { it.canonicalName == name }
            ?.let { return it.id }
        val at = madeColors++
        val color = ColorEntity.of(IdGenerator.Random.newId(), name, "#%06X".format(at * 7919 + 17), 500 + at)
        database.colorDao().insert(color)
        return color.id
    }

    private suspend fun aGameWithCells(name: String): Pair<EntityId, Map<CellColumnType, EntityId>> {
        val game = aGame(name = name)
        database.gameDao().insert(game)
        return game.id to
            CellColumnType.entries.associateWith { column ->
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
        isMissing: Boolean = false,
        isBorrowed: Boolean = false,
        colorIds: List<EntityId> = emptyList(),
    ): EntityId {
        val task =
            aTask(poolType = poolType, trackingMode = trackingMode, name = name, requiredQuantity = quantity)
                .copy(
                    notes = notes,
                    isCompleted = isCompleted,
                    completedAt = if (isCompleted) updatedAt else null,
                    needsInfo = needsInfo,
                    isMissing = isMissing,
                    isBorrowed = isBorrowed,
                )
        database.taskDao().addTaskToCell(task, cellId, IdGenerator.Random.newId(), createdAt)
        colorIds.forEach { database.taskColorDao().addColorToTask(task.id, it) }
        return task.id
    }

    private fun recordsIn(file: Path): List<List<String>> {
        val text = Files.readString(file, StandardCharsets.UTF_8)
        assertTrue(text.startsWith(BYTE_ORDER_MARK), "the file has no byte order mark")
        return parseCsvRecords(text.removePrefix(BYTE_ORDER_MARK.toString()), CsvDelimiter.COMMA).map { it.fields }
    }

    private fun rowsByTask(file: Path): Map<String, Map<String, String>> =
        recordsIn(file).drop(1).associate { row ->
            val cells = TASK_EXPORT_HEADER.zip(row).toMap()
            cells.getValue("task") to cells
        }

    // ------------------------------------------------------------ the bytes

    @Test
    fun `the file is exactly the bytes the contract describes`() =
        runBlocking<Unit> {
            val (_, cells) = aGameWithCells("Oyun, \"Özel\" Baskı")
            val threeD = cells.getValue(CellColumnType.THREE_D)
            aTaskIn(
                threeD,
                " -iki",
                quantity = 10,
                notes = "satır1\r\nsatır2",
                colorIds = listOf(aColor("Gri|Mat"), aColor("A\\B")),
            )
            aTaskIn(threeD, "Kırmızı ev", quantity = 3, notes = "", isCompleted = true, colorIds = listOf(aColor("Siyah")))
            aTaskIn(
                cells.getValue(CellColumnType.CARD),
                "Deste",
                PoolType.CARD,
                TrackingMode.PIPELINE,
                quantity = null,
                notes = "\tsekmeli",
                isCompleted = true,
                needsInfo = true,
            )

            val controller = controllerFor(target)
            controller.exportTasks()

            assertIs<ExportScreenState.Written>(controller.state)
            val game = "\"Oyun, \"\"Özel\"\" Baskı\""
            val expected =
                BYTE_ORDER_MARK.toString() +
                    "game,column,task,pool,colors,required_quantity,status,notes\r\n" +
                    "$game,3D Baskı,' -iki,3D Baskı,Gri\\|Mat|A\\\\B,10,açık,\"satır1\r\nsatır2\"\r\n" +
                    "$game,3D Baskı,Kırmızı ev,3D Baskı,Siyah,3,tamamlandı,\r\n" +
                    "$game,Kart,Deste,Kart,,,bilgi eksik,'\tsekmeli\r\n"
            val bytes = Files.readAllBytes(target)
            assertContentEquals(expected.toByteArray(StandardCharsets.UTF_8), bytes, String(bytes, StandardCharsets.UTF_8))
            assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF }, "the mark is not UTF-8's")
            assertEquals(listOf(target), filesInDirectory(), "something besides the file was left in the folder")
        }

    // ------------------------------------------------------------ the sources

    @Test
    fun `every field is read from where it is stored now`() =
        runBlocking<Unit> {
            val (gameId, cells) = aGameWithCells("Eski oyun adı")
            val grey = aColor("Açık gri")
            val task = aTaskIn(cells.getValue(CellColumnType.THREE_D), "Eski görev", notes = "eski not", colorIds = listOf(grey))

            // The game is renamed, the colour is renamed through the colour
            // screen's own path, and the task is edited through the task editor's.
            executeRawSql(database, "UPDATE games SET name = ? WHERE id = ?", "Yeni oyun adı", gameId.toString())
            database.colorDao().renameAndRecolorTheUserHasConfirmed(
                grey,
                expectedName = "Açık gri",
                expectedHex = database.colorDao().colorById(grey)!!.hex,
                canonicalName = "Duman grisi",
                hex = "#778899",
            )
            database.taskEditDao().editTask(
                taskId = task,
                name = "Yeni görev",
                colorIds = listOf(grey),
                requiredQuantity = 7,
                notes = "yeni not",
                trackingMode = TrackingMode.THREE_D_BATCH,
                flags = null,
                clock = StoppedClock(updatedAt),
            )

            controllerFor(target).exportTasks()

            val row = rowsByTask(target).getValue("Yeni görev")
            assertEquals("Yeni oyun adı", row["game"])
            assertEquals("Duman grisi", row["colors"])
            assertEquals("7", row["required_quantity"])
            assertEquals("yeni not", row["notes"])
            assertEquals(1, rowsByTask(target).size)
        }

    @Test
    fun `progress, stages and flags neither add rows nor make a status`() =
        runBlocking<Unit> {
            val (_, cells) = aGameWithCells("Harmonies")
            val threeD = cells.getValue(CellColumnType.THREE_D)
            val short = aTaskIn(threeD, "Eksikli", quantity = 10, colorIds = listOf(aColor("Kırmızı"), aColor("Sarı"), aColor("Siyah")))
            database.taskProgressDao().reportFailure(IdGenerator.Random.newId(), short, 3, StoppedClock(updatedAt))
            val deck = aTaskIn(cells.getValue(CellColumnType.CARD), "Deste", PoolType.CARD, TrackingMode.PIPELINE, quantity = 20)
            database.taskProgressDao().setStageQuantity(deck, ProductionStage.PRINT, 4, StoppedClock(updatedAt))
            aTaskIn(threeD, "Kayıp", isMissing = true)
            aTaskIn(threeD, "Ödünç", isBorrowed = true)

            assertTrue(database.taskProgressDao().progressEventsOfTask(short).isNotEmpty(), "the fixture recorded no progress")

            controllerFor(target).exportTasks()

            val records = recordsIn(target)
            assertEquals(1 + 4, records.size, "one heading and exactly one row per task: $records")
            val rows = rowsByTask(target)
            assertEquals(setOf("açık"), rows.values.map { it.getValue("status") }.toSet())
            assertEquals("Kırmızı|Sarı|Siyah", rows.getValue("Eksikli")["colors"], "a three-colour task is one row, colours in slot order")
            assertEquals("10", rows.getValue("Eksikli")["required_quantity"], "the count was recomputed from progress")
            assertEquals("20", rows.getValue("Deste")["required_quantity"])
        }

    @Test
    fun `a deleted game's tasks and a deleted task are left out of a real file`() =
        runBlocking<Unit> {
            val (_, kept) = aGameWithCells("Kalan oyun")
            aTaskIn(kept.getValue(CellColumnType.THREE_D), "Kalan görev")
            val gone = aTaskIn(kept.getValue(CellColumnType.THREE_D), "Silinen görev")
            database.taskDao().softDelete(gone, deletedAt)
            val (deletedGame, deleted) = aGameWithCells("Silinen oyun")
            aTaskIn(deleted.getValue(CellColumnType.THREE_D), "Silinen oyunun görevi", colorIds = listOf(aColor("Mavi")))
            database.gameDao().softDelete(deletedGame, deletedAt)

            controllerFor(target).exportTasks()

            assertEquals(listOf("Kalan görev"), recordsIn(target).drop(1).map { it[2] })
        }

    // -------------------------------------------------------------- the scale

    @Test
    fun `a library past a thousand tasks is every task once, and the same bytes twice`() =
        runBlocking<Unit> {
            val palette = listOf("Kırmızı", "Sarı", "Siyah", "Beyaz", "Mavi").map { aColor(it) }
            val expectedStatus = mutableMapOf<String, String>()
            var made = 0
            repeat(30) { game ->
                val (gameId, cells) = aGameWithCells("Oyun ${"ığüşöç".take(game % 6 + 1)} %02d".format(game))
                listOf(
                    Triple(CellColumnType.THREE_D, PoolType.THREE_D, TrackingMode.THREE_D_BATCH),
                    Triple(CellColumnType.CARD, PoolType.CARD, TrackingMode.PIPELINE),
                    Triple(CellColumnType.BOARD, PoolType.BOARD, TrackingMode.PIPELINE),
                    Triple(CellColumnType.SPECIAL, PoolType.SPECIAL, TrackingMode.CHECKLIST),
                ).forEach { (column, pool, mode) ->
                    repeat(10) {
                        val name = "Görev #$made, \"${column.name.lowercase()}\" 🎲"
                        val needsInfo = made % 7 == 0
                        val completed = made % 3 == 0
                        val id =
                            aTaskIn(
                                cells.getValue(column),
                                name,
                                pool,
                                mode,
                                quantity = if (needsInfo) null else made % 50 + 1,
                                notes = if (made % 4 == 0) "not $made\r\nikinci satır" else null,
                                isCompleted = completed,
                                needsInfo = needsInfo,
                                colorIds = if (pool == PoolType.THREE_D) palette.take(made % 3) else emptyList(),
                            )
                        if (made % 11 == 0) {
                            database.taskDao().softDelete(id, deletedAt)
                        } else if (game != 29) {
                            expectedStatus[name] =
                                if (needsInfo) {
                                    "bilgi eksik"
                                } else if (completed) {
                                    "tamamlandı"
                                } else {
                                    "açık"
                                }
                        }
                        made++
                    }
                }
                if (game == 29) database.gameDao().softDelete(gameId, deletedAt)
            }
            assertEquals(1_200, made)
            assertTrue(expectedStatus.size > 1_000, "the library is not past a thousand tasks: ${expectedStatus.size}")

            val first = fileDirectory.resolve("bir.csv")
            val second = fileDirectory.resolve("iki.csv")
            val controller = controllerFor(first)
            controller.exportTasks()
            controllerFor(second).exportTasks()

            assertEquals(expectedStatus.size, assertIs<ExportScreenState.Written>(controller.state).taskCount)
            assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second), "two exports of one database differ")
            val records = recordsIn(first)
            assertEquals(TASK_EXPORT_HEADER, records.first())
            assertEquals(1, records.count { it == TASK_EXPORT_HEADER }, "the heading was written more than once")
            val rows = records.drop(1).map { TASK_EXPORT_HEADER.zip(it).toMap() }
            assertEquals(expectedStatus.size, rows.size)
            assertEquals(expectedStatus, rows.associate { it.getValue("task") to it.getValue("status") })
            val text = String(Files.readAllBytes(first), StandardCharsets.UTF_8)
            assertEquals(1, text.count { it == BYTE_ORDER_MARK }, "the mark is in the file more than once")
            assertTrue(text.endsWith("\r\n"))
        }

    // ------------------------------------------------- what must not be lost

    private fun anOldFile(): ByteArray {
        val old = "eski dosya, kullanıcının\r\n".toByteArray(StandardCharsets.UTF_8)
        Files.write(target, old)
        return old
    }

    private suspend fun exportOverOldFile(controller: ExportController): ExportScreenState.Failed {
        controller.exportTasks()
        assertIs<ExportScreenState.ConfirmingOverwrite>(controller.state)
        controller.confirmOverwrite()
        return assertIs(controller.state)
    }

    private suspend fun aSmallLibrary() {
        val (_, cells) = aGameWithCells("Harmonies")
        aTaskIn(cells.getValue(CellColumnType.THREE_D), "Ev", colorIds = listOf(aColor("Kırmızı"), aColor("Sarı")))
    }

    @Test
    fun `a file system that cannot replace in one step leaves the old file and nothing else`() =
        runBlocking<Unit> {
            aSmallLibrary()
            val old = anOldFile()
            val writer =
                AtomicFileWriter(
                    temporarySuffix = ".csv.part",
                    moveIntoPlace = { _, _ -> throw AtomicMoveNotSupportedException("a", "b", "no") },
                )

            val failed = exportOverOldFile(controllerFor(target, writer))

            assertEquals(ExportFailure.NOT_ATOMIC, failed.failure)
            assertContentEquals(old, Files.readAllBytes(target))
            assertEquals(listOf(target), filesInDirectory(), "a half-written file was left behind")
        }

    @Test
    fun `a write that fails half way leaves the old file and nothing else`() =
        runBlocking<Unit> {
            aSmallLibrary()
            val old = anOldFile()
            val writer =
                AtomicFileWriter(
                    temporarySuffix = ".csv.part",
                    writeBytes = { file, bytes ->
                        Files.write(file, bytes.copyOf(bytes.size / 2))
                        throw IOException("disk full")
                    },
                )

            val failed = exportOverOldFile(controllerFor(target, writer))

            assertEquals(ExportFailure.WRITE_FAILED, failed.failure)
            assertContentEquals(old, Files.readAllBytes(target))
            assertEquals(listOf(target), filesInDirectory())
        }

    @Test
    fun `a place that cannot be written to leaves the old file and nothing else`() =
        runBlocking<Unit> {
            aSmallLibrary()
            val old = anOldFile()
            val writer = AtomicFileWriter(createTemporary = { throw AccessDeniedException("folder") })

            val failed = exportOverOldFile(controllerFor(target, writer))

            assertEquals(ExportFailure.NOT_WRITABLE, failed.failure)
            assertContentEquals(old, Files.readAllBytes(target))
            assertEquals(listOf(target), filesInDirectory())
        }

    @Test
    fun `a broken colour slot stops the export before the old file is touched`() =
        runBlocking<Unit> {
            val (_, cells) = aGameWithCells("Harmonies")
            val task = aTaskIn(cells.getValue(CellColumnType.THREE_D), "Ev", colorIds = listOf(aColor("Kırmızı"), aColor("Sarı")))
            executeRawSql(database, "UPDATE task_colors SET slot_index = 5 WHERE task_id = ? AND slot_index = 1", task.toString())
            val old = anOldFile()

            val failed = exportOverOldFile(controllerFor(target))

            assertEquals(ExportFailure.BROKEN_DATA, failed.failure)
            assertContentEquals(old, Files.readAllBytes(target))
            assertEquals(listOf(target), filesInDirectory())
        }

    @Test
    fun `a task filed in a pool its column does not feed stops the export before the old file is touched`() =
        runBlocking<Unit> {
            val (_, cells) = aGameWithCells("Harmonies")
            val task = aTaskIn(cells.getValue(CellColumnType.THREE_D), "Ev")
            executeRawSql(database, "UPDATE tasks SET pool_type = 'SPECIAL', tracking_mode = 'CHECKLIST' WHERE id = ?", task.toString())
            val old = anOldFile()

            val failed = exportOverOldFile(controllerFor(target))

            assertEquals(ExportFailure.BROKEN_DATA, failed.failure)
            assertContentEquals(old, Files.readAllBytes(target))
        }

    @Test
    fun `storage that will not answer is a failure the screen can show, and the old file stays`() =
        runBlocking<Unit> {
            aSmallLibrary()
            val old = anOldFile()
            val controller = controllerFor(target)
            driver.failOn { sql -> sql.trimStart().startsWith("SELECT") && "cell_segments" in sql }

            val failed = exportOverOldFile(controller)

            assertEquals(ExportFailure.COULD_NOT_READ, failed.failure)
            assertFalse(controller.isBusy, "the export stayed busy after storage refused")
            assertContentEquals(old, Files.readAllBytes(target))
            assertEquals(listOf(target), filesInDirectory())
            assertTrue(target.toString() !in failed.toString() && "SQL" !in failed.toString())

            driver.disarm()
            controller.startOver()
            controller.exportTasks()
            controller.confirmOverwrite()
            assertIs<ExportScreenState.Written>(controller.state, "the export could not simply be tried again")
            assertEquals(listOf("Ev"), recordsIn(target).drop(1).map { it[2] })
        }
}
