package dev.pnptracker.platform.files

import dev.pnptracker.AppInfo
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * Computes where this application keeps its data, its configuration and its
 * state (the diagnostic log and the table's sizes).
 *
 * There are two layouts and this is the only place that chooses between them:
 * the XDG base directories on Linux (PLAN 14.2) and the user's own folders on
 * Windows (PLAN 14.8.1). Nothing above this class asks which system it is on —
 * everything else is handed the [AppPaths] that come out of here.
 *
 * Resolving is a pure calculation: nothing is read from or written to disk.
 */
class AppPathsResolver(
    private val appId: String = AppInfo.Current.id,
    private val environment: (String) -> String? = System::getenv,
    private val systemProperty: (String) -> String? = System::getProperty,
) {
    /**
     * @throws StartupRefused with [StartupProblem.FOLDERS_NOT_FOUND] when the
     *   system says nothing usable about where the user's own folders are. The
     *   cause carries the value that was unusable, so it stays here: PLAN 14.7.1
     *   keeps an environment value out of the log, and the window is made from
     *   the problem alone.
     */
    fun resolve(): AppPaths = if (namesWindows(systemProperty(OPERATING_SYSTEM))) windowsPaths() else xdgPaths()

    /** PLAN 14.2: three base directories, each with this application's own folder inside it. */
    private fun xdgPaths(): AppPaths =
        pathsUnder(
            dataDirectory = baseDirectory(DATA_HOME_VARIABLE, DATA_HOME_FALLBACK).resolve(appId),
            configDirectory = baseDirectory(CONFIG_HOME_VARIABLE, CONFIG_HOME_FALLBACK).resolve(appId),
            stateDirectory = baseDirectory(STATE_HOME_VARIABLE, STATE_HOME_FALLBACK).resolve(appId),
        )

    /**
     * PLAN 14.8.1: data and state are siblings under `%LOCALAPPDATA%`, the
     * setting is under `%APPDATA%`.
     *
     * Siblings rather than one inside the other, so PLAN 14.7.1's rule — the
     * diagnostic log touches nothing under the data and configuration areas —
     * stays a rule somebody can read. Both live under the local folder because
     * a database and a layout belong to this machine and should not travel.
     */
    private fun windowsPaths(): AppPaths {
        val applicationLocal = windowsBase(LOCAL_APP_DATA_VARIABLE, LOCAL_APP_DATA_FALLBACK).resolve(appId)
        return pathsUnder(
            dataDirectory = applicationLocal.resolve(WINDOWS_DATA_DIRECTORY_NAME),
            configDirectory = windowsBase(ROAMING_APP_DATA_VARIABLE, ROAMING_APP_DATA_FALLBACK).resolve(appId),
            stateDirectory = applicationLocal.resolve(WINDOWS_STATE_DIRECTORY_NAME),
        )
    }

    /**
     * The files, which are the same names on both systems.
     *
     * A backup written on one machine names the same file on the other, and a
     * person reading either layout finds `pnp.db` where they expect it.
     */
    private fun pathsUnder(
        dataDirectory: Path,
        configDirectory: Path,
        stateDirectory: Path,
    ): AppPaths =
        AppPaths(
            dataDirectory = dataDirectory,
            databaseFile = dataDirectory.resolve(DATABASE_FILE_NAME),
            backupsDirectory = dataDirectory.resolve(BACKUPS_DIRECTORY_NAME),
            configDirectory = configDirectory,
            settingsFile = configDirectory.resolve(SETTINGS_FILE_NAME),
            appearanceFile = configDirectory.resolve(APPEARANCE_FILE_NAME),
            stateDirectory = stateDirectory,
            logsDirectory = stateDirectory.resolve(LOGS_DIRECTORY_NAME),
            tableSizesFile = stateDirectory.resolve(TABLE_SIZES_FILE_NAME),
            gameOrderFile = stateDirectory.resolve(GAME_ORDER_FILE_NAME),
        )

    /**
     * The XDG specification requires base directory variables to hold an absolute
     * path and gives no meaning to `~`, so anything else falls back to the home
     * directory. The home directory is only read when a fallback is actually needed.
     */
    private fun baseDirectory(
        variableName: String,
        fallbackBelowHome: String,
    ): Path {
        val configured = environment(variableName)?.takeIf { it.isNotBlank() }?.toPathOrNull()
        return if (configured != null && configured.isAbsolute) {
            configured.normalize()
        } else {
            homeDirectory(USER_HOME_PROPERTY, systemProperty, variableName).resolve(fallbackBelowHome).normalize()
        }
    }

    /**
     * The same shape for Windows, with `%USERPROFILE%` as the fallback root.
     *
     * Windows sets both variables on every ordinary account; the fallback is for
     * the accounts where a service or a stripped environment does not, and it
     * names the folders Windows itself would have named.
     */
    private fun windowsBase(
        variableName: String,
        fallbackBelowProfile: String,
    ): Path {
        val configured = environment(variableName)?.takeIf { it.isNotBlank() }?.toPathOrNull()
        return if (configured != null && configured.isAbsolute) {
            configured.normalize()
        } else {
            // Windows' own path parser reads `/` as a separator, so the fallback
            // below is written once and lands as `AppData\Local` on Windows.
            homeDirectory(USER_PROFILE_VARIABLE, environment, variableName).resolve(fallbackBelowProfile).normalize()
        }
    }

    /**
     * Where the user's own folders start, read only when a fallback is needed.
     *
     * The root is a system property on Linux (`user.home`) and an environment
     * variable on Windows (`%USERPROFILE%`), so the caller says which and how to
     * read it. Both refuse the same way, because what the application does about
     * it is the same: it stops before touching anything (PLAN 14.8.1).
     */
    private fun homeDirectory(
        rootName: String,
        read: (String) -> String?,
        forVariable: String,
    ): Path {
        val home =
            read(rootName)?.takeIf { it.isNotBlank() }?.toPathOrNull()
                ?: throw notFound("$rootName is not set to a usable path while resolving $forVariable")
        if (!home.isAbsolute) {
            throw notFound("$rootName must be an absolute path while resolving $forVariable")
        }
        return home.normalize()
    }

    private fun notFound(reason: String): StartupRefused = StartupRefused(StartupProblem.FOLDERS_NOT_FOUND, IllegalStateException(reason))

    private fun String.toPathOrNull(): Path? =
        try {
            Path.of(this)
        } catch (_: InvalidPathException) {
            null
        }

    private fun namesWindows(operatingSystem: String?): Boolean = operatingSystem.orEmpty().lowercase(Locale.ROOT).contains("windows")

    private companion object {
        const val OPERATING_SYSTEM = "os.name"
        const val USER_HOME_PROPERTY = "user.home"
        const val DATA_HOME_VARIABLE = "XDG_DATA_HOME"
        const val CONFIG_HOME_VARIABLE = "XDG_CONFIG_HOME"
        const val STATE_HOME_VARIABLE = "XDG_STATE_HOME"
        const val DATA_HOME_FALLBACK = ".local/share"
        const val CONFIG_HOME_FALLBACK = ".config"
        const val STATE_HOME_FALLBACK = ".local/state"
        const val USER_PROFILE_VARIABLE = "USERPROFILE"
        const val LOCAL_APP_DATA_VARIABLE = "LOCALAPPDATA"
        const val ROAMING_APP_DATA_VARIABLE = "APPDATA"
        const val LOCAL_APP_DATA_FALLBACK = "AppData/Local"
        const val ROAMING_APP_DATA_FALLBACK = "AppData/Roaming"
        const val WINDOWS_DATA_DIRECTORY_NAME = "data"
        const val WINDOWS_STATE_DIRECTORY_NAME = "state"
        const val DATABASE_FILE_NAME = "pnp.db"
        const val BACKUPS_DIRECTORY_NAME = "backups"
        const val SETTINGS_FILE_NAME = "settings.json"
        const val APPEARANCE_FILE_NAME = "appearance.json"
        const val GAME_ORDER_FILE_NAME = "game-order.json"
        const val LOGS_DIRECTORY_NAME = "logs"
        const val TABLE_SIZES_FILE_NAME = "table-sizes.json"
    }
}
