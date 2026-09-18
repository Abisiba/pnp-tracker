package dev.pnptracker.platform.diagnostics

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticLevel
import dev.pnptracker.domain.diagnostics.recordSafely
import dev.pnptracker.platform.files.AppDirectoryInitializer
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.files.XdgAppPathsResolver
import dev.pnptracker.platform.startup.deleteTemporaryTree
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The last of the five escapes Dilim 2 measured: the folders this application
 * keeps its data in, when they cannot be made (PLAN 14.7.6).
 *
 * It used to raise `java.io.IOException` out of `main` before there was a
 * window, a log or anything else — and the message it carried named an absolute
 * path under the user's home. Now it is a refusal like any other: the same typed
 * problem the gate produces, the same window, the same one record, and the path
 * left where it was found.
 *
 * Nothing here trusts what this machine lets root do. A folder is made
 * read-only, and if the file system will not honour that the test says so
 * instead of pretending to have proved something.
 */
class StartupFolderRefusalTest {
    private lateinit var home: Path
    private val diagnostics = RecordingDiagnostics()
    private var realDatabaseExisted = false
    private val unwritable = mutableListOf<Path>()

    @BeforeTest
    fun createHome() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = Files.createTempDirectory("pnp-tracker-folder-refusal")
    }

    @AfterTest
    fun deleteHome() {
        unwritable.forEach { runCatching { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) } }
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the test changed whether the real application database exists",
        )
        deleteTemporaryTree(home)
    }

    private fun pathsUnder(root: Path): XdgAppPaths =
        XdgAppPathsResolver(
            environment = { name ->
                when (name) {
                    "XDG_DATA_HOME" -> root.resolve("data").toString()
                    "XDG_CONFIG_HOME" -> root.resolve("config").toString()
                    "XDG_STATE_HOME" -> root.resolve("state").toString()
                    else -> null
                }
            },
        ).resolve()

    /** A folder nothing may be created in, or null when this machine will not have one. */
    private fun aFolderNothingCanBeWrittenIn(name: String): Path? {
        val locked = Files.createDirectory(home.resolve(name))
        if (!locked.fileSystem.supportedFileAttributeViews().contains("posix")) return null
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"))
        unwritable.add(locked)
        // Root ignores the mode, and a test that quietly passes there proves nothing.
        return runCatching { Files.createDirectory(locked.resolve("deneme")) }.fold({ null }, { locked })
    }

    @Test
    fun `a folder the application cannot make becomes the typed refusal the window is made of`() {
        val locked = aFolderNothingCanBeWrittenIn("kilitli") ?: return
        val paths = pathsUnder(locked)

        val refused = assertFailsWith<StartupRefused> { AppDirectoryInitializer().ensureDirectories(paths) }

        assertEquals(StartupProblem.FOLDERS_NOT_CREATED, refused.problem)
        // Nothing of the user's was reached, because there was nowhere to reach it.
        assertFalse(Files.exists(paths.databaseFile), "a database was made in a folder that could not be made")
        assertFalse(Files.exists(paths.settingsFile))
        assertFalse(Files.exists(paths.backupsDirectory))
    }

    @Test
    fun `no sentence this application writes names the folder it failed on`() {
        val locked = aFolderNothingCanBeWrittenIn("gizli") ?: return

        val refused = assertFailsWith<StartupRefused> { AppDirectoryInitializer().ensureDirectories(pathsUnder(locked)) }

        // Every message this application wrote. The refusal's own used to read
        // "Cannot create the data directory at /home/…/pnp-tracker", and that
        // sentence is where the user's account name travelled out of here.
        val ours = listOfNotNull(refused.message, refused.cause?.message)
        assertEquals(2, ours.size, "the refusal stopped saying what happened")
        assertFalse(
            ours.any { locked.toString() in it || home.toString() in it || System.getProperty("user.name") in it },
            "a sentence of ours still carries a path: $ours",
        )

        // The file system's own exception is kept underneath, unread, because its
        // class is the useful half — "no permission" and "no space" are different
        // things to do about. Its message does carry the path, and that is the
        // whole reason nothing ever reads one: the record made of this refusal is
        // class names and nothing else (the test below), and the window is made
        // of the problem alone (StartupErrorScreenTest).
        val theirs = generateSequence(refused.cause?.cause) { it.cause }.toList()
        assertTrue(theirs.isNotEmpty(), "the cause worth recording was thrown away")
        assertEquals("java.nio.file.AccessDeniedException", theirs.last()::class.qualifiedName)
    }

    @Test
    fun `the record made of it is one safe line`() {
        val locked = aFolderNothingCanBeWrittenIn("kayit") ?: return
        val refused = assertFailsWith<StartupRefused> { AppDirectoryInitializer().ensureDirectories(pathsUnder(locked)) }

        // Exactly what Main does with a refusal, whichever half of the start
        // produced it.
        diagnostics.recordSafely { startupRefusalRecord(refused) }

        assertRecordedAsPlanned(
            ExpectedRecord(
                DiagnosticEvent.STARTUP_REFUSED,
                level = DiagnosticLevel.ERROR,
                reason = StartupProblem.FOLDERS_NOT_CREATED,
                exception = "java.io.IOException",
                cause = "java.nio.file.AccessDeniedException",
            ),
            diagnostics.only(),
        )
        assertLinesCarryNothingOfTheUsers(diagnostics, home.toString(), locked.toString(), System.getProperty("user.name"))
    }

    @Test
    fun `a state folder that cannot be made either is not a second failure`() {
        val locked = aFolderNothingCanBeWrittenIn("her-sey-kilitli") ?: return
        val paths = pathsUnder(locked)
        // The writer is built exactly as Main builds it, over a state folder that
        // is as unmakeable as the rest. Building it must touch nothing.
        val log = QueuedDiagnostics.inDirectory(paths.logsDirectory, AppInfo.Current)

        val refused = assertFailsWith<StartupRefused> { AppDirectoryInitializer().ensureDirectories(paths) }
        log.recordSafely { startupRefusalRecord(refused) }
        log.close()

        // No second failure reached the caller, and nothing was made anywhere.
        assertFalse(Files.exists(paths.stateDirectory), "a state folder appeared where none could be made")
        assertFalse(Files.exists(paths.logsDirectory))
        assertEquals(StartupProblem.FOLDERS_NOT_CREATED, refused.problem)
    }

    @Test
    fun `folders that can be made are still made, and record nothing`() {
        val paths = pathsUnder(home)

        AppDirectoryInitializer().ensureDirectories(paths)

        assertTrue(Files.isDirectory(paths.dataDirectory))
        assertTrue(Files.isDirectory(paths.backupsDirectory))
        assertTrue(Files.isDirectory(paths.configDirectory))
        // The ordinary start of an ordinary day writes nothing (PLAN 14.7.2).
        assertEquals(emptyList(), diagnostics.records.map { it.event.code })
        // And the state folder is still made only by a line being written.
        assertFalse(Files.exists(paths.stateDirectory))
    }
}
