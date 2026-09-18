package dev.pnptracker.platform.desktop

import java.util.concurrent.TimeUnit

/** One top-level window as the window manager lists it. */
data class DesktopWindow(
    val id: String,
    val pid: Long,
    val title: String,
)

/** What one listing says about the window a smoke run is looking for. */
sealed interface WindowChoice {
    /** Exactly one window has the exact title and belongs to the run. */
    data class One(
        val window: DesktopWindow,
    ) : WindowChoice

    /** No such window (yet). [sameTitle] are windows with the exact title that are not the run's. */
    data class None(
        val sameTitle: List<DesktopWindow>,
        val listed: Int,
    ) : WindowChoice

    /** More than one of the run's windows carries the title; none of them is chosen. */
    data class Several(
        val windows: List<DesktopWindow>,
    ) : WindowChoice

    /** The listing itself cannot be trusted, so nothing in it is acted on. */
    data class Untrustworthy(
        val why: String,
    ) : WindowChoice
}

/** What a close attempt did. Only [Closed] means a request was sent, and only to [window]. */
sealed interface CloseOutcome {
    data class Closed(
        val window: DesktopWindow,
    ) : CloseOutcome

    data class Refused(
        val why: String,
    ) : CloseOutcome
}

/** Runs a program with its arguments as they are — never through a shell. */
fun interface Commands {
    /** The program's exit code and standard output. */
    fun run(argv: List<String>): CommandResult
}

data class CommandResult(
    val exitCode: Int,
    val output: String,
)

/**
 * The real programs, started directly: an argument is one argument whatever
 * characters it holds, and nothing is ever handed to a shell to split.
 */
object SystemCommands : Commands {
    override fun run(argv: List<String>): CommandResult {
        val process =
            ProcessBuilder(argv)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!process.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return CommandResult(-1, output)
        }
        return CommandResult(process.exitValue(), output)
    }

    private const val COMMAND_SECONDS = 10L
}

/** A window id as `wmctrl` prints it; nothing else is ever put on a command line. */
private val WINDOW_ID = Regex("0x[0-9a-fA-F]{1,8}")

/** `<id> <desktop> <pid> <host> <title>` — the title is everything after the host and one space. */
private val LISTED_WINDOW = Regex("^(0x[0-9a-fA-F]{1,8})\\s+(-?\\d+)\\s+(\\d+)\\s+(\\S+) (.*)$")

private val NET_WM_PID = Regex("^_NET_WM_PID\\(CARDINAL\\) = (\\d+)$")

/**
 * Reads `wmctrl -l -p`.
 *
 * Returns null when any line does not read as one window. A title may hold a
 * line break, and the line after it would then be words of that title
 * pretending to be a window of their own; a listing like that is not read at
 * all rather than read cleverly.
 */
fun windowsIn(listing: String): List<DesktopWindow>? {
    val windows = mutableListOf<DesktopWindow>()
    for (line in listing.lines()) {
        if (line.isEmpty()) continue
        val match = LISTED_WINDOW.matchEntire(line) ?: return null
        val pid = match.groupValues[3].toLongOrNull() ?: return null
        windows += DesktopWindow(match.groupValues[1].lowercase(), pid, match.groupValues[5])
    }
    return windows
}

/**
 * Picks the one window titled exactly [title] that [belongsToRun] says is the
 * run's own.
 *
 * Part of a title is never enough — another program's window may say more than
 * the one being looked for — and the right title is never enough either: the
 * user may have a copy of the same application open. Both must hold, for exactly
 * one window, in a listing where every window id appears once.
 */
fun chooseWindow(
    listing: String,
    title: String,
    belongsToRun: (Long) -> Boolean,
): WindowChoice {
    val windows = windowsIn(listing) ?: return WindowChoice.Untrustworthy("a line of the window list did not read as one window")
    val repeated = windows.groupBy { it.id }.filterValues { it.size > 1 }.keys
    if (repeated.isNotEmpty()) return WindowChoice.Untrustworthy("window ids listed more than once: ${repeated.sorted()}")

    val sameTitle = windows.filter { it.title == title }
    val ours = sameTitle.filter { it.pid > 0 && belongsToRun(it.pid) }
    return when (ours.size) {
        0 -> WindowChoice.None(sameTitle, windows.size)
        1 -> WindowChoice.One(ours.single())
        else -> WindowChoice.Several(ours)
    }
}

/** True when [pid] is [root] or a process started, directly or not, by it. */
fun isInProcessTree(
    pid: Long,
    root: Long,
): Boolean {
    var current = ProcessHandle.of(pid).orElse(null) ?: return false
    repeat(MAX_TREE_DEPTH) {
        if (current.pid() == root) return true
        current = current.parent().orElse(null) ?: return false
    }
    return false
}

private const val MAX_TREE_DEPTH = 64

/**
 * Closes the one window a smoke run opened, and nothing else.
 *
 * A window is chosen from a listing by its exact title and by belonging to the
 * run's process tree; immediately before the close request it is chosen again
 * from a fresh listing and its process id is read once more by its id. Only an
 * id that passed all of that is ever put on a command line, as an argument of
 * its own. Anything less — no window, two windows, a listing that cannot be
 * read, an id that changed hands — is a refusal, and no request goes anywhere.
 */
class SafeWindowCloser(
    private val commands: Commands,
    private val belongsToRun: (Long) -> Boolean,
) {
    /** What the window manager lists right now. */
    fun find(title: String): WindowChoice {
        val listed = commands.run(listOf("wmctrl", "-l", "-p"))
        if (listed.exitCode != 0) return WindowChoice.Untrustworthy("the window list could not be read (exit ${listed.exitCode})")
        return chooseWindow(listed.output, title, belongsToRun)
    }

    fun close(title: String): CloseOutcome {
        val first = find(title)
        val chosen = first as? WindowChoice.One ?: return CloseOutcome.Refused(describe(first))
        val window = chosen.window

        // Immediately before: the same window, still alone, still ours, and the
        // window itself still says it belongs to that process.
        val again = find(title)
        if (again != chosen) return CloseOutcome.Refused("the window changed before it could be closed: ${describe(again)}")
        if (!WINDOW_ID.matches(window.id)) return CloseOutcome.Refused("not a window id: ${window.id.length} characters")
        val owner = pidOf(window.id)
        if (owner != window.pid || !belongsToRun(window.pid)) {
            return CloseOutcome.Refused("window ${window.id} says it belongs to process $owner, not ${window.pid}")
        }

        val sent = commands.run(listOf("wmctrl", "-i", "-c", window.id))
        if (sent.exitCode != 0) return CloseOutcome.Refused("the close request for ${window.id} failed (exit ${sent.exitCode})")
        return CloseOutcome.Closed(window)
    }

    private fun pidOf(id: String): Long? {
        val read = commands.run(listOf("xprop", "-id", id, "_NET_WM_PID"))
        if (read.exitCode != 0) return null
        val lines = read.output.lines().filter { it.isNotBlank() }
        return lines
            .singleOrNull()
            ?.let { NET_WM_PID.matchEntire(it) }
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
    }

    companion object {
        /**
         * Says why nothing was closed, without repeating any other program's
         * window title: only ids, process ids and counts.
         */
        fun describe(choice: WindowChoice): String =
            when (choice) {
                is WindowChoice.One -> "one window: ${choice.window.id} of process ${choice.window.pid}"
                is WindowChoice.None ->
                    "no window of this run has the exact title (${choice.listed} windows listed; " +
                        "same title elsewhere: ${choice.sameTitle.map { "${it.id} of process ${it.pid}" }})"
                is WindowChoice.Several -> "${choice.windows.size} windows of this run have the title: ${choice.windows.map { it.id }}"
                is WindowChoice.Untrustworthy -> choice.why
            }
    }
}
