package dev.pnptracker.platform.files

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.database.rowCount
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

private val ARRAYS =
    listOf(
        "colors",
        "colorAliases",
        "importBatches",
        "games",
        "gameCells",
        "rawImportBlocks",
        "tasks",
        "cellSegments",
        "taskColors",
        "taskStages",
        "progressEvents",
        "historyEvents",
        "importBatchCells",
        "draftTasks",
        "draftTaskColors",
    )

/**
 * The whole way through, once, with nothing faked.
 *
 * The other tests each hold one piece still and ask about it. This one asks the
 * question a person would: put a temporary XDG home in front of the application,
 * let it work out where its data goes, create the database there, fill it, take
 * a backup, and see whether what comes out is a JSON document that says what it
 * ought to about the database it came from.
 *
 * Nothing here is a double. The paths come from [XdgAppPathsResolver], the
 * directories from [AppDirectoryInitializer], the database from
 * [DatabaseFactory]; only the environment variables are the test's, and they
 * point at a directory it made and will delete.
 *
 * The checksum is worked out again here from first principles, with the
 * platform's own digest rather than the application's helper, so agreement means
 * the two arrived at it separately.
 */
class BackupSmokeTest {
    private lateinit var home: Path
    private var realDatabaseExisted = false
    private var database: AppDatabase? = null

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-backup-smoke")
    }

    @AfterTest
    fun deleteHome() {
        database?.close()
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the smoke changed whether the real application database exists",
        )
        val root = home.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(root.startsWith(temporary) && root != temporary) { "refusing to delete $root" }
        Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `a temporary home, a filled database, and a backup that describes it`() =
        runBlocking<Unit> {
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
            assertTrue(Files.isDirectory(paths.dataDirectory), "the data directory was not created")
            assertTrue(Files.isDirectory(paths.backupsDirectory), "the backups directory was not created")

            val opened = DatabaseFactory().open(paths.databaseFile)
            database = opened
            // Opening runs the migrations; a harmless read is what production
            // does to make that happen before anything else.
            opened.gameDao().activeCount()
            fillWithEverything(opened)

            val before = TABLES.map { it to rowCount(opened, it) }
            assertTrue(before.all { it.second > 0 }, "a table stayed empty: $before")

            val document =
                DatabaseBackupExporter(BackupStore(opened), AppInfo.Current, StoppedClock(MOMENT)).backupDocument()

            // It parses, and it is the document the format describes.
            val parsed = Json.parseToJsonElement(document.json).jsonObject
            assertEquals("pnp-tracker-backup", parsed.getValue("format").jsonPrimitive.content)
            val formatVersion = parsed.getValue("formatVersion").jsonPrimitive.content
            val schemaVersion = parsed.getValue("sourceSchemaVersion").jsonPrimitive.content
            assertEquals(1, formatVersion.toInt())
            assertEquals(8, schemaVersion.toInt())
            assertEquals(AppInfo.Current.version, parsed.getValue("appVersion").jsonPrimitive.content)

            val backedUp = parsed.getValue("data").jsonObject
            assertEquals(ARRAYS, backedUp.keys.toList(), "the document does not carry the fifteen arrays in order")
            ARRAYS.forEach { array ->
                val written = backedUp.getValue(array).jsonArray
                assertTrue(written.isNotEmpty(), "$array came back empty")
            }
            TABLES.zip(ARRAYS).forEach { (table, array) ->
                val written = backedUp.getValue(array).jsonArray
                assertEquals(
                    rowCount(opened, table),
                    written.size.toLong(),
                    "$table and $array hold different numbers of rows",
                )
            }

            // The checksum, worked out again from the document's own bytes.
            val embedded = document.json.substringAfter("\"data\":").removeSuffix("}")
            val digest = MessageDigest.getInstance("SHA-256").digest(embedded.toByteArray(Charsets.UTF_8))
            val recomputed = digest.joinToString("") { byte -> "%02x".format(byte) }
            assertEquals(document.envelope.dataSha256, recomputed, "the checksum does not describe the data in the file")

            // And the database is exactly as it was.
            assertEquals(before, TABLES.map { it to rowCount(opened, it) })
            // Taking a backup is not choosing a setting. The settings file is
            // created by one thing only — somebody pressing save on the retention
            // number — and this whole tour must leave a machine that has never
            // done that without one (PLAN 14.4.12).
            assertFalse(Files.exists(paths.settingsFile), "taking a backup created the settings file")
            assertEquals(
                emptyList(),
                Files.list(paths.backupsDirectory).use { it.toList() },
                "this slice writes no file anywhere; the backups directory must still be empty",
            )
        }
}
