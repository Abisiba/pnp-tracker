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

    private fun pathsBelowTemporaryRoot(): XdgAppPaths =
        XdgAppPathsResolver(
            appId = "pnp-tracker",
            environment =
                mapOf(
                    "XDG_DATA_HOME" to temporaryRoot.resolve("data").toString(),
                    "XDG_CONFIG_HOME" to temporaryRoot.resolve("config").toString(),
                )::get,
            userHome = { error("the home directory must not be needed in this test") },
        ).resolve()

    @Test
    fun `ensureDirectories creates the data backups and config directories`() {
        val paths = pathsBelowTemporaryRoot()

        initializer.ensureDirectories(paths)

        assertTrue(Files.isDirectory(paths.dataDirectory), "data directory missing")
        assertTrue(Files.isDirectory(paths.backupsDirectory), "backups directory missing")
        assertTrue(Files.isDirectory(paths.configDirectory), "config directory missing")
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
}
