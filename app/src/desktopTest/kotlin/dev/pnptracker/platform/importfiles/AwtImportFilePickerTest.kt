package dev.pnptracker.platform.importfiles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins down how the picker reaches the AWT event dispatch thread.
 *
 * This is the one place where the import flow touches a toolkit thread, and it
 * was wrong once: `Dispatchers.Main` has no provider on this classpath, so the
 * very first click on "choose a file" threw instead of opening a dialog. Nothing
 * caught it, because every other test stands in for the picker as a whole.
 *
 * So the dialog itself is faked here — never a real [FileDialog], which would put
 * a window on whoever is running the tests — while the thread hand-off around it
 * is the real one.
 */
class AwtImportFilePickerTest {
    @Test
    fun `the dialog runs on the awt event dispatch thread`() {
        val onDispatchThread = AtomicBoolean(false)
        val threadName = AtomicReference("")
        val picker =
            AwtImportFilePicker(
                ModalFileDialog {
                    onDispatchThread.set(EventQueue.isDispatchThread())
                    threadName.set(Thread.currentThread().name)
                    null
                },
            )

        // Deliberately off the dispatch thread, which is where the import flow
        // would be if Compose ever moved this call off the toolkit thread.
        runBlocking(Dispatchers.Default) {
            check(!EventQueue.isDispatchThread()) { "the caller was already on the dispatch thread" }
            picker.chooseImportFile()
        }

        assertTrue(
            onDispatchThread.get(),
            "the dialog ran on ${threadName.get()} instead of the awt event dispatch thread",
        )
    }

    @Test
    fun `the chosen file is handed back unchanged`() {
        val chosen = Path.of("/some/where/kitap.xlsx")
        val picker = AwtImportFilePicker(ModalFileDialog { chosen })

        val answer = runBlocking(Dispatchers.Default) { picker.chooseImportFile() }

        assertEquals(chosen, answer)
    }

    @Test
    fun `closing the dialog without choosing is not a failure`() {
        val picker = AwtImportFilePicker(ModalFileDialog { null })

        val answer = runBlocking(Dispatchers.Default) { picker.chooseImportFile() }

        assertNull(answer, "changing one's mind must stay an ordinary null, not an error")
    }

    @Test
    fun `an unexpected failure inside the dialog reaches the caller as it was`() {
        val thrown = IllegalStateException("the toolkit is in a state nobody expected")
        val picker = AwtImportFilePicker(ModalFileDialog { throw thrown })

        val caught =
            assertFailsWith<IllegalStateException> {
                runBlocking(Dispatchers.Default) { picker.chooseImportFile() }
            }

        assertEquals(thrown.message, caught.message, "the failure was replaced instead of passed on")
    }

    @Test
    fun `a programming error is not dressed up as a file problem`() {
        val picker = AwtImportFilePicker(ModalFileDialog { error("invariant broken") })

        // Nothing here turns a defect into ImportFailure.DAMAGED_FILE or any other
        // "your file is bad" answer, which would send the user hunting the wrong thing.
        assertFailsWith<IllegalStateException> {
            runBlocking(Dispatchers.Default) { picker.chooseImportFile() }
        }
    }

    @Test
    fun `cancelling the caller takes the open dialog off the screen`() {
        // A modal dialog blocks the dispatch thread but keeps pumping events, and a
        // secondary loop is the same mechanism AWT itself uses for that, so a
        // cancellation queued behind it still gets through.
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        val picker =
            AwtImportFilePicker(
                ModalFileDialog { publish ->
                    publish {
                        closeCount.incrementAndGet()
                        loop.exit()
                        closed.countDown()
                    }
                    opened.countDown()
                    loop.enter()
                    null
                },
            )

        runBlocking(Dispatchers.Default) {
            val job = launch { picker.chooseImportFile() }
            assertTrue(opened.await(10, TimeUnit.SECONDS), "the fake dialog never opened")
            job.cancel()
            job.join()
        }

        // Closing is queued onto the dispatch thread, so the cancelled caller is
        // free again before the window has actually gone.
        assertTrue(closed.await(10, TimeUnit.SECONDS), "the dialog was left open after the caller was cancelled")
        assertEquals(1, closeCount.get(), "the dialog was closed more than once")
    }

    @Test
    fun `no real file dialog is ever put on the screen`() {
        val before = fileDialogCount()

        runBlocking(Dispatchers.Default) { AwtImportFilePicker(ModalFileDialog { null }).chooseImportFile() }

        assertEquals(before, fileDialogCount(), "a real awt file dialog was created")
    }

    @Test
    fun `the picker does not reach for a coroutine main dispatcher`() {
        val code = withoutComments(Files.readString(pickerSource()))

        assertTrue(
            "Dispatchers" !in code,
            "the picker is back on a coroutine dispatcher; there is no provider for one here",
        )
    }

    @Test
    fun `no main dispatcher artefact has been added to the runtime classpath`() {
        val forbidden = listOf("kotlinx-coroutines-swing", "kotlinx-coroutines-javafx", "kotlinx-coroutines-android")
        val entries = System.getProperty("java.class.path").split(java.io.File.pathSeparator)

        val added = entries.filter { entry -> forbidden.any { it in entry } }

        assertEquals(emptyList(), added, "a dispatcher dependency was added rather than using AWT's own queue")
    }

    private fun fileDialogCount(): Int = Window.getWindows().count { it is FileDialog }

    /** Strips KDoc and line comments, so prose about the old bug is not read as code. */
    private fun withoutComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun pickerSource(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .map { it.resolve("src/desktopMain/kotlin/dev/pnptracker/platform/importfiles/ImportFilePicker.kt") }
                .firstOrNull { Files.isRegularFile(it) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("Could not locate ImportFilePicker.kt from ${Path.of("").toAbsolutePath()}")
    }
}
