package dev.pnptracker.platform.desktop

import dev.pnptracker.platform.diagnostics.LogHome
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.system.exitProcess

/** The main window's title, exactly as `strings.xml` gives it (app_window_title). */
const val MAIN_WINDOW_TITLE: String = "PnP Üretim Takipçisi"

private const val WINDOW_WAIT_SECONDS = 180L
private const val EXIT_WAIT_SECONDS = 60L
private const val LOOK_AGAIN_MILLIS = 250L
private const val TEMPORARY_PREFIX = "pnp-desktop-smoke-"

/**
 * A real window on this desktop: `./gradlew desktopWindowSmoke`.
 *
 * Starts the application — this very build, as its own process — with
 * temporary XDG data, config and state folders, waits for its main window, and
 * closes that window through [SafeWindowCloser]: exact title, the started
 * process's own tree, checked again right before the request. Nothing else on
 * the desktop is ever sent anything. It then expects the application to exit
 * with 0, the temporary data folder to hold the database it made, the state
 * folder to be empty (an ordinary run writes no diagnostic line), and the real
 * application's files to be exactly as they were.
 *
 * Anything short of that fails the run and says why. If the window cannot be
 * told apart safely, the application is stopped as a process — it is this run's
 * own — and no window is closed.
 */
fun main() {
    val problems = mutableListOf<String>()
    val realBefore = LogHome.realApplicationLocations()
    val root = Files.createTempDirectory(TEMPORARY_PREFIX)
    println("SMOKE: temporary home created")
    try {
        runTheWindow(root, problems)
    } finally {
        if (LogHome.realApplicationLocations() != realBefore) problems += "the real application's files changed"
        deleteOwnTemporaryHome(root)
        println("SMOKE: temporary home removed: ${!Files.exists(root, LinkOption.NOFOLLOW_LINKS)}")
    }
    if (problems.isEmpty()) {
        println("SMOKE: PASSED")
        exitProcess(0)
    }
    problems.forEach { println("SMOKE: FAILED — $it") }
    exitProcess(1)
}

private fun runTheWindow(
    root: Path,
    problems: MutableList<String>,
) {
    val data = root.resolve("data")
    val config = root.resolve("config")
    val state = root.resolve("state")
    val tmp = Files.createDirectories(root.resolve("tmp"))
    val log = root.resolve("application.log")

    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val builder =
        ProcessBuilder(java, "-Djava.io.tmpdir=$tmp", "-cp", System.getProperty("java.class.path"), "dev.pnptracker.MainKt")
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
    builder.environment()["XDG_DATA_HOME"] = data.toString()
    builder.environment()["XDG_CONFIG_HOME"] = config.toString()
    builder.environment()["XDG_STATE_HOME"] = state.toString()
    val application = builder.start()
    val run = application.pid()
    println("SMOKE: application started as process $run")

    val closer = SafeWindowCloser(SystemCommands) { pid -> isInProcessTree(pid, run) }
    // Whatever the application starts is seen while it lives; once it has exited
    // its children would no longer be found under it.
    val started = mutableListOf<ProcessHandle>()
    try {
        val found = awaitWindow(application, closer)
        if (found !is WindowChoice.One) {
            problems += "no window was closed: ${SafeWindowCloser.describe(found)}"
            return
        }
        println("SMOKE: window ${found.window.id} of process ${found.window.pid}, title exactly \"$MAIN_WINDOW_TITLE\"")
        started += application.descendants().toList()

        when (val outcome = closer.close(MAIN_WINDOW_TITLE)) {
            is CloseOutcome.Refused -> {
                problems += "the window was not closed: ${outcome.why}"
                return
            }
            is CloseOutcome.Closed -> println("SMOKE: close request sent to ${outcome.window.id} only")
        }

        if (!application.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) {
            problems += "the application did not exit after its window was closed"
            return
        }
        val exit = application.exitValue()
        println("SMOKE: application exit code $exit")
        if (exit != 0) problems += "the application exited with $exit"

        val made = listed(data.resolve("pnp-tracker"))
        println("SMOKE: temporary data folder: $made")
        if ("pnp.db" !in made) problems += "the application made no database in the temporary data folder"
        if (made.any { it.endsWith("-wal") || it.endsWith("-shm") }) problems += "the database was not closed cleanly: $made"
        val stateFiles = Files.exists(state) && Files.walk(state).use { entries -> entries.anyMatch { Files.isRegularFile(it) } }
        println("SMOKE: state folder holds a file: $stateFiles")
        if (stateFiles) problems += "an ordinary run wrote into the state folder"
        val trouble = Files.readAllLines(log).filter { "Exception" in it || it.trimStart().startsWith("at ") }
        println("SMOKE: exception lines in the application's output: ${trouble.size}")
        if (trouble.isNotEmpty()) problems += "the application printed an exception"
    } finally {
        stopIfStillRunning(application, started, problems)
    }
}

/** Looks until exactly one window of the run has the title, the run ends, or the listing cannot be trusted. */
private fun awaitWindow(
    application: Process,
    closer: SafeWindowCloser,
): WindowChoice {
    val giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(WINDOW_WAIT_SECONDS)
    while (true) {
        val choice = closer.find(MAIN_WINDOW_TITLE)
        if (choice !is WindowChoice.None) return choice
        if (!application.isAlive || System.nanoTime() > giveUpAt) return choice
        // The window manager says nothing when a window appears; it is asked again.
        Thread.sleep(LOOK_AGAIN_MILLIS)
    }
}

private fun stopIfStillRunning(
    application: Process,
    seen: List<ProcessHandle>,
    problems: MutableList<String>,
) {
    val descendants = seen + application.descendants().toList()
    if (application.isAlive) {
        problems += "the application was still running and was stopped as a process"
        application.destroy()
        if (!application.waitFor(20, TimeUnit.SECONDS)) application.destroyForcibly().waitFor(20, TimeUnit.SECONDS)
    }
    descendants.filter { it.isAlive }.forEach { process ->
        problems += "a process the application started outlived it"
        process.destroyForcibly()
    }
    println("SMOKE: processes left from this run: ${descendants.count { it.isAlive } + if (application.isAlive) 1 else 0}")
}

private fun listed(folder: Path): List<String> =
    if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
        emptyList()
    } else {
        Files.list(folder).use { entries -> entries.map { it.name }.sorted().toList() }
    }

/** Deletes the one folder this run made, and refuses anything that is not plainly that. */
private fun deleteOwnTemporaryHome(root: Path) {
    val systemTemporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
    check(root.parent.toRealPath() == systemTemporary && root.name.startsWith(TEMPORARY_PREFIX)) { "not this run's folder" }
    Files.walk(root).use { entries ->
        entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
