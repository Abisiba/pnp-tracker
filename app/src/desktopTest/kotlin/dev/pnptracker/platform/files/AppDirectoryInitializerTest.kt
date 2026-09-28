package dev.pnptracker.platform.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests touch the real file system, but only inside a temporary directory
 * created per test. Nothing is written below the real user home.
 */
class AppDirectoryInitializerTest {
    private lateinit var temporaryRoot: Path

    private val initializer = AppDirectoryInitializer()

    @BeforeTest
    fun createTemporaryRoot() {
        temporaryRoot = Files.createTempDirectory("pnp-tracker-test")
    }

    @AfterTest
    fun removeTemporaryRoot() {
        val systemTemporaryDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val root = temporaryRoot.toAbsolutePath().normalize()
        // Refuse to delete anything that is not the temporary directory this test made.
        check(root.startsWith(systemTemporaryDirectory) && root != systemTemporaryDirectory) {
            "Refusing to delete $root: it is outside $systemTemporaryDirectory"
        }
        Files.walk(root).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private fun pathsBelowTemporaryRoot(): AppPaths =
        AppPathsResolver(
            appId = "pnp-tracker",
            environment =
                mapOf(
                    "XDG_DATA_HOME" to temporaryRoot.resolve("data").toString(),
                    "XDG_CONFIG_HOME" to temporaryRoot.resolve("config").toString(),
                    "XDG_STATE_HOME" to temporaryRoot.resolve("state").toString(),
                )::get,
            // The layout is named rather than taken from the host, so this test
            // asks about the initializer and about nothing else — and so it can
            // never resolve to a real user's folders (PLAN 14.8.5).
            systemProperty = { name ->
                if (name == "os.name") "Linux" else error("the home directory must not be needed in this test")
            },
        ).resolve()

    /** The same three areas as PLAN 14.8.1 puts them, still under the temporary root. */
    private fun windowsPathsBelowTemporaryRoot(): AppPaths =
        AppPathsResolver(
            appId = "pnp-tracker",
            environment =
                mapOf(
                    "LOCALAPPDATA" to temporaryRoot.resolve("Local").toString(),
                    "APPDATA" to temporaryRoot.resolve("Roaming").toString(),
                )::get,
            systemProperty = { name ->
                if (name == "os.name") "Windows 11" else error("the home directory is not Windows' answer")
            },
        ).resolve()

    @Test
    fun `ensureDirectories creates the data backups and config directories`() {
        val paths = pathsBelowTemporaryRoot()

        initializer.ensureDirectories(paths)

        assertTrue(Files.isDirectory(paths.dataDirectory), "data directory missing")
        assertTrue(Files.isDirectory(paths.backupsDirectory), "backups directory missing")
        assertTrue(Files.isDirectory(paths.configDirectory), "config directory missing")
        // The state folder is the diagnostic log's own and is made by its first
        // line, never at startup (PLAN 14.7.1).
        assertTrue(!Files.exists(paths.stateDirectory), "the state directory was made at startup")
    }

    @Test
    fun `ensureDirectories is safe to call twice`() {
        val paths = pathsBelowTemporaryRoot()

        initializer.ensureDirectories(paths)
        initializer.ensureDirectories(paths)

        assertTrue(Files.isDirectory(paths.dataDirectory))
        assertTrue(Files.isDirectory(paths.backupsDirectory))
        assertTrue(Files.isDirectory(paths.configDirectory))
    }

    @Test
    fun `ensureDirectories does not create the database or the settings file`() {
        val paths = pathsBelowTemporaryRoot()

        initializer.ensureDirectories(paths)

        assertFalse(Files.exists(paths.databaseFile), "pnp.db must not be created here")
        // It has an owner now — DesktopSettingsStore — and that one creates
        // it only when somebody saves a retention number (PLAN 14.4.12).
        assertFalse(Files.exists(paths.settingsFile), "settings.json must not be created here")
    }

    @Test
    fun `ensureDirectories writes nothing outside the temporary root`() {
        val realUserHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val realDataDirectory = realUserHome.resolve(".local/share/pnp-tracker")
        val realConfigDirectory = realUserHome.resolve(".config/pnp-tracker")
        val dataDirectoryExistedBefore = Files.exists(realDataDirectory)
        val configDirectoryExistedBefore = Files.exists(realConfigDirectory)
        val paths = pathsBelowTemporaryRoot()

        initializer.ensureDirectories(paths)

        assertFalse(
            temporaryRoot.toAbsolutePath().normalize().startsWith(realUserHome),
            "the temporary root must not live below the real user home",
        )
        Files.walk(temporaryRoot).use { entries ->
            entries.forEach { entry ->
                assertTrue(entry.startsWith(temporaryRoot), "unexpected entry outside the temporary root: $entry")
            }
        }
        assertEquals(dataDirectoryExistedBefore, Files.exists(realDataDirectory))
        assertEquals(configDirectoryExistedBefore, Files.exists(realConfigDirectory))
    }

    @Test
    fun `new application directories are owner only`() {
        val paths = pathsBelowTemporaryRoot()
        if (!supportsPosixPermissions()) return

        initializer.ensureDirectories(paths)

        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(paths.dataDirectory))
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(paths.backupsDirectory))
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(paths.configDirectory))
    }

    @Test
    fun `permissions of an existing directory are left alone`() {
        val paths = pathsBelowTemporaryRoot()
        if (!supportsPosixPermissions()) return
        val groupReadable = PosixFilePermissions.fromString("rwxr-xr-x")
        Files.createDirectories(paths.dataDirectory.parent)
        Files.createDirectory(paths.dataDirectory, PosixFilePermissions.asFileAttribute(groupReadable))

        initializer.ensureDirectories(paths)

        assertEquals(groupReadable, Files.getPosixFilePermissions(paths.dataDirectory))
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(paths.backupsDirectory))
    }

    private fun supportsPosixPermissions(): Boolean = temporaryRoot.fileSystem.supportedFileAttributeViews().contains("posix")

    private companion object {
        val OWNER_ONLY: Set<PosixFilePermission> =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
    }

    @Test
    fun `the windows layout gets the same three directories and no others`() {
        val paths = windowsPathsBelowTemporaryRoot()

        initializer.ensureDirectories(paths)

        assertTrue(Files.isDirectory(paths.dataDirectory), "data")
        assertTrue(Files.isDirectory(paths.backupsDirectory), "backups")
        assertTrue(Files.isDirectory(paths.configDirectory), "config")
        // State is made by whoever first writes into it, on either layout
        // (PLAN 14.7.1): a run with no failures leaves it absent.
        assertFalse(Files.exists(paths.stateDirectory), "state must not be made here")
        assertFalse(Files.exists(paths.databaseFile), "pnp.db belongs to the database")
        assertFalse(Files.exists(paths.settingsFile), "settings.json belongs to the settings")
    }
}
