package dev.pnptracker.platform.files

import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests never touch the file system: [AppPathsResolver] only computes paths.
 *
 * Both layouts are checked on whichever system the suite runs on, because what
 * they assert is the policy — which variable is read, which folder is joined, and
 * what happens when a value is unusable — and a policy is the same calculation
 * everywhere. The one thing that is not portable is what counts as an absolute
 * path, so every example root comes from [absolute] and is absolute on the host.
 * The real Windows path shapes (`C:\Users\…`) are proved on the Windows runner
 * by the tests that actually open files, not made up here (PLAN 14.8.5).
 */
class AppPathsResolverTest {
    private fun resolver(
        environment: Map<String, String?> = emptyMap(),
        home: String? = absolute("home", "tester").toString(),
        operatingSystem: String = "Linux",
    ) = AppPathsResolver(
        appId = "pnp-tracker",
        environment = { name -> environment[name] },
        systemProperty = { name -> if (name == "os.name") operatingSystem else home },
    )

    private fun windowsResolver(
        environment: Map<String, String?> = emptyMap(),
        profile: String? = absolute("Users", "tester").toString(),
    ) = AppPathsResolver(
        appId = "pnp-tracker",
        environment = { name -> if (name == "USERPROFILE") profile else environment[name] },
        systemProperty = { name -> if (name == "os.name") "Windows 11" else error("user.home is not Windows' answer") },
    )

    @Test
    fun `absolute xdg variables are used as given`() {
        val paths =
            resolver(
                environment =
                    mapOf(
                        "XDG_DATA_HOME" to absolute("srv", "data").toString(),
                        "XDG_CONFIG_HOME" to absolute("srv", "config").toString(),
                        "XDG_STATE_HOME" to absolute("srv", "state").toString(),
                    ),
            ).resolve()

        assertEquals(absolute("srv", "data", "pnp-tracker"), paths.dataDirectory)
        assertEquals(absolute("srv", "data", "pnp-tracker", "pnp.db"), paths.databaseFile)
        assertEquals(absolute("srv", "data", "pnp-tracker", "backups"), paths.backupsDirectory)
        assertEquals(absolute("srv", "config", "pnp-tracker"), paths.configDirectory)
        assertEquals(absolute("srv", "config", "pnp-tracker", "settings.json"), paths.settingsFile)
        assertEquals(absolute("srv", "config", "pnp-tracker", "appearance.json"), paths.appearanceFile)
        assertEquals(absolute("srv", "state", "pnp-tracker"), paths.stateDirectory)
        assertEquals(absolute("srv", "state", "pnp-tracker", "logs"), paths.logsDirectory)
        assertEquals(absolute("srv", "state", "pnp-tracker", "table-sizes.json"), paths.tableSizesFile)
    }

    @Test
    fun `missing xdg variables fall back to the home directory`() {
        val paths = resolver(environment = emptyMap()).resolve()

        assertEquals(home(".local", "share", "pnp-tracker"), paths.dataDirectory)
        assertEquals(home(".local", "share", "pnp-tracker", "pnp.db"), paths.databaseFile)
        assertEquals(home(".local", "share", "pnp-tracker", "backups"), paths.backupsDirectory)
        assertEquals(home(".config", "pnp-tracker"), paths.configDirectory)
        assertEquals(home(".config", "pnp-tracker", "settings.json"), paths.settingsFile)
        assertEquals(home(".config", "pnp-tracker", "appearance.json"), paths.appearanceFile)
        assertEquals(home(".local", "state", "pnp-tracker"), paths.stateDirectory)
        assertEquals(home(".local", "state", "pnp-tracker", "logs"), paths.logsDirectory)
    }

    @Test
    fun `a blank or relative state home falls back, and state never shares a folder with data or config`() {
        listOf("", "   ", "state", "~/state").forEach { value ->
            val paths = resolver(environment = mapOf("XDG_STATE_HOME" to value)).resolve()
            assertEquals(home(".local", "state", "pnp-tracker", "logs"), paths.logsDirectory, "for `$value`")
        }
        val one = absolute("x").toString()
        val same =
            resolver(
                environment = mapOf("XDG_DATA_HOME" to one, "XDG_CONFIG_HOME" to one, "XDG_STATE_HOME" to one),
            ).resolve()
        assertTrue(same.logsDirectory != same.backupsDirectory && same.logsDirectory != same.dataDirectory)
        assertFalse(same.databaseFile.startsWith(same.logsDirectory) || same.settingsFile.startsWith(same.logsDirectory))
    }

    @Test
    fun `blank xdg variables fall back to the home directory`() {
        val paths = resolver(environment = mapOf("XDG_DATA_HOME" to "", "XDG_CONFIG_HOME" to "   ")).resolve()

        assertEquals(home(".local", "share", "pnp-tracker"), paths.dataDirectory)
        assertEquals(home(".config", "pnp-tracker"), paths.configDirectory)
    }

    @Test
    fun `relative xdg variables are invalid and fall back to the home directory`() {
        val paths = resolver(environment = mapOf("XDG_DATA_HOME" to "data", "XDG_CONFIG_HOME" to "../config")).resolve()

        assertEquals(home(".local", "share", "pnp-tracker"), paths.dataDirectory)
        assertEquals(home(".config", "pnp-tracker"), paths.configDirectory)
    }

    @Test
    fun `tilde in an xdg variable is not expanded and falls back to the home directory`() {
        val paths = resolver(environment = mapOf("XDG_DATA_HOME" to "~/data", "XDG_CONFIG_HOME" to "~/config")).resolve()

        assertEquals(home(".local", "share", "pnp-tracker"), paths.dataDirectory)
        assertEquals(home(".config", "pnp-tracker"), paths.configDirectory)
        assertFalse(paths.dataDirectory.toString().contains("~"))
        assertFalse(paths.configDirectory.toString().contains("~"))
    }

    @Test
    fun `data and config are resolved independently`() {
        val paths =
            resolver(
                environment = mapOf("XDG_DATA_HOME" to absolute("srv", "data").toString(), "XDG_CONFIG_HOME" to "relative"),
            ).resolve()

        assertEquals(absolute("srv", "data", "pnp-tracker"), paths.dataDirectory)
        assertEquals(home(".config", "pnp-tracker"), paths.configDirectory)
    }

    @Test
    fun `resolved paths are normalised`() {
        val winding = absolute("srv", ".", "nested", "..", "data").toString()
        val paths = resolver(environment = mapOf("XDG_DATA_HOME" to winding)).resolve()

        assertEquals(absolute("srv", "data", "pnp-tracker"), paths.dataDirectory)
    }

    @Test
    fun `a missing home directory is refused before anything is touched`() {
        val refused = assertFailsWith<StartupRefused> { resolver(home = null).resolve() }

        assertEquals(StartupProblem.FOLDERS_NOT_FOUND, refused.problem)
    }

    @Test
    fun `an empty or relative home directory is refused too`() {
        listOf("   ", "tester").forEach { value ->
            val refused = assertFailsWith<StartupRefused> { resolver(home = value).resolve() }
            assertEquals(StartupProblem.FOLDERS_NOT_FOUND, refused.problem, "for `$value`")
        }
    }

    @Test
    fun `an unusable home directory never falls back to the project folder or tmp`() {
        // The refusal carries a cause for a developer in a debugger and nothing
        // else: PLAN 14.7.1 keeps a path and an environment value out of the log,
        // and the window is made from the problem alone.
        val refused = assertFailsWith<StartupRefused> { resolver(home = null).resolve() }

        assertEquals(StartupProblem.FOLDERS_NOT_FOUND, refused.problem)
        assertFalse(
            refused.cause
                ?.message
                .orEmpty()
                .contains("tmp"),
        )
    }

    @Test
    fun `the home directory is not read when every xdg variable is absolute`() {
        // The state home joined data and config when the diagnostic log arrived
        // (PLAN 14.7.1); a missing one is a fallback like the other two.
        val environment =
            mapOf(
                "XDG_DATA_HOME" to absolute("srv", "data").toString(),
                "XDG_CONFIG_HOME" to absolute("srv", "config").toString(),
                "XDG_STATE_HOME" to absolute("srv", "state").toString(),
            )
        val paths =
            AppPathsResolver(
                appId = "pnp-tracker",
                environment = { name -> environment[name] },
                systemProperty = { name ->
                    if (name == "os.name") "Linux" else error("user.home must not be read when every XDG variable is absolute")
                },
            ).resolve()

        assertEquals(absolute("srv", "data", "pnp-tracker"), paths.dataDirectory)
        assertEquals(absolute("srv", "config", "pnp-tracker"), paths.configDirectory)
        assertEquals(absolute("srv", "state", "pnp-tracker", "logs"), paths.logsDirectory)
    }

    @Test
    fun `an xdg value that is not a valid path falls back to the home directory`() {
        // A NUL character is the one byte no path on either system may contain.
        val paths = resolver(environment = mapOf("XDG_DATA_HOME" to "/srv/\u0000/data")).resolve()

        assertEquals(home(".local", "share", "pnp-tracker"), paths.dataDirectory)
    }

    // ------------------------------------------------------------- Windows (PLAN 14.8.1)

    @Test
    fun `windows puts data and state side by side under the local folder`() {
        val local = absolute("Users", "tester", "AppData", "Local").toString()
        val roaming = absolute("Users", "tester", "AppData", "Roaming").toString()
        val paths = windowsResolver(environment = mapOf("LOCALAPPDATA" to local, "APPDATA" to roaming)).resolve()

        assertEquals(absolute("Users", "tester", "AppData", "Local", "pnp-tracker", "data"), paths.dataDirectory)
        assertEquals(absolute("Users", "tester", "AppData", "Local", "pnp-tracker", "data", "pnp.db"), paths.databaseFile)
        assertEquals(absolute("Users", "tester", "AppData", "Local", "pnp-tracker", "data", "backups"), paths.backupsDirectory)
        assertEquals(absolute("Users", "tester", "AppData", "Local", "pnp-tracker", "state"), paths.stateDirectory)
        assertEquals(absolute("Users", "tester", "AppData", "Local", "pnp-tracker", "state", "logs"), paths.logsDirectory)
        assertEquals(
            absolute("Users", "tester", "AppData", "Local", "pnp-tracker", "state", "table-sizes.json"),
            paths.tableSizesFile,
        )
        assertEquals(absolute("Users", "tester", "AppData", "Roaming", "pnp-tracker"), paths.configDirectory)
        assertEquals(absolute("Users", "tester", "AppData", "Roaming", "pnp-tracker", "settings.json"), paths.settingsFile)
        assertEquals(absolute("Users", "tester", "AppData", "Roaming", "pnp-tracker", "appearance.json"), paths.appearanceFile)
    }

    @Test
    fun `on windows the state folder is a sibling of the data folder and never inside it`() {
        // PLAN 14.7.1 has the diagnostic log touch nothing under the data and
        // configuration areas; a state folder inside the data folder would make
        // that rule unreadable, so the two are siblings (PLAN 14.8.1).
        val local = absolute("Users", "tester", "AppData", "Local").toString()
        val paths = windowsResolver(environment = mapOf("LOCALAPPDATA" to local)).resolve()

        assertFalse(paths.stateDirectory.startsWith(paths.dataDirectory))
        assertFalse(paths.dataDirectory.startsWith(paths.stateDirectory))
        assertFalse(paths.logsDirectory.startsWith(paths.dataDirectory))
        assertFalse(paths.settingsFile.startsWith(paths.dataDirectory))
        assertEquals(paths.stateDirectory.parent, paths.dataDirectory.parent)
    }

    @Test
    fun `windows does not read the xdg variables`() {
        val local = absolute("Users", "tester", "AppData", "Local").toString()
        val paths =
            windowsResolver(
                environment =
                    mapOf(
                        "LOCALAPPDATA" to local,
                        "XDG_DATA_HOME" to absolute("srv", "data").toString(),
                        "XDG_STATE_HOME" to absolute("srv", "state").toString(),
                        "XDG_CONFIG_HOME" to absolute("srv", "config").toString(),
                    ),
            ).resolve()

        listOf(paths.dataDirectory, paths.stateDirectory, paths.configDirectory).forEach { directory ->
            assertFalse(directory.toString().contains("srv"), "$directory read an XDG variable")
        }
    }

    @Test
    fun `missing windows variables fall back to the folders windows itself would name`() {
        listOf(emptyMap(), mapOf("LOCALAPPDATA" to "", "APPDATA" to "   "), mapOf("LOCALAPPDATA" to "Local")).forEach { environment ->
            val paths = windowsResolver(environment = environment).resolve()

            assertEquals(absolute("Users", "tester", "AppData", "Local", "pnp-tracker", "data"), paths.dataDirectory, "$environment")
            assertEquals(absolute("Users", "tester", "AppData", "Roaming", "pnp-tracker"), paths.configDirectory, "$environment")
        }
    }

    @Test
    fun `an unusable user profile is refused before anything is touched`() {
        listOf(null, "   ", "tester").forEach { profile ->
            val refused = assertFailsWith<StartupRefused> { windowsResolver(profile = profile).resolve() }
            assertEquals(StartupProblem.FOLDERS_NOT_FOUND, refused.problem, "for `$profile`")
        }
    }

    @Test
    fun `the user profile is not read when both windows variables are absolute`() {
        val environment =
            mapOf(
                "LOCALAPPDATA" to absolute("Users", "tester", "AppData", "Local").toString(),
                "APPDATA" to absolute("Users", "tester", "AppData", "Roaming").toString(),
            )
        val paths =
            AppPathsResolver(
                appId = "pnp-tracker",
                environment = { name ->
                    if (name ==
                        "USERPROFILE"
                    ) {
                        error("%USERPROFILE% must not be read when both variables are absolute")
                    } else {
                        environment[name]
                    }
                },
                systemProperty = { name -> if (name == "os.name") "Windows 11" else null },
            ).resolve()

        assertEquals(absolute("Users", "tester", "AppData", "Local", "pnp-tracker", "data"), paths.dataDirectory)
    }

    @Test
    fun `the layout is chosen by the system name and nothing else`() {
        val environment =
            mapOf(
                "LOCALAPPDATA" to absolute("local").toString(),
                "APPDATA" to absolute("roaming").toString(),
                "XDG_DATA_HOME" to absolute("xdg").toString(),
                "USERPROFILE" to absolute("profile").toString(),
            )

        fun pathsOn(operatingSystem: String) =
            AppPathsResolver(
                appId = "pnp-tracker",
                environment = { name -> environment[name] },
                systemProperty = { name -> if (name == "os.name") operatingSystem else absolute("home", "tester").toString() },
            ).resolve()

        listOf("Windows 11", "Windows Server 2025", "WINDOWS 10").forEach { name ->
            assertEquals(absolute("local", "pnp-tracker", "data"), pathsOn(name).dataDirectory, name)
        }
        listOf("Linux", "FreeBSD", "").forEach { name ->
            assertEquals(absolute("xdg", "pnp-tracker"), pathsOn(name).dataDirectory, name)
        }
    }

    @Test
    fun `both layouts name the same files`() {
        // A backup, a setting and a layout keep their names across systems, so a
        // person reading either folder finds what they expect (PLAN 14.8.1).
        val linux = resolver(environment = mapOf("XDG_DATA_HOME" to absolute("d").toString())).resolve()
        val windows = windowsResolver(environment = mapOf("LOCALAPPDATA" to absolute("l").toString())).resolve()

        listOf(
            linux.databaseFile to windows.databaseFile,
            linux.backupsDirectory to windows.backupsDirectory,
            linux.settingsFile to windows.settingsFile,
            linux.appearanceFile to windows.appearanceFile,
            linux.logsDirectory to windows.logsDirectory,
            linux.tableSizesFile to windows.tableSizesFile,
        ).forEach { (onLinux, onWindows) ->
            assertEquals(onLinux.fileName, onWindows.fileName)
        }
    }

    private companion object {
        /** An absolute path on whichever system this runs on, written once. */
        fun absolute(vararg names: String): Path = names.fold(Path.of("").toAbsolutePath().root!!) { at, name -> at.resolve(name) }

        fun home(vararg names: String): Path = absolute("home", "tester", *names)
    }
}
