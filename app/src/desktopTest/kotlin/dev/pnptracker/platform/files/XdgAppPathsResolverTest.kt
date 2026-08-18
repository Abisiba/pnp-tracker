package dev.pnptracker.platform.files

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests never touch the file system: [XdgAppPathsResolver] only computes paths.
 */
class XdgAppPathsResolverTest {
    private fun resolver(
        environment: Map<String, String?> = emptyMap(),
        home: String? = "/home/tester",
    ) = XdgAppPathsResolver(
        appId = "pnp-tracker",
        environment = { name -> environment[name] },
        userHome = { home },
    )

    @Test
    fun `absolute xdg variables are used as given`() {
        val paths =
            resolver(
                environment =
                    mapOf(
                        "XDG_DATA_HOME" to "/srv/data",
                        "XDG_CONFIG_HOME" to "/srv/config",
                    ),
            ).resolve()

        assertEquals(Path.of("/srv/data/pnp-tracker"), paths.dataDirectory)
        assertEquals(Path.of("/srv/data/pnp-tracker/pnp.db"), paths.databaseFile)
        assertEquals(Path.of("/srv/data/pnp-tracker/backups"), paths.backupsDirectory)
        assertEquals(Path.of("/srv/config/pnp-tracker"), paths.configDirectory)
        assertEquals(Path.of("/srv/config/pnp-tracker/settings.json"), paths.settingsFile)
    }

    @Test
    fun `missing xdg variables fall back to the home directory`() {
        val paths = resolver(environment = emptyMap(), home = "/home/tester").resolve()

        assertEquals(Path.of("/home/tester/.local/share/pnp-tracker"), paths.dataDirectory)
        assertEquals(Path.of("/home/tester/.local/share/pnp-tracker/pnp.db"), paths.databaseFile)
        assertEquals(Path.of("/home/tester/.local/share/pnp-tracker/backups"), paths.backupsDirectory)
        assertEquals(Path.of("/home/tester/.config/pnp-tracker"), paths.configDirectory)
        assertEquals(Path.of("/home/tester/.config/pnp-tracker/settings.json"), paths.settingsFile)
    }

    @Test
    fun `blank xdg variables fall back to the home directory`() {
        val paths =
            resolver(
                environment = mapOf("XDG_DATA_HOME" to "", "XDG_CONFIG_HOME" to "   "),
            ).resolve()

        assertEquals(Path.of("/home/tester/.local/share/pnp-tracker"), paths.dataDirectory)
        assertEquals(Path.of("/home/tester/.config/pnp-tracker"), paths.configDirectory)
    }

    @Test
    fun `relative xdg variables are invalid and fall back to the home directory`() {
        val paths =
            resolver(
                environment = mapOf("XDG_DATA_HOME" to "data", "XDG_CONFIG_HOME" to "../config"),
            ).resolve()

        assertEquals(Path.of("/home/tester/.local/share/pnp-tracker"), paths.dataDirectory)
        assertEquals(Path.of("/home/tester/.config/pnp-tracker"), paths.configDirectory)
    }

    @Test
    fun `tilde in an xdg variable is not expanded and falls back to the home directory`() {
        val paths =
            resolver(
                environment = mapOf("XDG_DATA_HOME" to "~/data", "XDG_CONFIG_HOME" to "~/config"),
            ).resolve()

        assertEquals(Path.of("/home/tester/.local/share/pnp-tracker"), paths.dataDirectory)
        assertEquals(Path.of("/home/tester/.config/pnp-tracker"), paths.configDirectory)
        assertFalse(paths.dataDirectory.toString().contains("~"))
        assertFalse(paths.configDirectory.toString().contains("~"))
    }

    @Test
    fun `data and config are resolved independently`() {
        val paths =
            resolver(
                environment = mapOf("XDG_DATA_HOME" to "/srv/data", "XDG_CONFIG_HOME" to "relative"),
            ).resolve()

        assertEquals(Path.of("/srv/data/pnp-tracker"), paths.dataDirectory)
        assertEquals(Path.of("/home/tester/.config/pnp-tracker"), paths.configDirectory)
    }

    @Test
    fun `resolved paths are normalised`() {
        val paths = resolver(environment = mapOf("XDG_DATA_HOME" to "/srv/./nested/../data")).resolve()

        assertEquals(Path.of("/srv/data/pnp-tracker"), paths.dataDirectory)
    }

    @Test
    fun `a missing home directory produces a clear error`() {
        val failure = assertFailsWith<IllegalStateException> { resolver(home = null).resolve() }

        assertTrue(failure.message.orEmpty().contains("user.home"))
        assertTrue(failure.message.orEmpty().contains("XDG_DATA_HOME"))
    }

    @Test
    fun `an empty home directory produces a clear error`() {
        val failure = assertFailsWith<IllegalStateException> { resolver(home = "   ").resolve() }

        assertTrue(failure.message.orEmpty().contains("empty"))
    }

    @Test
    fun `a relative home directory produces a clear error`() {
        val failure = assertFailsWith<IllegalStateException> { resolver(home = "tester").resolve() }

        assertTrue(failure.message.orEmpty().contains("absolute"))
        assertTrue(failure.message.orEmpty().contains("tester"))
    }

    @Test
    fun `an unusable home directory never falls back to the project folder or tmp`() {
        val failure = assertFailsWith<IllegalStateException> { resolver(home = null).resolve() }

        assertFalse(failure.message.orEmpty().contains("/tmp"))
    }

    @Test
    fun `the home directory is not read when both xdg variables are absolute`() {
        val environment = mapOf("XDG_DATA_HOME" to "/srv/data", "XDG_CONFIG_HOME" to "/srv/config")
        val paths =
            XdgAppPathsResolver(
                appId = "pnp-tracker",
                environment = { name -> environment[name] },
                userHome = { error("user.home must not be read when both XDG variables are absolute") },
            ).resolve()

        assertEquals(Path.of("/srv/data/pnp-tracker"), paths.dataDirectory)
        assertEquals(Path.of("/srv/config/pnp-tracker"), paths.configDirectory)
    }

    @Test
    fun `an xdg value that is not a valid path falls back to the home directory`() {
        // A NUL character is the one byte a Linux path may not contain.
        val paths = resolver(environment = mapOf("XDG_DATA_HOME" to "/srv/\u0000/data")).resolve()

        assertEquals(Path.of("/home/tester/.local/share/pnp-tracker"), paths.dataDirectory)
    }
}
