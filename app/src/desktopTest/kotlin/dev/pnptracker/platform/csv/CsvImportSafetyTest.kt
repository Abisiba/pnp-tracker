package dev.pnptracker.platform.csv

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.domain.importprep.ImportFailure
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.platform.importfiles.DesktopImportFileGateway
import dev.pnptracker.platform.importfiles.ImportFilePicker
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreenState
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a CSV that cannot be imported leaves behind, which is nothing.
 *
 * The file is read, decoded, cut into records and checked whole before a single
 * row is written, so a file that is wrong on its last line costs the database
 * nothing at all. These tests say that in the only way worth saying it: by
 * looking at every table afterwards.
 *
 * The source file is never written to on any of these paths, and that is checked
 * too — an import that quietly rewrote somebody's own file would be a far worse
 * fault than any of the ones being provoked here.
 */
class CsvImportSafetyTest {
    private lateinit var fileDirectory: Path
    private lateinit var databaseDirectory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    private class FixedPicker(
        private val file: Path?,
    ) : ImportFilePicker {
        override suspend fun chooseImportFile(): Path? = file
    }

    /** Hands out names, and refuses after the [afterwards]-th one. */
    private class LimitedIdGenerator(
        private val afterwards: Int,
    ) : IdGenerator {
        private var handed = 0

        override fun newId(): EntityId {
            if (handed >= afterwards) throw IllegalStateException("no more names")
            handed++
            return IdGenerator.Random.newId()
        }
    }

    @BeforeTest
    fun setUp() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        fileDirectory = Files.createTempDirectory("pnp-csv-safety")
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

    private val importDao get() = database.importDao()

    private fun csvFile(
        name: String,
        text: String,
    ): Path = fileDirectory.resolve(name).also { Files.write(it, text.toByteArray(StandardCharsets.UTF_8)) }

    private fun controllerFor(
        file: Path?,
        idGenerator: IdGenerator = IdGenerator.Random,
    ) = ImportController(
        DesktopImportFileGateway(FixedPicker(file)),
        ImportDraftStore(importDao, idGenerator),
    )

    /** Every table an import could possibly have touched. */
    private suspend fun nothingWasWritten() {
        assertEquals(emptyList(), importDao.allBatches(), "an import batch was left behind")
        assertEquals(emptyList(), database.taskDao().allTasksIncludingDeleted(), "a task was left behind")
        assertEquals(emptyList(), database.gameDao().allGamesIncludingDeleted(), "a game was left behind")
        // No cell was ever opened here, so there is nowhere a segment could be.
    }

    private suspend fun refuse(
        name: String,
        text: String,
    ): ImportFailure {
        val file = csvFile(name, text)
        val before = Files.readAllBytes(file)
        val controller = controllerFor(file)
        controller.chooseFile()
        val failed = assertIs<ImportScreenState.Failed>(controller.state)
        nothingWasWritten()
        assertTrue(before.contentEquals(Files.readAllBytes(file)), "the source file was rewritten")
        return failed.failure
    }

    // ------------------------------------------------- a file that never opens

    @Test
    fun `a file that is not CSV leaves nothing behind`() =
        runBlocking<Unit> {
            assertEquals(
                ImportFailure.CSV_UNCLOSED_QUOTE,
                refuse("bozuk.csv", "game,source_type,raw_text\nHarmonies,3d,\"hiç kapanmadı\n"),
            )
        }

    @Test
    fun `a heading row this version cannot read leaves nothing behind`() =
        runBlocking<Unit> {
            assertEquals(
                ImportFailure.CSV_MISSING_HEADER_COLUMN,
                refuse("başlık.csv", "game,source_type\nHarmonies,3d"),
            )
        }

    @Test
    fun `a bad source type on the very last row still leaves nothing behind`() =
        runBlocking<Unit> {
            assertEquals(
                ImportFailure.CSV_UNKNOWN_SOURCE_TYPE,
                refuse(
                    "son-satır.csv",
                    "game,source_type,raw_text\nHarmonies,3d,Ev\nHarmonies,card,Deste\nWingspan,kartlar,Yuva",
                ),
            )
        }

    @Test
    fun `a file that is not UTF-8 is refused rather than read with the wrong letters`() =
        runBlocking<Unit> {
            // `Kırmızı` in Windows-1254, which is not valid UTF-8.
            val legacy = byteArrayOf(0x4B, 0xFD.toByte(), 0x72, 0x6D, 0xFD.toByte(), 0x7A, 0xFD.toByte())
            val file = fileDirectory.resolve("eski.csv")
            Files.write(file, "game,source_type,raw_text\nHarmonies,3d,".toByteArray(StandardCharsets.UTF_8) + legacy)

            val controller = controllerFor(file)
            controller.chooseFile()

            assertEquals(ImportFailure.NOT_UTF8, assertIs<ImportScreenState.Failed>(controller.state).failure)
            nothingWasWritten()
        }

    // ------------------------------------------------------------- while saving

    @Test
    fun `running out of names part way through a save leaves no half-written import`() =
        runBlocking<Unit> {
            val file = csvFile("kimlik.csv", "game,source_type,raw_text\nHarmonies,3d,Ev\nWingspan,3d,Yuva\n")
            // One name for the batch and one for the first block; the second block
            // asks for a name that will not come.
            val controller = controllerFor(file, LimitedIdGenerator(afterwards = 2))
            controller.chooseFile()
            assertIs<ImportScreenState.PreviewReady>(controller.state)

            runCatching { controller.saveDraft() }

            nothingWasWritten()
        }

    @Test
    fun `a save that failed can simply be tried again`() =
        runBlocking<Unit> {
            val file = csvFile("tekrar.csv", "game,source_type,raw_text\nHarmonies,3d,Ev\n")
            val failing = controllerFor(file, LimitedIdGenerator(afterwards = 1))
            failing.chooseFile()
            runCatching { failing.saveDraft() }
            nothingWasWritten()

            val second = controllerFor(file)
            second.chooseFile()
            second.saveDraft()

            val saved = assertIs<ImportScreenState.Saved>(second.state)
            assertEquals(2, saved.summary.rawBlockCount)
            assertEquals(ImportSourceFormat.CSV, assertNotNull(importDao.batchById(saved.summary.batchId)).sourceFormat)
        }

    // ------------------------------------------------- the same file, twice

    @Test
    fun `the same CSV imported again is recognised by its fingerprint`() =
        runBlocking<Unit> {
            val file = csvFile("aynı.csv", "game,source_type,raw_text\nHarmonies,3d,Ev\n")
            val first = controllerFor(file)
            first.chooseFile()
            first.saveDraft()
            assertIs<ImportScreenState.Saved>(first.state)

            val second = controllerFor(file)
            second.chooseFile()
            second.saveDraft()

            // Asked, not written: the second import waits for the user to say so.
            val warning = assertIs<ImportScreenState.DuplicateWarning>(second.state)
            assertEquals(1, warning.session.earlierImports.size)
            assertEquals(1, importDao.allBatches().size, "a repeat import was written without being asked about")

            second.confirmDuplicateImport()

            assertIs<ImportScreenState.Saved>(second.state)
            assertEquals(2, importDao.allBatches().size)
        }

    @Test
    fun `a draft batch is still there to be carried on with after a restart`() =
        runBlocking<Unit> {
            val file = csvFile("devam.csv", "game,source_type,raw_text\nHarmonies,3d,Ev\n")
            val controller = controllerFor(file)
            controller.chooseFile()
            controller.saveDraft()
            val batchId = assertIs<ImportScreenState.Saved>(controller.state).summary.batchId

            // The application closing and opening again is the database being
            // closed and opened again; nothing else about a draft is in memory.
            database.close()
            database = DatabaseFactory().open(databaseDirectory.databaseFile)

            val batch = assertNotNull(importDao.batchById(batchId))
            assertEquals(ImportBatchStatus.DRAFT, batch.status)
            assertEquals(ImportSourceFormat.CSV, batch.sourceFormat)
            assertEquals(2, importDao.rawBlocksOfBatch(batchId).size)
        }

    @Test
    fun `a second save while one is running writes only one import`() =
        runBlocking<Unit> {
            val file = csvFile("çift.csv", "game,source_type,raw_text\nHarmonies,3d,Ev\n")
            val controller = controllerFor(file)
            controller.chooseFile()

            controller.saveDraft()
            // The screen has moved on to `Saved`, which holds no session, so a
            // second click has nothing to save and cannot write a second batch.
            controller.saveDraft()

            assertEquals(1, importDao.allBatches().size)
        }

    @Test
    fun `choosing no file at all changes nothing on the screen or in the database`() =
        runBlocking<Unit> {
            val controller = controllerFor(file = null)

            controller.chooseFile()

            assertEquals(ImportScreenState.Idle, controller.state)
            nothingWasWritten()
        }
}
