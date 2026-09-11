package dev.pnptracker.platform.files

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.database.rowCount
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.MAXIMUM_BACKUP_BYTES
import dev.pnptracker.domain.backup.restore.SUPPORTED_SOURCE_SCHEMA_VERSION
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.sha256Of
import dev.pnptracker.platform.backupfiles.PathBackupInput
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

/**
 * The smoke test for reading a backup: a temporary home, a real file, and the
 * whole pipeline in one go.
 *
 * The companion of [BackupSmokeTest], which does the same for writing one. Where
 * that stops at a document in memory, this carries on: the document is written
 * into the application's own backups directory by the real atomic writer, opened
 * again through the real file gateway, and taken through every gate of the reader
 * — size, encoding, JSON, envelope, checksum, values, graph, and a throwaway
 * database of the current schema.
 *
 * Nothing here is a double and nothing here is the user's. The paths come from
 * [XdgAppPathsResolver], the directories from [AppDirectoryInitializer], the
 * database from [DatabaseFactory]; only the environment variables are the test's,
 * and they point at a directory it made and will delete. The data is the
 * anonymous fixture.
 *
 * What it proves that the narrower tests do not is that the pieces fit: the
 * writer's bytes are the reader's bytes, the gateway's file is the reader's file,
 * and by the end there is nothing on disk that was not there at the start.
 */
class BackupRestoreSmokeTest {
    private lateinit var home: Path
    private var realDatabaseExisted = false
    private var database: AppDatabase? = null
    private val probeRoots = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-restore-smoke")
    }

    @AfterTest
    fun deleteHome() {
        database?.close()
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the smoke changed whether the real application database exists",
        )
        probeRoots.forEach { deleteTree(it) }
        deleteTree(home)
    }

    /**
     * Deletes one directory, after proving it is one this test made.
     *
     * A directory at a time and never a list built by adding a path to one: a
     * `Path` is itself an `Iterable<Path>` of its own name elements, so joining
     * one onto a list of them is a way to end up quietly iterating `tmp` and
     * `pnp-tracker-...` instead of the directory itself, and deleting nothing.
     */
    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        val absolute = root.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `a temporary home, a real file, and a backup that is read back whole`() =
        runBlocking<Unit> {
            // 1. A home of its own, and the application working out where its data goes.
            val data = home.resolve("data")
            val config = home.resolve("config")
            val paths =
                XdgAppPathsResolver(
                    environment = { name ->
                        when (name) {
                            "XDG_DATA_HOME" -> data.toString()
                            "XDG_CONFIG_HOME" -> config.toString()
                            else -> null
                        }
                    },
                ).resolve()
            AppDirectoryInitializer().ensureDirectories(paths)

            // 2. A database of the current schema, filled with something of every kind.
            val opened = DatabaseFactory().open(paths.databaseFile)
            database = opened
            opened.gameDao().activeCount()
            fillWithEverything(opened)
            val before = TABLES.associateWith { rowCount(opened, it) }
            assertTrue(before.values.all { it > 0 }, "a table stayed empty: $before")

            // 3. The backup, written by the writer and put on disk atomically.
            val written = DatabaseBackupExporter(BackupStore(opened), AppInfo.Current, StoppedClock(MOMENT)).backupDocument()
            val file = paths.backupsDirectory.resolve("pnp-yedek-2026-09-08.json")
            AtomicFileWriter(temporarySuffix = ".json.part").write(file, written.json.encodeToByteArray())
            val size = Files.size(file)
            assertTrue(size in 1 until MAXIMUM_BACKUP_BYTES, "the backup is $size bytes")

            // 4-7. Read again as an untrusted file, all the way through the
            // temporary database of the current schema.
            val reader =
                UntrustedBackupReader(
                    TemporaryBackupProbe(
                        temporaryDirectory = {
                            Files.createTempDirectory("pnp-tracker-restore-smoke-probe").also { probeRoots.add(it) }
                        },
                    ),
                )
            val result = reader.read(PathBackupInput(file))

            val read = (result as? BackupReadResult.Valid)?.backup
            assertTrue(read != null, "the reader refused a backup this application had just written: $result")
            assertEquals(written.envelope.data, read.data)
            assertEquals(written.envelope.dataSha256, read.dataSha256)
            assertEquals(sha256Of(canonicalBackupDataJson(read.data).encodeToByteArray()), read.dataSha256)
            assertEquals(SUPPORTED_SOURCE_SCHEMA_VERSION, read.sourceSchemaVersion)
            assertEquals("pnp-yedek-2026-09-08.json", read.summary.fileName)

            // 8. The database it came from is exactly as it was.
            assertEquals(before, TABLES.associateWith { rowCount(opened, it) })

            // 9. And nothing is left on disk but the backup itself.
            assertEquals(
                listOf("pnp-yedek-2026-09-08.json"),
                Files.list(paths.backupsDirectory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() },
            )
            probeRoots.forEach { root ->
                assertTrue(Files.notExists(root), "the probe left a database behind at the end of the smoke")
            }
            // Reading and checking a backup is not choosing a setting; the
            // settings file comes from a save and nothing else (PLAN 14.4.12).
            assertTrue(Files.notExists(paths.settingsFile), "reading a backup created the settings file")
        }
}
