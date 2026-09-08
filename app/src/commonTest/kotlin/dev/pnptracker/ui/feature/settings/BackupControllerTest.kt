package dev.pnptracker.ui.feature.settings

import dev.pnptracker.AppInfo
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.BackupFileGateway
import dev.pnptracker.domain.backup.BackupFileHandle
import dev.pnptracker.domain.backup.BackupSnapshot
import dev.pnptracker.domain.backup.BackupSource
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)

private class StoppedClock(
    private val fixed: Instant,
) : Clock {
    override fun now(): Instant = fixed
}

private val emptyData =
    BackupData(
        colors = emptyList(),
        colorAliases = emptyList(),
        importBatches = emptyList(),
        games = emptyList(),
        gameCells = emptyList(),
        rawImportBlocks = emptyList(),
        tasks = emptyList(),
        cellSegments = emptyList(),
        taskColors = emptyList(),
        taskStages = emptyList(),
        progressEvents = emptyList(),
        historyEvents = emptyList(),
        importBatchCells = emptyList(),
        draftTasks = emptyList(),
        draftTaskColors = emptyList(),
    )

/** A database that answers, counts how often it was asked, and can be held open. */
private class FakeSource : BackupSource {
    var reads = 0
        private set
    var gate: CompletableDeferred<Unit>? = null
    var refuse: (() -> Nothing)? = null

    override suspend fun snapshot(): BackupSnapshot {
        reads++
        gate?.await()
        refuse?.invoke()
        return BackupSnapshot(sourceSchemaVersion = 8, data = emptyData)
    }
}

private class FakeFile(
    override val fileName: String,
    private val alreadyThere: Boolean = false,
    private val refuse: BackupFailure? = null,
) : BackupFileHandle {
    var writes = 0
        private set
    var written: ByteArray? = null
        private set
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun exists(): Boolean = alreadyThere

    override suspend fun write(bytes: ByteArray) {
        gate?.await()
        refuse?.let { throw BackupException(it) }
        writes++
        written = bytes
    }
}

private class FakeGateway(
    private vararg val answers: Any?,
) : BackupFileGateway {
    var asked = 0
        private set
    var suggested: String? = null
        private set
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun chooseDestination(suggestedName: String): BackupFileHandle? {
        suggested = suggestedName
        val answer = answers.getOrNull(asked) ?: answers.lastOrNull()
        asked++
        gate?.await()
        return when (answer) {
            is BackupFailure -> throw BackupException(answer)
            is BackupFileHandle -> answer
            else -> null
        }
    }
}

/**
 * The order the four steps happen in, and what refuses to happen twice.
 *
 * The destination is settled before anything is read, so changing one's mind
 * costs no database work at all; a file already there is not replaced until
 * somebody has said so; and the agreement to replace belongs to the file it was
 * asked about. Each of those is a way a backup could quietly destroy something,
 * and each is asked about here rather than assumed.
 */
class BackupControllerTest {
    /**
     * Lets the coroutine under test run until it is where the test expects.
     *
     * `launch` on a single thread does not start until something suspends, so a
     * test that asserted straight after it would be asking what happened before
     * anything happened at all.
     */
    private suspend fun BackupController.reaches(state: (BackupScreenState) -> Boolean) {
        repeat(100) {
            if (state(this.state)) return
            yield()
        }
    }

    private fun controllerFor(
        gateway: FakeGateway,
        source: FakeSource = FakeSource(),
    ) = BackupController(
        gateway = gateway,
        exporter = DatabaseBackupExporter(source, AppInfo.Current, StoppedClock(MOMENT)),
        clock = StoppedClock(MOMENT),
    )

    @Test
    fun `a new destination is chosen, read once and written once`() =
        runBlocking<Unit> {
            val file = FakeFile("pnp-yedek-2026-09-08.json")
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(file), source)

            assertEquals(BackupScreenState.Idle, controller.state)
            controller.saveBackup()

            assertEquals(BackupScreenState.Saved("pnp-yedek-2026-09-08.json"), controller.state)
            assertEquals(1, source.reads)
            assertEquals(1, file.writes)
        }

    @Test
    fun `the bytes written are the document's own`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json")
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(file), source)
            controller.saveBackup()

            val document =
                DatabaseBackupExporter(FakeSource(), AppInfo.Current, StoppedClock(MOMENT)).backupDocument()
            assertEquals(document.json, file.written?.decodeToString())
        }

    @Test
    fun `the name offered to the dialog is the day's own`() =
        runBlocking<Unit> {
            val gateway = FakeGateway(null)
            val controller = controllerFor(gateway)
            controller.saveBackup()

            assertEquals(controller.suggestedFileName(), gateway.suggested)
            assertTrue(gateway.suggested.orEmpty().startsWith("pnp-yedek-"), gateway.suggested.orEmpty())
            assertTrue(gateway.suggested.orEmpty().endsWith(".json"))
        }

    @Test
    fun `changing one's mind reads nothing and shows no problem`() =
        runBlocking<Unit> {
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(null), source)

            controller.saveBackup()

            assertEquals(BackupScreenState.Idle, controller.state)
            assertEquals(0, source.reads, "the database was read for a backup nobody asked to keep")
        }

    @Test
    fun `a file already there is asked about before it is touched`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json", alreadyThere = true)
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(file), source)

            controller.saveBackup()

            assertEquals(BackupScreenState.ConfirmingOverwrite("yedek.json"), controller.state)
            assertEquals(0, source.reads)
            assertEquals(0, file.writes)
        }

    @Test
    fun `backing out of the question leaves the file alone`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json", alreadyThere = true)
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(file), source)
            controller.saveBackup()

            controller.cancelOverwrite()

            assertEquals(BackupScreenState.Idle, controller.state)
            assertEquals(0, source.reads)
            assertEquals(0, file.writes)
        }

    @Test
    fun `agreeing to the question writes once`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json", alreadyThere = true)
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(file), source)
            controller.saveBackup()

            controller.confirmOverwrite()

            assertEquals(BackupScreenState.Saved("yedek.json"), controller.state)
            assertEquals(1, source.reads)
            assertEquals(1, file.writes)
        }

    @Test
    fun `an agreement cannot be answered twice`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json", alreadyThere = true)
            val controller = controllerFor(FakeGateway(file))
            controller.saveBackup()

            controller.confirmOverwrite()
            controller.confirmOverwrite()

            assertEquals(1, file.writes, "the same agreement wrote the file twice")
        }

    @Test
    fun `an agreement does not carry over to a different file`() =
        runBlocking<Unit> {
            // The user is asked about one file, backs out, and picks another. The
            // answer they gave belonged to the first, and there is nothing left
            // for it to apply to.
            val first = FakeFile("eski.json", alreadyThere = true)
            val second = FakeFile("yeni.json")
            val controller = controllerFor(FakeGateway(first, second))
            controller.saveBackup()
            assertEquals(BackupScreenState.ConfirmingOverwrite("eski.json"), controller.state)

            controller.cancelOverwrite()
            controller.saveBackup()
            controller.confirmOverwrite()

            assertEquals(0, first.writes, "a file nobody agreed to replace was replaced")
            assertEquals(1, second.writes)
        }

    @Test
    fun `a second press while the dialog is open opens nothing`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json")
            val gateway = FakeGateway(file)
            gateway.gate = CompletableDeferred()
            val controller = controllerFor(gateway)

            val first = launch { controller.saveBackup() }
            controller.reaches { it is BackupScreenState.ChoosingDestination }
            controller.saveBackup()
            controller.saveBackup()
            assertEquals(BackupScreenState.ChoosingDestination, controller.state)

            gateway.gate?.complete(Unit)
            first.join()
            assertEquals(1, gateway.asked, "a repeated press opened a second save dialog")
        }

    @Test
    fun `a second press while the file is being written writes nothing more`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json")
            file.gate = CompletableDeferred()
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(file), source)

            val first = launch { controller.saveBackup() }
            controller.reaches { it is BackupScreenState.Writing }
            controller.saveBackup()
            assertIs<BackupScreenState.Writing>(controller.state)

            file.gate?.complete(Unit)
            first.join()
            assertEquals(1, file.writes)
            assertEquals(1, source.reads)
        }

    @Test
    fun `the button behind an open question does nothing`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json", alreadyThere = true)
            val gateway = FakeGateway(file)
            val controller = controllerFor(gateway)
            controller.saveBackup()

            controller.saveBackup()

            assertEquals(BackupScreenState.ConfirmingOverwrite("yedek.json"), controller.state)
            assertEquals(1, gateway.asked)
        }

    @Test
    fun `reading the database is its own step, told apart from writing the file`() =
        runBlocking<Unit> {
            val source = FakeSource()
            source.gate = CompletableDeferred()
            val controller = controllerFor(FakeGateway(FakeFile("yedek.json")), source)

            val running = launch { controller.saveBackup() }
            controller.reaches { it is BackupScreenState.Preparing }
            assertEquals(BackupScreenState.Preparing, controller.state)

            source.gate?.complete(Unit)
            running.join()
            assertIs<BackupScreenState.Saved>(controller.state)
        }

    @Test
    fun `a database that will not be read is said so, and nothing is written`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json")
            val source = FakeSource()
            source.refuse = { throw androidx.sqlite.SQLiteException("disk I/O error") }
            val controller = controllerFor(FakeGateway(file), source)

            controller.saveBackup()

            assertEquals(BackupScreenState.Failed(BackupFailure.COULD_NOT_READ_DATABASE), controller.state)
            assertEquals(0, file.writes)
        }

    @Test
    fun `a broken invariant is not dressed up as a failed backup`() =
        runBlocking<Unit> {
            val source = FakeSource()
            source.refuse = { throw IllegalStateException("a task with two segments") }
            val controller = controllerFor(FakeGateway(FakeFile("yedek.json")), source)

            var thrown: Throwable? = null
            try {
                controller.saveBackup()
            } catch (defect: IllegalStateException) {
                thrown = defect
            }
            assertTrue(thrown != null, "a programming error was reported as an ordinary failure")
        }

    @Test
    fun `every failure the file system reports becomes its own answer`() =
        runBlocking<Unit> {
            val fileSystemFailures =
                listOf(
                    BackupFailure.NOT_WRITABLE,
                    BackupFailure.TEMPORARY_FILE_FAILED,
                    BackupFailure.TARGET_UNAVAILABLE,
                    BackupFailure.WRITE_FAILED,
                    BackupFailure.NOT_ATOMIC,
                )
            fileSystemFailures.forEach { failure ->
                val controller = controllerFor(FakeGateway(FakeFile("yedek.json", refuse = failure)))
                controller.saveBackup()
                assertEquals(BackupScreenState.Failed(failure), controller.state, "$failure was reported as something else")
            }
        }

    @Test
    fun `a dialog that answers with nothing usable is said so`() =
        runBlocking<Unit> {
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(BackupFailure.NO_DESTINATION), source)

            controller.saveBackup()

            assertEquals(BackupScreenState.Failed(BackupFailure.NO_DESTINATION), controller.state)
            assertEquals(0, source.reads)
        }

    @Test
    fun `a name this cannot write is refused before the database is read`() =
        runBlocking<Unit> {
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(BackupFailure.UNSUPPORTED_FILE_TYPE), source)

            controller.saveBackup()

            assertEquals(BackupScreenState.Failed(BackupFailure.UNSUPPORTED_FILE_TYPE), controller.state)
            assertEquals(0, source.reads)
        }

    @Test
    fun `another backup can be taken after one succeeded`() =
        runBlocking<Unit> {
            val first = FakeFile("bir.json")
            val second = FakeFile("iki.json")
            val source = FakeSource()
            val controller = controllerFor(FakeGateway(first, second), source)

            controller.saveBackup()
            controller.startOver()
            controller.saveBackup()

            assertEquals(BackupScreenState.Saved("iki.json"), controller.state)
            assertEquals(2, source.reads)
            assertEquals(1, first.writes)
            assertEquals(1, second.writes)
        }

    @Test
    fun `another backup can be taken after one failed`() =
        runBlocking<Unit> {
            val refused = FakeFile("bir.json", refuse = BackupFailure.WRITE_FAILED)
            val second = FakeFile("iki.json")
            val controller = controllerFor(FakeGateway(refused, second))

            controller.saveBackup()
            assertEquals(BackupScreenState.Failed(BackupFailure.WRITE_FAILED), controller.state)

            controller.saveBackup()
            assertEquals(BackupScreenState.Saved("iki.json"), controller.state)
        }

    @Test
    fun `the keyboard is called back once for each surface that closes`() =
        runBlocking<Unit> {
            val controller = controllerFor(FakeGateway(FakeFile("yedek.json")))
            assertEquals(0, controller.focusRecall)

            controller.saveBackup()

            assertEquals(1, controller.focusRecall, "the outcome did not ask for the keyboard back")
        }

    @Test
    fun `starting over while something is running changes nothing`() =
        runBlocking<Unit> {
            val file = FakeFile("yedek.json")
            file.gate = CompletableDeferred()
            val controller = controllerFor(FakeGateway(file))

            val running = launch { controller.saveBackup() }
            controller.reaches { it is BackupScreenState.Writing }
            controller.startOver()
            assertIs<BackupScreenState.Writing>(controller.state)

            file.gate?.complete(Unit)
            running.join()
            assertEquals(BackupScreenState.Saved("yedek.json"), controller.state)
            assertEquals(1, file.writes, "leaving mid-write abandoned or repeated the file")
        }
}
