package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The step that gives an import somewhere to keep what it wrote over.
 *
 * Version 7 could say which import created a task, and could not say what the
 * cell holding that task read beforehand. PLAN 11.4.4 needs that second thing to
 * take an import back without destroying whatever the user wrote afterwards, so
 * version 8 adds `import_batch_cells` and the confirmation fills it in.
 *
 * The fixture below is a version 7 database as somebody who had really been
 * using it would leave it, including an import already confirmed. Two things are
 * asked of it: that every row survives, and that the confirmed import is left
 * **without** a snapshot. The second is the point of the whole migration. The
 * obvious backfill — today's cell text minus the names this import wrote — would
 * produce a document that never existed on any day, because the user has been
 * editing the cell ever since. PLAN 11.4.4 refuses such a rollback instead, and
 * that refusal is only correct if nothing here invents a record.
 */
class Migration7To8Test {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExistedBefore = false

    private val gameId = IdGenerator.Random.newId()
    private val otherGameId = IdGenerator.Random.newId()
    private val cardCellId = IdGenerator.Random.newId()

    /** Never written in, so a snapshot of it has to come out as the empty string. */
    private val emptyCardCellId = IdGenerator.Random.newId()

    private val confirmedBatchId = IdGenerator.Random.newId()
    private val draftBatchId = IdGenerator.Random.newId()
    private val importedTaskId = IdGenerator.Random.newId()
    private val handwrittenTaskId = IdGenerator.Random.newId()

    @BeforeTest
    fun createTemporaryDirectory() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /**
     * A version 7 database holding one import that was confirmed and one still
     * being reviewed, plus a cell the user wrote in themselves.
     */
    private fun createUsedVersion7Database() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 7) { connection ->
            insertSeedColors(connection)
            insertVersion4Game(connection, gameId)
            insertVersion4Game(connection, otherGameId, name = "Wingspan")
            insertVersion4GameCell(connection, cardCellId, gameId, "CARD")
            insertVersion4GameCell(connection, emptyCardCellId, otherGameId, "CARD")

            insertVersion6Task(connection, handwrittenTaskId, "CARD", "PIPELINE", "Elle yazılan deste", 30)
            insertVersion6Task(connection, importedTaskId, "CARD", "PIPELINE", "İçe aktarılan deste", 15)
            insertVersion4PlainTextSegment(connection, IdGenerator.Random.newId(), cardCellId, 0, "Kullanıcının notu ")
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cardCellId, 1, handwrittenTaskId)
            insertVersion4PlainTextSegment(connection, IdGenerator.Random.newId(), cardCellId, 2, " ")
            insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cardCellId, 3, importedTaskId)

            // The import that produced one of those tasks, confirmed back when
            // there was nowhere to record what the cell said first.
            insertVersion5ImportBatch(connection, confirmedBatchId, status = "CONFIRMED", createdTaskCount = 1)
            val confirmedBlock = IdGenerator.Random.newId()
            insertVersion6RawImportBlock(connection, confirmedBlock, confirmedBatchId)
            insertVersion4DraftTask(
                connection,
                IdGenerator.Random.newId(),
                confirmedBlock,
                cardCellId,
                materializedTaskId = importedTaskId,
            )

            // And one still open, which will be confirmed after the upgrade.
            insertVersion5ImportBatch(connection, draftBatchId, sha256 = "1".repeat(64))
            val draftBlock = IdGenerator.Random.newId()
            insertVersion6RawImportBlock(connection, draftBlock, draftBatchId, rowIndex = 2)
            insertVersion4DraftTask(connection, IdGenerator.Random.newId(), draftBlock, emptyCardCellId)
        }
    }

    private fun version() = CommittedSchema.readVersion(directory.databaseFile)

    private fun rows(table: String) = CommittedSchema.countRowsOf(directory.databaseFile, table)

    private fun openAndClose() {
        val database = DatabaseFactory().open(directory.databaseFile)
        runBlocking<Unit> { database.gameDao().activeGames() }
        database.close()
    }

    // ------------------------------------------------------------ the walk itself

    @Test
    fun `an empty version 7 database walks up and is given an empty table`() {
        CommittedSchema.createDatabase(directory.databaseFile, version = 7) { connection ->
            insertSeedColors(connection)
        }

        openAndClose()

        assertEquals(CURRENT_SCHEMA_VERSION, version())
        assertEquals(0, rows("import_batch_cells"))
        assertEquals(12, rows("colors"))
    }

    @Test
    fun `a used version 7 database reaches the current version with every row still there`() {
        createUsedVersion7Database()
        val before = TABLES.associateWith { rows(it) }

        openAndClose()

        assertEquals(CURRENT_SCHEMA_VERSION, version())
        assertEquals(before, TABLES.associateWith { rows(it) }, "the walk changed how many rows a table holds")
    }

    @Test
    fun `the cell an old import wrote into is left exactly as it reads today`() {
        createUsedVersion7Database()
        val before = segmentsOf(cardCellId)

        openAndClose()

        assertEquals(
            listOf("Kullanıcının notu ", null, " ", null),
            before.map { it.second },
            "the fixture is not the document these tests are about",
        )
        assertEquals(before, segmentsOf(cardCellId), "the walk rewrote a cell it had no business touching")
    }

    // ------------------------------------------------------- what is not invented

    @Test
    fun `an import confirmed before this version is left without a snapshot`() {
        createUsedVersion7Database()

        openAndClose()

        assertEquals(
            emptyList(),
            snapshotRows(),
            "the walk guessed what a cell said on a day nobody recorded",
        )
    }

    @Test
    fun `a draft that survives the walk records its cell when it is finally confirmed`() =
        runBlocking<Unit> {
            createUsedVersion7Database()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                database.importDao().confirmDraftBatch(
                    draftBatchId,
                    acknowledgeUnprocessedBlocks = true,
                    clock = StoppedClock(updatedAt),
                    idGenerator = IdGenerator.Random,
                )
            } finally {
                database.close()
            }

            // The upgraded database is a working one, not merely a shaped one:
            // the draft it carried across takes the ordinary path and is kept.
            assertEquals(
                listOf(Triple(draftBatchId.toString(), emptyCardCellId.toString(), "")),
                snapshotRows(),
                "a draft confirmed after the upgrade was not recorded",
            )
            // And the old batch is still empty-handed, which is what makes the
            // refusal in PLAN 11.4.4 a fact rather than a guess.
            assertTrue(snapshotRows().none { it.first == confirmedBatchId.toString() })
        }

    // ------------------------------------------------------------- the shape added

    @Test
    fun `the table the walk creates is the table that was committed`() {
        createUsedVersion7Database()
        openAndClose()

        val committed =
            CommittedSchema
                .read(CURRENT_SCHEMA_VERSION.toInt())
                .createStatements
                .single { it.startsWith("CREATE TABLE") && "`import_batch_cells`" in it }

        assertEquals(normalised(committed), normalised(createSqlOf("import_batch_cells")))
    }

    @Test
    fun `a walked database and a fresh one hold the same table`() {
        createUsedVersion7Database()
        openAndClose()
        val walked = createSqlOf("import_batch_cells")
        val walkedIndexes = indexNamesOf("import_batch_cells")

        val fresh = TemporaryDatabaseDirectory()
        try {
            val database = DatabaseFactory().open(fresh.databaseFile)
            // Room creates the file on the first read, not on open.
            runBlocking<Unit> { database.gameDao().activeGames() }
            database.close()
            assertEquals(normalised(walked), normalised(createSqlOf("import_batch_cells", fresh.databaseFile)))
            assertEquals(walkedIndexes, indexNamesOf("import_batch_cells", fresh.databaseFile))
        } finally {
            fresh.delete()
        }
    }

    @Test
    fun `the new table keeps both records, its index and the pair that identifies a row`() {
        createUsedVersion7Database()
        openAndClose()

        assertEquals(
            setOf("index_import_batch_cells_cell_id"),
            indexNamesOf("import_batch_cells"),
            "the table was left without the index its cell lookup reads by",
        )
        assertEquals(
            listOf("import_batch_id", "cell_id"),
            primaryKeyOf("import_batch_cells"),
            "a row is identified by something other than the batch and the cell",
        )
        assertEquals(
            listOf(
                Triple("game_cells", "cell_id", "RESTRICT"),
                Triple("import_batches", "import_batch_id", "CASCADE"),
            ),
            foreignKeysOf("import_batch_cells"),
            "the record can be erased by something passing on its way out, or is not held to its batch",
        )
    }

    @Test
    fun `a snapshot cannot name a batch or a cell that is not there`() {
        createUsedVersion7Database()
        openAndClose()

        val strayBatch = writeSnapshot(IdGenerator.Random.newId().toString(), cardCellId.toString())
        val strayCell = writeSnapshot(confirmedBatchId.toString(), IdGenerator.Random.newId().toString())
        val real = writeSnapshot(confirmedBatchId.toString(), cardCellId.toString())

        assertTrue(strayBatch.isFailure, "a snapshot was kept for an import that does not exist")
        assertTrue(strayCell.isFailure, "a snapshot was kept for a cell that does not exist")
        assertTrue(real.isSuccess, "a snapshot naming rows that are really there was refused")
        assertTrue(
            writeSnapshot(confirmedBatchId.toString(), cardCellId.toString()).isFailure,
            "one import kept two records of the same cell",
        )
    }

    // -------------------------------------------------------------- the walk itself

    @Test
    fun `a version 1 database can be walked all the way to the current version`() =
        runBlocking<Unit> {
            CommittedSchema.createDatabase(directory.databaseFile, version = 1) { }
            assertEquals(1L, version())

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(12, database.colorDao().allColors().size)
                assertEquals(emptyList(), soundnessProblemsOf(database))
                assertEquals(emptyList(), database.importDao().allBatches())
            } finally {
                database.close()
            }
            assertEquals(CURRENT_SCHEMA_VERSION, version())
            assertEquals(0, rows("import_batch_cells"))
        }

    @Test
    fun `the walk leaves no foreign key dangling and nothing broken`() =
        runBlocking<Unit> {
            createUsedVersion7Database()

            val database = DatabaseFactory().open(directory.databaseFile)
            try {
                assertEquals(emptyList(), soundnessProblemsOf(database))
            } finally {
                database.close()
            }
        }

    @Test
    fun `opening an already upgraded database again changes nothing`() {
        createUsedVersion7Database()
        openAndClose()
        val once = TABLES.associateWith { rows(it) } + ("import_batch_cells" to rows("import_batch_cells"))

        openAndClose()
        openAndClose()

        assertEquals(CURRENT_SCHEMA_VERSION, version())
        assertEquals(
            once,
            TABLES.associateWith { rows(it) } + ("import_batch_cells" to rows("import_batch_cells")),
            "opening the database again ran the walk a second time",
        )
    }

    @Test
    fun `the database is never opened with a way past a missing migration`() {
        val factory = Files.readString(moduleRoot().resolve(DATABASE_FACTORY))

        assertTrue(
            "fallbackToDestructiveMigration" !in factory,
            "the application would throw the user's database away rather than fail to migrate it",
        )
        // Every step is registered, so nothing is reached by skipping.
        (1..<CURRENT_SCHEMA_VERSION.toInt()).forEach { from ->
            assertTrue(
                "Migration${from}To${from + 1}" in factory,
                "the walk from $from to ${from + 1} is not registered",
            )
        }
    }

    // ------------------------------------------------------------------- helpers

    /** Every snapshot row, read without going through Room. */
    private fun snapshotRows(): List<Triple<String, String, String>> =
        BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
            connection
                .prepare("SELECT import_batch_id, cell_id, document_before FROM import_batch_cells ORDER BY cell_id")
                .use { statement ->
                    buildList {
                        while (statement.step()) {
                            add(Triple(statement.getText(0), statement.getText(1), statement.getText(2)))
                        }
                    }
                }
        }

    /** The pieces of a cell as `order_index to text`, so a rewrite would show. */
    private fun segmentsOf(cellId: dev.pnptracker.domain.model.EntityId): List<Pair<Int, String?>> =
        BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
            connection
                .prepare("SELECT order_index, text FROM cell_segments WHERE cell_id = ? ORDER BY order_index")
                .use { statement ->
                    statement.bindText(1, cellId.toString())
                    buildList {
                        while (statement.step()) {
                            add(statement.getInt(0) to if (statement.isNull(1)) null else statement.getText(1))
                        }
                    }
                }
        }

    private fun writeSnapshot(
        batchId: String,
        cellId: String,
    ): Result<Unit> =
        runCatching {
            BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
                connection.prepare("PRAGMA foreign_keys = ON").use { it.step() }
                connection
                    .prepare("INSERT INTO import_batch_cells (import_batch_id, cell_id, document_before) VALUES (?, ?, '')")
                    .use { statement ->
                        statement.bindText(1, batchId)
                        statement.bindText(2, cellId)
                        statement.step()
                    }
            }
        }

    private suspend fun soundnessProblemsOf(database: AppDatabase): List<String> =
        database.useReaderConnection { transactor ->
            transactor.usePrepared("PRAGMA foreign_key_check") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            } +
                transactor
                    .usePrepared("PRAGMA integrity_check") { statement ->
                        buildList { while (statement.step()) add(statement.getText(0)) }
                    }.filterNot { it == "ok" }
        }

    private fun indexNamesOf(
        table: String,
        file: Path = directory.databaseFile,
    ): Set<String> =
        BundledSQLiteDriver().open(file.toString()).use { connection ->
            connection
                .prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?")
                .use { statement ->
                    statement.bindText(1, table)
                    // SQLite's own index behind the primary key is not one this
                    // migration chose, so it is not one this is about.
                    buildSet { while (statement.step()) add(statement.getText(0)) }
                        .filterNot { it.startsWith("sqlite_") }
                        .toSet()
                }
        }

    /** The columns of a table's primary key, in the order they were declared. */
    private fun primaryKeyOf(table: String): List<String> =
        BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
            connection.prepare("SELECT name, pk FROM pragma_table_info('$table') WHERE pk > 0 ORDER BY pk").use {
                buildList { while (it.step()) add(it.getText(0)) }
            }
        }

    /** Each foreign key as `referenced table, column, on delete`, in a stable order. */
    private fun foreignKeysOf(table: String): List<Triple<String, String, String>> =
        BundledSQLiteDriver().open(directory.databaseFile.toString()).use { connection ->
            val sql = "SELECT \"table\", \"from\", on_delete FROM pragma_foreign_key_list('$table')"
            val keys =
                connection.prepare(sql).use {
                    buildList { while (it.step()) add(Triple(it.getText(0), it.getText(1), it.getText(2))) }
                }
            keys.sortedBy { it.first }
        }

    private fun createSqlOf(
        table: String,
        file: Path = directory.databaseFile,
    ): String =
        BundledSQLiteDriver().open(file.toString()).use { connection ->
            connection.prepare("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
                statement.bindText(1, table)
                assertTrue(statement.step(), "there is no table called $table")
                statement.getText(0)
            }
        }

    /** Ignores the difference between `CREATE TABLE` and `CREATE TABLE IF NOT EXISTS`, and spacing. */
    private fun normalised(sql: String): String =
        sql
            .replace("IF NOT EXISTS ", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** The module directory, found the same way the committed schemas are. */
    private fun moduleRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .firstOrNull { Files.isDirectory(it.resolve("src/commonMain/kotlin")) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("Could not locate the module directory from ${Path.of("").toAbsolutePath()}")
    }

    private companion object {
        const val DATABASE_FACTORY = "src/desktopMain/kotlin/dev/pnptracker/data/database/DatabaseFactory.kt"

        /** Every table a version 7 database has, so a lost row anywhere shows. */
        val TABLES =
            listOf(
                "games",
                "game_cells",
                "cell_segments",
                "colors",
                "color_aliases",
                "tasks",
                "task_colors",
                "task_stages",
                "progress_events",
                "history_events",
                "import_batches",
                "raw_import_blocks",
                "draft_tasks",
                "draft_task_colors",
            )
    }
}
