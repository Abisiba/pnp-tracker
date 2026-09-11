package dev.pnptracker.platform.files

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.backup.restore.safetyBackupFileName
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.DEFAULT_AUTOMATIC_BACKUPS
import dev.pnptracker.domain.backup.retention.IMPORT_SNAPSHOT_PREFIX
import dev.pnptracker.domain.backup.retention.MIGRATION_SNAPSHOT_PREFIX
import dev.pnptracker.domain.backup.retention.importSnapshotFileName
import dev.pnptracker.domain.backup.retention.migrationSnapshotSetName
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.settings.DesktopSettingsStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

private val WRITTEN_AT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/**
 * Housekeeping in a home of its own, with nothing pretended.
 *
 * The companion of the other smoke tests, and the smallest of them: the paths
 * come from [XdgAppPathsResolver], the folder from [AppDirectoryInitializer],
 * the files are written by [AtomicFileWriter] and the documents by the real
 * backup writer. Only the trigger is missing, because there is not one yet —
 * this slice builds the housekeeping and the next three give it something to
 * follow.
 *
 * What it is really for is the mixed folder. A person's backups directory does
 * not hold a tidy series of one kind; it holds manual backups they made, safety
 * backups from restores, whatever an import left, and sometimes a file they put
 * there themselves. Rotation has to walk through all of that and come out having
 * removed only its own.
 */
class RetentionSmokeTest {
    private lateinit var home: Path
    private var realDatabaseExisted = false

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-retention-smoke")
    }

    @AfterTest
    fun deleteHome() {
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the smoke changed whether the real application database exists",
        )
        val absolute = home.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `a real folder of mixed files keeps its newest few and loses nobody else's`() =
        runBlocking<Unit> {
            // 1. A home of its own, and the application working out where its
            // backups go.
            val data = home.resolve("data")
            val paths =
                XdgAppPathsResolver(
                    environment = { name ->
                        when (name) {
                            "XDG_DATA_HOME" -> data.toString()
                            "XDG_CONFIG_HOME" -> home.resolve("config").toString()
                            else -> null
                        }
                    },
                ).resolve()
            AppDirectoryInitializer().ensureDirectories(paths)
            val backups = paths.backupsDirectory
            assertTrue(Files.isDirectory(backups))

            // 2. Ten import snapshots and three safety backups, written the way
            // the application writes them.
            val writer = AtomicFileWriter(temporarySuffix = ".json.part")
            val document =
                backupDocumentOf(anEmptyBackup(), AppInfo.Current.version, sourceSchemaVersion = 8, createdAt = WRITTEN_AT)
                    .json
                    .encodeToByteArray()
            val imports = (1..10).map { importSnapshotFileName(at(minute = it)) }
            imports.forEach { writer.write(backups.resolve(it), document) }
            val safety = (1..3).map { safetyBackupFileName(at(minute = it)) }
            safety.forEach { writer.write(backups.resolve(it), document) }

            // 3. One migration set: a document and the raw copy beside it.
            val setName = migrationSnapshotSetName(fromSchemaVersion = 3, toSchemaVersion = 8, moment = at(minute = 0))
            writer.write(backups.resolve("$setName.json"), document)
            writer.write(backups.resolve("$setName.db"), aDatabaseOfVersion(3))

            // 4. And the things that are not the application's to touch: a
            // manual backup, a note, a half-written file and a folder.
            val manual = backups.resolve("pnp-yedek-2026-09-09.json")
            writer.write(manual, document)
            val note = backups.resolve("okubeni.txt")
            Files.write(note, "bunlar benim".encodeToByteArray())
            val halfWritten = backups.resolve("pnp-otomatik-import-2026-09-09-235959.json.part")
            Files.write(halfWritten, document)
            val theirs = backups.resolve("pnp-otomatik-import-2026-09-09-235959.json")
            Files.write(theirs, "bu benim dosyam, adı benziyor".encodeToByteArray())

            // 5. Housekeeping, through the real settings store — which finds no
            // settings file and answers with the number that stands until
            // somebody chooses another.
            val rotation = AutomaticBackupRotation(DesktopBackupDirectory(backups))
            val settings = DesktopSettingsStore(paths.settingsFile)
            assertEquals(DEFAULT_AUTOMATIC_BACKUPS, settings.read().automaticBackupCount)
            val outcome = rotation.rotateAfter(imports.last().removeSuffix(".json"), keep = settings.read().automaticBackupCount)

            assertFalse(outcome.refused)
            assertEquals(emptyList(), outcome.couldNotRemove)
            assertEquals(3, outcome.removed.size, outcome.removed.toString())

            // 6. Seven imports left, the newest seven; the safety backups and
            // the migration set are under their own counts and untouched.
            val left = namesIn(backups)
            // The seven newest of the ten this application wrote. The eleventh
            // name in the folder starts the same way and belongs to somebody
            // else; step 7 is where it is accounted for.
            assertEquals(imports.drop(3).sorted(), left.filter { it in imports })
            assertEquals(safety.sorted(), left.filter { it.startsWith("pnp-oncesi-") })
            assertEquals(2, left.count { it.startsWith(MIGRATION_SNAPSHOT_PREFIX) })

            // 7. And nothing that was not the application's has moved.
            assertTrue(Files.exists(manual), "a manual backup was removed")
            assertTrue(Files.exists(note), "somebody's own file was removed")
            assertTrue(Files.exists(halfWritten), "a half-written file was removed")
            assertTrue(Files.exists(theirs), "a file that only looked like ours was removed")
            assertEquals("bu benim dosyam, adı benziyor", Files.readString(theirs))

            // 8. Running it again over the settled folder changes nothing, which
            // is what makes it safe after a crash.
            val again = rotation.rotateAfter(imports.last().removeSuffix(".json"), keep = DEFAULT_AUTOMATIC_BACKUPS)
            assertEquals(emptyList(), again.removed)
            assertEquals(left, namesIn(backups))

            // 9. Nothing was written outside the backups folder, and no database
            // was made anywhere.
            assertTrue(Files.notExists(paths.databaseFile), "the smoke made a database it has no business making")
            // Reading the retention number does not create the file it is kept
            // in: that happens when somebody presses save and at no other
            // moment (PLAN 14.4.12).
            assertTrue(Files.notExists(paths.settingsFile), "housekeeping created the settings file by itself")
            assertTrue(left.none { it.endsWith(".part") && !it.startsWith(IMPORT_SNAPSHOT_PREFIX) })
        }

    private fun at(minute: Int) = LocalMoment(2026, 9, 9, 14, minute, 0)

    private fun namesIn(folder: Path): List<String> =
        Files.list(folder).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    /** A plausible first page of a SQLite database still on [userVersion]. */
    private fun aDatabaseOfVersion(userVersion: Int): ByteArray {
        val page = ByteArray(4096)
        "SQLite format 3".encodeToByteArray().copyInto(page)
        page[16] = 0x10
        page[63] = userVersion.toByte()
        return page
    }
}
