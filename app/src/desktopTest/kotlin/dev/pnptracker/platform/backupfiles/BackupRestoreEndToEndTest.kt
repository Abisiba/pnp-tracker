package dev.pnptracker.platform.backupfiles

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AWKWARD_TEXT
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DOCUMENT_BEFORE_WITH_SPACES
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TASK_MULTICOLOR
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.data.database.rowCount
import dev.pnptracker.data.database.writeRow
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.MAXIMUM_BACKUP_BYTES
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.restore.ValidatedBackup
import dev.pnptracker.platform.files.AtomicFileWriter
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * The whole way round, with nothing faked: a real database, a real file, and a
 * reader that has never been told the file is trustworthy.
 *
 * ```text
 * a filled database → the writer → bytes on disk → the untrusted reader
 *   → the validator → a throwaway database of the current schema → back out again
 * ```
 *
 * What has to come out at the end is the data that went in, to the byte, and the
 * same checksum. That single equality is what makes a backup worth having, and it
 * is the only claim here that cannot be arrived at by any shortcut: the file is
 * written by the real writer, read by the real reader through the real file
 * gateway, and put into a real database by the real probe. Nothing in the chain
 * is a double.
 *
 * The individual tests then ask about the awkward parts of the data — the
 * tombstones, the emoji, the whitespace an import rollback compares to the
 * character — because "the whole thing round trips" is a claim that could be true
 * of a backup that had quietly lost one column of one table nobody looked at.
 */
class BackupRestoreEndToEndTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExisted = false
    private var database: AppDatabase? = null
    private val probeRoots = mutableListOf<Path>()

    @BeforeTest
    fun createDatabase() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun deleteDatabase() {
        database?.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExisted)
        directory.delete()
        probeRoots.forEach { root ->
            if (Files.exists(root)) {
                Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }

    @Test
    fun `a database written to a file comes back as the same database`() =
        runBlocking<Unit> {
            val opened = filledDatabase()
            val written = exporter(opened).backupDocument()
            val file = saved(written.json.encodeToByteArray())

            val read = readBack(file)

            assertEquals(written.envelope.data, read.data, "the data that came back is not the data that went out")
            assertEquals(written.envelope.dataSha256, read.dataSha256)
            assertEquals(written.envelope.createdAt, read.createdAt)
            assertEquals(8, read.sourceSchemaVersion)
            assertEquals(AppInfo.Current.version, read.appVersion)
            assertEquals("pnp-yedek-2026-09-08.json", read.fileName)
        }

    @Test
    fun `all fifteen tables come back with the rows they went out with`() =
        runBlocking<Unit> {
            val opened = filledDatabase()
            val counts = TABLES.associateWith { rowCount(opened, it) }
            assertTrue(counts.values.all { it > 0 }, "the fixture left a table empty: $counts")

            val read = readBack(saved(exporter(opened).backupDocument().json.encodeToByteArray()))

            val arrays =
                mapOf(
                    "colors" to read.data.colors.size,
                    "color_aliases" to read.data.colorAliases.size,
                    "import_batches" to read.data.importBatches.size,
                    "games" to read.data.games.size,
                    "game_cells" to read.data.gameCells.size,
                    "raw_import_blocks" to read.data.rawImportBlocks.size,
                    "tasks" to read.data.tasks.size,
                    "cell_segments" to read.data.cellSegments.size,
                    "task_colors" to read.data.taskColors.size,
                    "task_stages" to read.data.taskStages.size,
                    "progress_events" to read.data.progressEvents.size,
                    "history_events" to read.data.historyEvents.size,
                    "import_batch_cells" to read.data.importBatchCells.size,
                    "draft_tasks" to read.data.draftTasks.size,
                    "draft_task_colors" to read.data.draftTaskColors.size,
                )
            assertEquals(counts.mapValues { it.value.toInt() }, arrays)
        }

    @Test
    fun `the awkward parts of the data survive being written and read`() =
        runBlocking<Unit> {
            val read = readBack(saved(exporter(filledDatabase()).backupDocument().json.encodeToByteArray()))

            // Turkish letters, an emoji outside the basic plane, a newline, a tab,
            // a quote and a backslash — all of it to the character.
            val task = read.data.tasks.single { it.id == TASK_MULTICOLOR }
            assertEquals("Kılıç $AWKWARD_TEXT", task.name)
            assertEquals(AWKWARD_TEXT, task.notes)

            // PLAN 11.4.4 compares this text exactly, so the spaces at both ends
            // are the difference between an import that can be taken back and one
            // that cannot.
            val snapshots = read.data.importBatchCells
            assertTrue(snapshots.any { it.documentBefore == DOCUMENT_BEFORE_WITH_SPACES })
            // And an empty snapshot is not the same as no snapshot.
            assertTrue(snapshots.any { it.documentBefore == "" })

            // Null and empty stay different things.
            assertTrue(read.data.tasks.any { it.notes == null })
            assertTrue(read.data.tasks.any { it.notes == "" })

            // The records a user deleted are in there with the fact that they were.
            assertTrue(read.data.games.any { it.deletedAt != null })
            assertTrue(read.data.tasks.any { it.deletedAt != null })

            // Every history kind the fixture writes, and every progress event.
            assertEquals(11, read.data.historyEvents.size)
            assertEquals(3, read.data.progressEvents.size)
            assertTrue(read.data.historyEvents.any { it.kind == "TASK_STAGE_QUANTITY_CHANGED" && it.newQuantity == 167 })

            // One task in two colours, in the slots the user chose (PLAN 5.10).
            assertEquals(
                listOf(0, 1),
                read.data.taskColors
                    .filter { it.taskId == TASK_MULTICOLOR }
                    .map { it.slotIndex },
            )

            // All three states an import can be left in (PLAN 11.2), and the
            // provenance that makes a confirmed one rollbackable after a restore.
            assertEquals(
                setOf("DRAFT", "CONFIRMED", "ROLLED_BACK"),
                read.data.importBatches
                    .map { it.status }
                    .toSet(),
            )
            val confirmed = read.data.importBatches.single { it.status == "CONFIRMED" }
            assertTrue(read.data.games.any { it.sourceImportBatchId == confirmed.id })
            assertTrue(read.data.importBatchCells.all { it.importBatchId == confirmed.id })
            assertTrue(read.data.rawImportBlocks.any { it.importBatchId == confirmed.id })
            assertNotNull(read.data.draftTasks.single { it.materializedTaskId != null })
            assertTrue(read.data.tasks.any { it.sourceRawImportBlockId != null })
        }

    @Test
    fun `a file that is not there is unreadable, and nothing else happens`() =
        runBlocking<Unit> {
            val missing = directory.root.resolve("yok.json")

            val result = reader().read(PathBackupInput(missing))

            assertEquals(BackupProblem.UNREADABLE, (result as BackupReadResult.Refused).rejection.problem)
        }

    @Test
    fun `a file somebody edited by hand is refused, and the database it came from is untouched`() =
        runBlocking<Unit> {
            val opened = filledDatabase()
            val before = TABLES.associateWith { rowCount(opened, it) }
            val file = saved(exporter(opened).backupDocument().json.encodeToByteArray())
            val text = Files.readString(file)
            Files.write(file, text.replaceFirst("Örnek Oyun", "Örnek Oyum").encodeToByteArray())

            val result = reader().read(PathBackupInput(file))

            assertEquals(BackupProblem.CHECKSUM_MISMATCH, (result as BackupReadResult.Refused).rejection.problem)
            assertEquals(before, TABLES.associateWith { rowCount(opened, it) })
        }

    @Test
    fun `reading a backup changes nothing about the database it was taken from`() =
        runBlocking<Unit> {
            val opened = filledDatabase()
            val before = TABLES.associateWith { rowCount(opened, it) }
            val document = exporter(opened).backupDocument()

            readBack(saved(document.json.encodeToByteArray()))

            assertEquals(before, TABLES.associateWith { rowCount(opened, it) })
            // And the same database still produces the same file.
            assertContentEquals(
                document.json.encodeToByteArray(),
                exporter(opened).backupDocument().json.encodeToByteArray(),
            )
        }

    @Test
    fun `the file that was read is left exactly as it was found`() =
        runBlocking<Unit> {
            val file = saved(exporter(filledDatabase()).backupDocument().json.encodeToByteArray())
            val bytes = Files.readAllBytes(file)

            readBack(file)

            assertContentEquals(bytes, Files.readAllBytes(file), "reading the backup changed the backup")
            assertEquals(
                listOf(file.fileName.toString()),
                Files.list(file.parent).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() },
                "reading the backup left something beside it",
            )
        }

    @Test
    fun `a library of a thousand tasks is a backup well inside the limit and is read whole`() =
        runBlocking<Unit> {
            val opened = filledDatabase()
            oneThousandMoreTasks(opened)
            val file = saved(exporter(opened).backupDocument().json.encodeToByteArray())

            val size = Files.size(file)
            val read = readBack(file)

            assertEquals(1_003, read.data.tasks.size)
            // Measured rather than guessed at: a thousand and three tasks come to
            // about 450 KiB, which is a hundred and fifty times under the limit.
            // The range is wide enough that a longer application version string
            // does not fail it and narrow enough to notice an order of magnitude.
            assertTrue(size in 400_000..600_000, "a thousand tasks came to $size bytes")
            assertTrue(size < MAXIMUM_BACKUP_BYTES / 8, "a thousand tasks came within reach of the limit: $size bytes")
            assertEquals(1_003, read.summary.taskCount)
        }

    private suspend fun readBack(file: Path): ValidatedBackup {
        val result = reader().read(PathBackupInput(file))
        assertTrue(result is BackupReadResult.Valid, "the reader refused a backup this application wrote: $result")
        return result.backup
    }

    /** The real reader, with the real probe, watched so its directory can be checked. */
    private fun reader(): UntrustedBackupReader =
        UntrustedBackupReader(
            TemporaryBackupProbe(
                temporaryDirectory = {
                    Files.createTempDirectory("pnp-tracker-round-trip").also { probeRoots.add(it) }
                },
            ),
        )

    private fun exporter(opened: AppDatabase) = DatabaseBackupExporter(BackupStore(opened), AppInfo.Current, StoppedClock(MOMENT))

    /** The bytes on disk, put there the way the application puts them there. */
    private fun saved(bytes: ByteArray): Path {
        val target = directory.root.resolve("backups").resolve("pnp-yedek-2026-09-08.json")
        Files.createDirectories(target.parent)
        AtomicFileWriter(temporarySuffix = ".json.part").write(target, bytes)
        return target
    }

    private suspend fun filledDatabase(): AppDatabase {
        val opened = DatabaseFactory().open(directory.databaseFile)
        database = opened
        opened.gameDao().activeCount()
        fillWithEverything(opened)
        return opened
    }

    /**
     * A thousand more tasks, of the size PLAN 20 asks the application to cope with.
     *
     * Written straight into the table because what is being measured is the size
     * and completeness of the backup, not the screens that would normally create
     * them. They are attached to nothing, which is a shape storage allows: a task
     * whose words were turned back into text keeps its row and loses its piece of
     * the cell (PLAN 12.8).
     */
    private suspend fun oneThousandMoreTasks(opened: AppDatabase) {
        (0 until 1_000).forEach { at ->
            writeRow(
                opened,
                "tasks",
                listOf(
                    "id" to "70000000-0000-4000-8000-" + at.toString().padStart(12, '0'),
                    "pool_type" to "SPECIAL",
                    "tracking_mode" to "CHECKLIST",
                    "name" to "Toplu görev $at",
                    "required_quantity" to null,
                    "notes" to null,
                    "is_completed" to false,
                    "completed_at" to null,
                    "primary_batch_completed" to false,
                    "current_missing_quantity" to 0,
                    "created_at" to 1_700_000_000_000L,
                    "updated_at" to 1_700_000_000_000L,
                    "deleted_at" to 1_700_001_200_000L,
                    "source_raw_import_block_id" to null,
                    "is_missing" to false,
                    "is_borrowed" to false,
                    "needs_info" to false,
                    "needs_classification" to false,
                ),
            )
        }
    }

    @Test
    fun `nothing about reading a backup can name the file it came from`() =
        runBlocking<Unit> {
            // The gateway hands upwards a name and never a path, so a refusal has
            // nothing to leak (PLAN 14.4.5).
            val file = saved("bu bir yedek değil".encodeToByteArray())

            val result = reader().read(PathBackupInput(file))

            val rejection = (result as BackupReadResult.Refused).rejection
            assertEquals(BackupProblem.MALFORMED_JSON, rejection.problem)
            assertNull(rejection.place.field)
            assertTrue(directory.root.toString() !in rejection.toString())
        }
}
