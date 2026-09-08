package dev.pnptracker.platform.backupfiles

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.database.rowCount
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.ui.feature.settings.BackupController
import dev.pnptracker.ui.feature.settings.BackupScreenState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)

private val TABLES =
    listOf(
        "colors",
        "color_aliases",
        "import_batches",
        "games",
        "game_cells",
        "raw_import_blocks",
        "tasks",
        "cell_segments",
        "task_colors",
        "task_stages",
        "progress_events",
        "history_events",
        "import_batch_cells",
        "draft_tasks",
        "draft_task_colors",
    )

/** A picker that answers with whatever the test decided, without a window server. */
private class FakePicker(
    private vararg val answers: Path?,
) : BackupFilePicker {
    var asked = 0
        private set
    var suggested: String? = null
        private set

    override suspend fun chooseDestination(suggestedName: String): Path? {
        suggested = suggestedName
        val answer = answers.getOrNull(asked) ?: answers.lastOrNull()
        asked++
        return answer
    }
}

/**
 * The whole way from the button to a file on a real disk.
 *
 * The pieces each have their own tests; what only this can answer is whether
 * they add up: the bytes the checksum was taken of are the bytes on disk, an
 * existing file survives every way the write can fail, and taking a backup
 * leaves the database exactly as it was.
 *
 * The picker is the only thing faked, because the alternative is a save dialog
 * on somebody's screen.
 */
class BackupEndToEndTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var folder: Path
    private lateinit var database: AppDatabase
    private var realDatabaseExisted = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        folder = Files.createDirectory(directory.root.resolve("hedef"))
        database = DatabaseFactory().open(directory.databaseFile)
        runBlocking { fillWithEverything(database) }
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExisted)
        directory.delete()
    }

    private fun exporter() = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, StoppedClock(MOMENT))

    private fun controllerFor(
        picker: FakePicker,
        writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = ".json.part"),
    ) = BackupController(
        gateway = DesktopBackupFileGateway(picker, writer),
        exporter = exporter(),
        clock = StoppedClock(MOMENT),
    )

    /** Everything in the target folder apart from the file itself. */
    private fun leftovers(target: Path): List<String> =
        Files.list(folder).use { stream ->
            stream.map { it.fileName.toString() }.toList().filterNot { it == target.fileName.toString() }
        }

    private suspend fun tableCounts(): List<Long> = TABLES.map { rowCount(database, it) }

    @Test
    fun `the backup lands on disk as the exact bytes its checksum covers`() =
        runBlocking<Unit> {
            val target = folder.resolve("pnp-yedek-2026-09-08.json")
            val controller = controllerFor(FakePicker(target))

            controller.saveBackup()

            assertEquals(BackupScreenState.Saved("pnp-yedek-2026-09-08.json"), controller.state)
            assertContentEquals(
                exporter().backupDocument().json.toByteArray(StandardCharsets.UTF_8),
                Files.readAllBytes(target),
                "the file is not byte for byte the document that was hashed",
            )
            assertEquals(emptyList(), leftovers(target), "a half-written file was left behind")
        }

    @Test
    fun `what lands is a backup document, readable on its own`() =
        runBlocking<Unit> {
            val target = folder.resolve("yedek.json")
            controllerFor(FakePicker(target)).saveBackup()

            val written = Json.parseToJsonElement(Files.readString(target, StandardCharsets.UTF_8))
            val parsed = written.jsonObject
            assertEquals("pnp-tracker-backup", parsed.getValue("format").jsonPrimitive.content)
            assertEquals("1", parsed.getValue("formatVersion").jsonPrimitive.content)
            val data = parsed.getValue("data").jsonObject
            assertEquals(15, data.keys.size)
            assertTrue(data.getValue("games").jsonArray.isNotEmpty())
        }

    @Test
    fun `the dialog is offered the day's own name`() =
        runBlocking<Unit> {
            val picker = FakePicker(folder.resolve("yedek.json"))
            controllerFor(picker).saveBackup()

            assertTrue(picker.suggested.orEmpty().startsWith("pnp-yedek-"), picker.suggested.orEmpty())
            assertTrue(picker.suggested.orEmpty().endsWith(".json"))
        }

    @Test
    fun `a chosen name with no extension is written as a json`() =
        runBlocking<Unit> {
            val controller = controllerFor(FakePicker(folder.resolve("yedegim")))

            controller.saveBackup()

            assertEquals(BackupScreenState.Saved("yedegim.json"), controller.state)
            assertTrue(Files.exists(folder.resolve("yedegim.json")))
            assertTrue(!Files.exists(folder.resolve("yedegim")), "a file was written under the name without the extension")
        }

    @Test
    fun `a chosen name that promises something else is refused before anything is read`() =
        runBlocking<Unit> {
            val controller = controllerFor(FakePicker(folder.resolve("yedek.txt")))

            controller.saveBackup()

            assertEquals(BackupScreenState.Failed(BackupFailure.UNSUPPORTED_FILE_TYPE), controller.state)
            assertEquals(emptyList<String>(), Files.list(folder).use { it.map { p -> p.fileName.toString() }.toList() })
        }

    @Test
    fun `changing one's mind leaves the folder empty and says nothing about it`() =
        runBlocking<Unit> {
            val before = tableCounts()
            val controller = controllerFor(FakePicker(null))

            controller.saveBackup()

            assertEquals(BackupScreenState.Idle, controller.state)
            assertEquals(emptyList<String>(), Files.list(folder).use { it.map { p -> p.fileName.toString() }.toList() })
            assertEquals(before, tableCounts())
        }

    @Test
    fun `a file already there is replaced only after the user agrees`() =
        runBlocking<Unit> {
            val target = folder.resolve("yedek.json")
            val existing = "eski içerik"
            Files.writeString(target, existing)
            val controller = controllerFor(FakePicker(target))

            controller.saveBackup()
            assertEquals(BackupScreenState.ConfirmingOverwrite("yedek.json"), controller.state)
            assertEquals(existing, Files.readString(target), "the file was replaced before anybody agreed")

            controller.cancelOverwrite()
            assertEquals(existing, Files.readString(target), "backing out replaced the file anyway")

            controller.saveBackup()
            controller.confirmOverwrite()
            assertIs<BackupScreenState.Saved>(controller.state)
            assertTrue(Files.readString(target).startsWith("{\"format\":\"pnp-tracker-backup\""))
        }

    @Test
    fun `a failure at any step leaves the file that was there byte for byte`() =
        runBlocking<Unit> {
            val target = folder.resolve("yedek.json")
            val existing = "eski içerik ığşçöü"
            val before = Files.readAllBytes(target.also { Files.writeString(it, existing) })

            val seams =
                listOf<Pair<AtomicFileWriter, BackupFailure>>(
                    AtomicFileWriter(writeBytes = { _, _ -> throw IOException("disk full") }) to
                        BackupFailure.WRITE_FAILED,
                    AtomicFileWriter(moveIntoPlace = { _, _ -> throw AtomicMoveNotSupportedException(null, null, "no") }) to
                        BackupFailure.NOT_ATOMIC,
                    AtomicFileWriter(moveIntoPlace = { _, _ -> throw IOException("gone") }) to
                        BackupFailure.WRITE_FAILED,
                    AtomicFileWriter(createTemporary = { throw IOException("read only") }) to
                        BackupFailure.TEMPORARY_FILE_FAILED,
                )

            seams.forEach { (writer, expected) ->
                val controller = controllerFor(FakePicker(target), writer)
                controller.saveBackup()
                controller.confirmOverwrite()

                assertEquals(BackupScreenState.Failed(expected), controller.state)
                assertContentEquals(before, Files.readAllBytes(target), "$expected changed the file that was there")
                assertEquals(emptyList(), leftovers(target), "$expected left a half-written file behind")
            }
        }

    @Test
    fun `a failure on a new destination leaves no half file at all`() =
        runBlocking<Unit> {
            val target = folder.resolve("yeni.json")
            val controller =
                controllerFor(
                    FakePicker(target),
                    AtomicFileWriter(writeBytes = { _, _ -> throw IOException("disk full") }),
                )

            controller.saveBackup()

            assertEquals(BackupScreenState.Failed(BackupFailure.WRITE_FAILED), controller.state)
            assertTrue(!Files.exists(target), "a destination was created for a backup that was never written")
            assertEquals(emptyList<String>(), Files.list(folder).use { it.map { p -> p.fileName.toString() }.toList() })
        }

    @Test
    fun `a file system that cannot replace in one step is refused rather than worked around`() =
        runBlocking<Unit> {
            val target = folder.resolve("yedek.json")
            Files.writeString(target, "eski")
            val controller =
                controllerFor(
                    FakePicker(target),
                    AtomicFileWriter(moveIntoPlace = { _, _ -> throw AtomicMoveNotSupportedException(null, null, "no") }),
                )

            controller.saveBackup()
            controller.confirmOverwrite()

            assertEquals(BackupScreenState.Failed(BackupFailure.NOT_ATOMIC), controller.state)
            assertEquals("eski", Files.readString(target))
        }

    @Test
    fun `only the file name leaves the desktop layer`() =
        runBlocking<Unit> {
            val target = folder.resolve("yedek.json")
            val handle = DesktopBackupFileGateway(FakePicker(target)).chooseDestination("pnp-yedek-2026-09-08.json")

            assertEquals("yedek.json", handle?.fileName)
            assertTrue(
                handle?.fileName?.contains(folder.toString()) != true,
                "the handle carries the folder it lives in",
            )
        }

    @Test
    fun `a dialog that answers with an empty name is refused`() =
        runBlocking<Unit> {
            // Path.of("") has an empty file name; a dialog that returns one has
            // told us nothing usable.
            val refused =
                assertFailsWith<BackupException> {
                    DesktopBackupFileGateway(FakePicker(Path.of(""))).chooseDestination("pnp-yedek.json")
                }
            assertEquals(BackupFailure.NO_DESTINATION, refused.failure)
        }

    @Test
    fun `taking a backup leaves every table exactly as it was`() =
        runBlocking<Unit> {
            val before = tableCounts()
            val target = folder.resolve("yedek.json")

            controllerFor(FakePicker(target)).saveBackup()

            assertEquals(before, tableCounts())
            // And what it wrote still describes the same database.
            assertEquals(exporter().backupDocument().json, Files.readString(target, StandardCharsets.UTF_8))
        }

    @Test
    fun `two backups of the same database are the same file`() =
        runBlocking<Unit> {
            val first = folder.resolve("bir.json")
            val second = folder.resolve("iki.json")
            val controller = controllerFor(FakePicker(first, second))

            controller.saveBackup()
            controller.startOver()
            controller.saveBackup()

            assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second))
        }
}
