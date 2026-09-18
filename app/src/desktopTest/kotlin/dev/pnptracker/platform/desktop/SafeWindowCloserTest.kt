package dev.pnptracker.platform.desktop

import dev.pnptracker.ui.Strings
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Which window a desktop smoke run may close: exactly one, titled exactly as
 * expected, belonging to the process the run started — checked again right
 * before the request — and never anything a title or a listing smuggles in.
 *
 * The window manager is played by [FakeDesktop], which answers the three
 * commands the closer is allowed to run and records every command it was given.
 */
class SafeWindowCloserTest {
    private val ours = 4242L
    private val ourWindow = window("0x05a00007", ours, APP_TITLE)

    private fun window(
        id: String,
        pid: Long,
        title: String,
    ) = DesktopWindow(id, pid, title)

    /** How `wmctrl -l -p` prints a window: padded desktop and process id, then the host and the title. */
    private fun listed(vararg windows: DesktopWindow): String =
        windows.joinToString("\n", postfix = "\n") { "${it.id}  0 ${it.pid.toString().padEnd(6)} makine ${it.title}" }

    private class FakeDesktop(
        vararg listings: String,
        private val owners: Map<String, Long>,
    ) : Commands {
        private val listings = ArrayDeque(listings.toList())
        val calls = mutableListOf<List<String>>()

        val closeRequests: List<List<String>>
            get() = calls.filter { it.take(3) == listOf("wmctrl", "-i", "-c") }

        /** Each listing in turn; the last one is what the desktop keeps showing. */
        private fun nextListing(): String = if (listings.size > 1) listings.removeFirst() else listings.first()

        override fun run(argv: List<String>): CommandResult {
            calls += argv
            return when {
                argv == listOf("wmctrl", "-l", "-p") -> CommandResult(0, nextListing())
                argv.size == 4 && argv[0] == "xprop" && argv[1] == "-id" && argv[3] == "_NET_WM_PID" ->
                    owners[argv[2]]?.let { CommandResult(0, "_NET_WM_PID(CARDINAL) = $it\n") }
                        ?: CommandResult(1, "")
                argv.size == 4 && argv.take(3) == listOf("wmctrl", "-i", "-c") -> CommandResult(0, "")
                else -> error("the closer ran a command it has no business running: $argv")
            }
        }
    }

    private fun closer(desktop: FakeDesktop) = SafeWindowCloser(desktop) { it == ours }

    @Test
    fun `the smoke looks for the title the application really gives its window`() =
        runBlocking<Unit> {
            assertEquals(getString(Strings.App.windowTitle), MAIN_WINDOW_TITLE)
            assertEquals(MAIN_WINDOW_TITLE, APP_TITLE)
        }

    // ------------------------------------------------------------- choosing

    @Test
    fun `a window that carries only part of the title is never chosen`() {
        // The first is shaped like the window a search for part of the title once
        // picked on a real desktop: another program's, holding "PnP" and part of
        // the application's name.
        val listing =
            listed(
                window("0x01e00004", 1101, "◐ PnP üretim takibi taslak notları"),
                window("0x02c00011", 1202, "PnP Üretim Takipçisi — Firefox"),
                // Even the run's own process: a dialog is not the main window.
                window("0x05a0000c", ours, "PnP Üretim Takipçisi - dosya seç"),
                window("0x05a0000d", ours, "pnp üretim takipçisi"),
                window("0x05a0000e", ours, " PnP Üretim Takipçisi"),
            )
        val desktop = FakeDesktop(listing, owners = mapOf("0x05a0000c" to ours))

        assertIs<WindowChoice.None>(chooseWindow(listing, APP_TITLE) { it == ours })
        assertIs<CloseOutcome.Refused>(closer(desktop).close(APP_TITLE))
        assertEquals(emptyList(), desktop.closeRequests)
    }

    @Test
    fun `the one window of the run with the exact title is chosen and only it is closed`() {
        val listing =
            listed(
                window("0x01e00004", 1101, "◐ PnP üretim takibi taslak notları"),
                ourWindow,
                window("0x03400009", 1303, "Terminal"),
            )
        val desktop = FakeDesktop(listing, owners = mapOf(ourWindow.id to ours))

        assertEquals(WindowChoice.One(ourWindow), chooseWindow(listing, APP_TITLE) { it == ours })
        assertEquals(CloseOutcome.Closed(ourWindow), closer(desktop).close(APP_TITLE))
        assertEquals(listOf(listOf("wmctrl", "-i", "-c", ourWindow.id)), desktop.closeRequests)
    }

    @Test
    fun `a window with the same title that belongs to another process is not chosen`() {
        // The user's own copy of the application, open while the smoke runs.
        val theirs = window("0x04800007", 3001, APP_TITLE)

        val onlyTheirs = FakeDesktop(listed(theirs), owners = mapOf(theirs.id to 3001L))
        val refused = closer(onlyTheirs).close(APP_TITLE)
        assertIs<CloseOutcome.Refused>(refused)
        assertEquals(emptyList(), onlyTheirs.closeRequests)
        assertTrue(theirs.id in refused.why, "the refusal does not say which same-titled window was left alone")

        val both = FakeDesktop(listed(theirs, ourWindow), owners = mapOf(theirs.id to 3001L, ourWindow.id to ours))
        assertEquals(CloseOutcome.Closed(ourWindow), closer(both).close(APP_TITLE))
        assertEquals(listOf(listOf("wmctrl", "-i", "-c", ourWindow.id)), both.closeRequests)
    }

    @Test
    fun `no candidate and several candidates are both refused and nothing is sent`() {
        val none = FakeDesktop(listed(window("0x03400009", 1303, "Terminal")), owners = emptyMap())
        assertIs<CloseOutcome.Refused>(closer(none).close(APP_TITLE))
        assertEquals(emptyList(), none.closeRequests)

        val second = window("0x05a00009", ours, APP_TITLE)
        val several = FakeDesktop(listed(ourWindow, second), owners = mapOf(ourWindow.id to ours, second.id to ours))
        assertEquals(WindowChoice.Several(listOf(ourWindow, second)), closer(several).find(APP_TITLE))
        assertIs<CloseOutcome.Refused>(closer(several).close(APP_TITLE))
        assertEquals(emptyList(), several.closeRequests)
    }

    // ------------------------------------------------ checked again before closing

    @Test
    fun `a window that changed hands or left between choosing and closing is not closed`() {
        val first = listed(ourWindow)
        val gone = FakeDesktop(first, listed(window("0x03400009", 1303, "Terminal")), owners = mapOf(ourWindow.id to ours))
        assertIs<CloseOutcome.Refused>(closer(gone).close(APP_TITLE))
        assertEquals(emptyList(), gone.closeRequests)

        val reused = FakeDesktop(first, listed(window(ourWindow.id, 5555, APP_TITLE)), owners = mapOf(ourWindow.id to 5555L))
        assertIs<CloseOutcome.Refused>(closer(reused).close(APP_TITLE))
        assertEquals(emptyList(), reused.closeRequests)
    }

    @Test
    fun `a window whose own process id disagrees with the listing is not closed`() {
        val disagreeing = FakeDesktop(listed(ourWindow), owners = mapOf(ourWindow.id to 9999L))
        assertIs<CloseOutcome.Refused>(closer(disagreeing).close(APP_TITLE))
        assertEquals(emptyList(), disagreeing.closeRequests)

        val unknown = FakeDesktop(listed(ourWindow), owners = emptyMap())
        assertIs<CloseOutcome.Refused>(closer(unknown).close(APP_TITLE))
        assertEquals(emptyList(), unknown.closeRequests)
    }

    // ------------------------------------------------------------- injection

    @Test
    fun `only the verified window id is ever put on a command line, as an argument of its own`() {
        val hostile =
            listOf(
                "PnP Üretim Takipçisi\"; wmctrl -c Terminal; echo \"",
                "\$(touch /tmp/pnp-pwned) PnP Üretim Takipçisi",
                "`rm -rf ~` PnP Üretim Takipçisi",
                "PnP Üretim Takipçisi && xdotool key ctrl+alt+Delete",
                "PnP Üretim Takipçisi | sh",
                "'PnP Üretim Takipçisi'",
            )
        val listing =
            listed(
                *hostile.mapIndexed { index, title -> window("0x0700000$index", 3000L + index, title) }.toTypedArray(),
                *hostile.mapIndexed { index, title -> window("0x0800000$index", ours, title) }.toTypedArray(),
                ourWindow,
            )
        val desktop = FakeDesktop(listing, owners = mapOf(ourWindow.id to ours))

        assertEquals(CloseOutcome.Closed(ourWindow), closer(desktop).close(APP_TITLE))

        val allowed = setOf("wmctrl", "-l", "-p", "-i", "-c", "xprop", "-id", "_NET_WM_PID", ourWindow.id)
        desktop.calls.flatten().forEach { argument -> assertTrue(argument in allowed, "a command was given $argument") }
        assertEquals(listOf(listOf("wmctrl", "-i", "-c", ourWindow.id)), desktop.closeRequests)
    }

    @Test
    fun `a title that breaks the listing into a forged line is never trusted`() {
        // A title may hold a line break. What follows it then looks like a window
        // of its own — here one claiming the run's process and the exact title.
        val forgedNewId = "0x02c00011  0 1202   makine zararsız\n0x0badf00d  0 $ours   makine $APP_TITLE\n"
        val forgery = FakeDesktop(forgedNewId, owners = emptyMap())
        assertIs<CloseOutcome.Refused>(closer(forgery).close(APP_TITLE), "a window that does not exist was 'closed'")
        assertEquals(emptyList(), forgery.closeRequests)

        // The same trick naming a real window's id makes that id appear twice.
        val forgedRealId =
            "0x02c00011  0 1202   makine zararsız\n" +
                "0x03400009  0 $ours   makine $APP_TITLE\n" +
                "0x03400009  0 1303   makine Terminal\n"
        assertIs<WindowChoice.Untrustworthy>(chooseWindow(forgedRealId, APP_TITLE) { it == ours })
        val victim = FakeDesktop(forgedRealId, owners = mapOf("0x03400009" to 1303L))
        assertIs<CloseOutcome.Refused>(closer(victim).close(APP_TITLE))
        assertEquals(emptyList(), victim.closeRequests)

        // And words after a break that do not read as a window spoil the whole list.
        val broken = "0x02c00011  0 1202   makine başlık\nrm -rf ~\n${listed(ourWindow)}"
        assertIs<WindowChoice.Untrustworthy>(chooseWindow(broken, APP_TITLE) { it == ours })
        val refusedBroken = FakeDesktop(broken, owners = mapOf(ourWindow.id to ours))
        assertIs<CloseOutcome.Refused>(closer(refusedBroken).close(APP_TITLE))
        assertEquals(emptyList(), refusedBroken.closeRequests)
    }

    @Test
    fun `an id or a process id that is not what wmctrl prints makes the listing unreadable`() {
        listOf(
            "0x05a00007;reboot  0 $ours   makine $APP_TITLE\n",
            "0x05a00007\$(id)  0 $ours   makine $APP_TITLE\n",
            "05a00007  0 $ours   makine $APP_TITLE\n",
            "0x05a000070  0 $ours   makine $APP_TITLE\n",
            "0x05a00007  0 -$ours   makine $APP_TITLE\n",
            "0x05a00007  0 99999999999999999999999   makine $APP_TITLE\n",
        ).forEach { listing ->
            assertIs<WindowChoice.Untrustworthy>(chooseWindow(listing, APP_TITLE) { true }, listing)
        }
    }

    @Test
    fun `the refusal names ids and process ids, never another program's title`() {
        val private = window("0x01e00004", 1101, "◐ PnP üretim takibi taslak notları")
        val theirs = window("0x04800007", 3001, APP_TITLE)
        val desktop = FakeDesktop(listed(private, theirs), owners = emptyMap())

        val refused = assertIs<CloseOutcome.Refused>(closer(desktop).close(APP_TITLE))

        assertFalse("taslak" in refused.why, refused.why)
        assertFalse("notları" in refused.why, refused.why)
    }

    // ------------------------------------------------------------- the repository

    @Test
    fun `nothing in the repository closes or drives a window except the verified closer`() {
        val repository = repositoryRoot()
        val scripts = mutableListOf<Path>()
        Files.walkFileTree(
            repository,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult =
                    if (dir.fileName?.toString() in SKIPPED_FOLDERS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile && file.fileName.toString().substringAfterLast('.', "") in SCRIPT_EXTENSIONS) scripts.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        assertTrue(scripts.size > 100, "the scan found only ${scripts.size} files; it is looking in the wrong place")

        val touching =
            scripts
                .filter { path -> WINDOW_TOOLS.any { tool -> tool in Files.readString(path) } }
                .map { it.fileName.toString() }
                .sorted()
        assertEquals(listOf("DesktopWindows.kt", "SafeWindowCloserTest.kt"), touching, "a window tool is used outside the verified closer")

        // In the closer itself there is one close request, by id, and no other
        // way of choosing a window (by title, by class, by the active one).
        val closerSource = Files.readString(repository.resolve(CLOSER_SOURCE))
        assertEquals(1, Regex("\"-c\"").findAll(closerSource).count())
        assertTrue("listOf(\"wmctrl\", \"-i\", \"-c\", window.id)" in closerSource)
        listOf("xdotool", "\"-F\"", "\"-a\"", "\"-r\"", ":ACTIVE:", "sh\", \"-c", "bash").forEach { forbidden ->
            assertFalse(forbidden in closerSource, "the closer uses $forbidden")
        }
    }

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("PLAN.md")) && Files.isDirectory(candidate.resolve("app/src"))) return candidate
            candidate = candidate.parent
        }
        error("Could not find the repository from ${Path.of("").toAbsolutePath()}")
    }

    // ------------------------------------------------------------- process tree

    @Test
    fun `a window belongs to the run when its process is the run's or one it started`() {
        val self = ProcessHandle.current().pid()
        val started =
            ProcessBuilder("cat")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        try {
            assertTrue(isInProcessTree(self, self))
            assertTrue(isInProcessTree(started.pid(), self), "a process this one started is not in its tree")
            assertFalse(isInProcessTree(self, started.pid()), "a parent was counted as its child's")
            ProcessHandle.current().parent().ifPresent { parent -> assertFalse(isInProcessTree(parent.pid(), self)) }
            assertFalse(isInProcessTree(Long.MAX_VALUE, self), "a process that does not exist was counted")
        } finally {
            started.outputStream.close()
            assertTrue(started.waitFor(20, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val APP_TITLE = "PnP Üretim Takipçisi"

        const val CLOSER_SOURCE = "app/src/desktopTest/kotlin/dev/pnptracker/platform/desktop/DesktopWindows.kt"

        /** Everything that could run a window tool: code, build scripts and shell/Python scripts. */
        val SCRIPT_EXTENSIONS = setOf("kt", "kts", "sh", "bash", "zsh", "py", "gradle", "java")

        val SKIPPED_FOLDERS = setOf(".git", "build", ".gradle", ".kotlin", ".idea")

        val WINDOW_TOOLS = listOf("wmctrl", "xdotool", "xprop", "xkill", "_NET_WM")
    }
}
