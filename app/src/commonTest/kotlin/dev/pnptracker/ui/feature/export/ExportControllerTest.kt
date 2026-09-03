package dev.pnptracker.ui.feature.export

import dev.pnptracker.data.repository.TaskExportSource
import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.ExportFileGateway
import dev.pnptracker.domain.export.ExportFileHandle
import dev.pnptracker.domain.export.ExportedTask
import dev.pnptracker.domain.export.TaskExportException
import dev.pnptracker.domain.export.TaskExportNames
import dev.pnptracker.domain.export.TaskExportStatus
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.PoolType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The order the four steps happen in, and what each of them refuses.
 *
 * The order is the design: the destination is settled before anything is read,
 * so changing one's mind costs no database read at all and writes nothing. Every
 * one of these drives the real controller without a database, a file or a
 * composition.
 */
class ExportControllerTest {
    private val names =
        TaskExportNames(
            pools = PoolType.entries.associateWith { it.name },
            columns = CellColumnType.entries.associateWith { it.name },
        )

    private fun task(name: String = "Kırmızı ev") =
        ExportedTask(
            gameName = "Harmonies",
            columnType = CellColumnType.THREE_D,
            taskName = name,
            poolType = PoolType.THREE_D,
            colorNames = emptyList(),
            requiredQuantity = 12,
            status = TaskExportStatus.OPEN,
            notes = null,
        )

    /** Remembers what it was asked to write, and can be made to refuse. */
    private class FakeFile(
        override val fileName: String = "gorevler.csv",
        private val alreadyThere: Boolean = false,
        private val writeFailure: ExportFailure? = null,
    ) : ExportFileHandle {
        var written: String? = null
            private set
        var writes = 0
            private set

        override suspend fun exists(): Boolean = alreadyThere

        override suspend fun write(content: String) {
            writes++
            writeFailure?.let { throw TaskExportException(it) }
            written = content
        }
    }

    private class FakeGateway(
        private val destination: ExportFileHandle?,
        private val failure: ExportFailure? = null,
    ) : ExportFileGateway {
        var calls = 0
            private set

        override suspend fun chooseDestination(suggestedName: String): ExportFileHandle? {
            calls++
            failure?.let { throw TaskExportException(it) }
            return destination
        }
    }

    /** A gateway that waits, so a second click can arrive while the first is out. */
    private class WaitingGateway(
        private val destination: ExportFileHandle,
        private val released: CompletableDeferred<Unit>,
        private val arrived: CompletableDeferred<Unit>,
    ) : ExportFileGateway {
        var calls = 0
            private set

        override suspend fun chooseDestination(suggestedName: String): ExportFileHandle {
            calls++
            arrived.complete(Unit)
            released.await()
            return destination
        }
    }

    private class FixedTasks(
        private val tasks: List<ExportedTask>,
        private val failure: ExportFailure? = null,
    ) : TaskExportSource {
        var reads = 0
            private set

        override suspend fun exportedTasks(): List<ExportedTask> {
            reads++
            failure?.let { throw TaskExportException(it) }
            return tasks
        }
    }

    private fun controllerFor(
        gateway: ExportFileGateway,
        source: TaskExportSource = FixedTasks(listOf(task())),
    ) = ExportController(gateway, source) { names }

    // ------------------------------------------------------------- the happy way

    @Test
    fun `a new file is written and said to be written`() =
        runBlocking<Unit> {
            val file = FakeFile()
            val controller = controllerFor(FakeGateway(file))

            controller.exportTasks()

            val written = assertIs<ExportScreenState.Written>(controller.state)
            assertEquals("gorevler.csv", written.fileName)
            assertEquals(1, written.taskCount)
            assertTrue(file.written.orEmpty().contains("game,column,task"), "the heading never reached the file")
        }

    @Test
    fun `the screen starts with nothing happening`() {
        assertEquals(ExportScreenState.Idle, controllerFor(FakeGateway(FakeFile())).state)
    }

    // -------------------------------------------------------------- changing mind

    @Test
    fun `cancelling reads nothing, writes nothing and is not a failure`() =
        runBlocking<Unit> {
            val source = FixedTasks(listOf(task()))
            val controller = controllerFor(FakeGateway(destination = null), source)

            controller.exportTasks()

            assertEquals(ExportScreenState.Idle, controller.state)
            assertEquals(0, source.reads, "the database was read for an export nobody asked to finish")
        }

    // ------------------------------------------------------------- overwriting

    @Test
    fun `a file that is already there is not replaced without being asked about`() =
        runBlocking<Unit> {
            val file = FakeFile(alreadyThere = true)
            val source = FixedTasks(listOf(task()))
            val controller = controllerFor(FakeGateway(file), source)

            controller.exportTasks()

            assertEquals(ExportScreenState.ConfirmingOverwrite("gorevler.csv"), controller.state)
            assertEquals(0, file.writes, "the old file was replaced before anybody agreed to it")
            assertEquals(0, source.reads, "the database was read before the question was answered")
        }

    @Test
    fun `agreeing replaces it`() =
        runBlocking<Unit> {
            val file = FakeFile(alreadyThere = true)
            val controller = controllerFor(FakeGateway(file))
            controller.exportTasks()

            controller.confirmOverwrite()

            assertIs<ExportScreenState.Written>(controller.state)
            assertEquals(1, file.writes)
        }

    @Test
    fun `refusing leaves the old file exactly as it was`() =
        runBlocking<Unit> {
            val file = FakeFile(alreadyThere = true)
            val controller = controllerFor(FakeGateway(file))
            controller.exportTasks()

            controller.cancelOverwrite()

            assertEquals(ExportScreenState.Idle, controller.state)
            assertEquals(0, file.writes)
        }

    @Test
    fun `refusing sends the keyboard back to the button that asked`() =
        runBlocking<Unit> {
            val controller = controllerFor(FakeGateway(FakeFile(alreadyThere = true)))
            controller.exportTasks()
            val before = controller.focusRecall

            controller.cancelOverwrite()

            assertTrue(controller.focusRecall > before)
        }

    @Test
    fun `the button does nothing while the question is on screen`() =
        runBlocking<Unit> {
            val gateway = FakeGateway(FakeFile(alreadyThere = true))
            val controller = controllerFor(gateway)
            controller.exportTasks()

            controller.exportTasks()

            assertEquals(1, gateway.calls, "a second dialog opened over an unanswered question")
            assertIs<ExportScreenState.ConfirmingOverwrite>(controller.state)
        }

    // ------------------------------------------------------------- what fails

    @Test
    fun `nothing to write is reported and no file is made`() =
        runBlocking<Unit> {
            val file = FakeFile()
            val controller = controllerFor(FakeGateway(file), FixedTasks(emptyList(), ExportFailure.NOTHING_TO_EXPORT))

            controller.exportTasks()

            assertEquals(ExportScreenState.Failed(ExportFailure.NOTHING_TO_EXPORT), controller.state)
            assertEquals(0, file.writes, "an empty file was made for an export with nothing in it")
        }

    @Test
    fun `a broken record is reported and no file is made`() =
        runBlocking<Unit> {
            val file = FakeFile()
            val controller = controllerFor(FakeGateway(file), FixedTasks(emptyList(), ExportFailure.BROKEN_DATA))

            controller.exportTasks()

            assertEquals(ExportScreenState.Failed(ExportFailure.BROKEN_DATA), controller.state)
            assertEquals(0, file.writes)
        }

    @Test
    fun `a name this does not write is refused before anything is read`() =
        runBlocking<Unit> {
            val source = FixedTasks(listOf(task()))
            val controller =
                controllerFor(FakeGateway(destination = null, failure = ExportFailure.UNSUPPORTED_FILE_TYPE), source)

            controller.exportTasks()

            assertEquals(ExportScreenState.Failed(ExportFailure.UNSUPPORTED_FILE_TYPE), controller.state)
            assertEquals(0, source.reads)
        }

    @Test
    fun `a file that could not be written is reported plainly`() =
        runBlocking<Unit> {
            val controller = controllerFor(FakeGateway(FakeFile(writeFailure = ExportFailure.NOT_ATOMIC)))

            controller.exportTasks()

            assertEquals(ExportScreenState.Failed(ExportFailure.NOT_ATOMIC), controller.state)
        }

    @Test
    fun `an export that failed can simply be tried again`() =
        runBlocking<Unit> {
            val failing = controllerFor(FakeGateway(FakeFile(writeFailure = ExportFailure.WRITE_FAILED)))
            failing.exportTasks()
            assertIs<ExportScreenState.Failed>(failing.state)

            val file = FakeFile()
            val second = controllerFor(FakeGateway(file))
            second.exportTasks()

            assertIs<ExportScreenState.Written>(second.state)
            assertEquals(1, file.writes)
        }

    @Test
    fun `a result can be dismissed and the screen goes back to where it was`() =
        runBlocking<Unit> {
            val controller = controllerFor(FakeGateway(FakeFile()))
            controller.exportTasks()

            controller.startOver()

            assertEquals(ExportScreenState.Idle, controller.state)
        }

    // ------------------------------------------------------------- two clicks

    @Test
    fun `a second click while the dialog is open opens no second dialog`() =
        runBlocking<Unit> {
            val released = CompletableDeferred<Unit>()
            val arrived = CompletableDeferred<Unit>()
            val file = FakeFile()
            val gateway = WaitingGateway(file, released, arrived)
            val controller = controllerFor(gateway)

            val first = launch(Dispatchers.Default) { controller.exportTasks() }
            withTimeout(10_000) { arrived.await() }
            assertTrue(controller.isBusy)

            controller.exportTasks()
            assertEquals(1, gateway.calls, "a second click opened a second save dialog")

            released.complete(Unit)
            first.join()
            assertEquals(1, file.writes, "a second click wrote the file twice")
        }
}
