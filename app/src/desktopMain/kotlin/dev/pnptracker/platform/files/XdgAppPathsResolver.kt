package dev.pnptracker.platform.files

import dev.pnptracker.AppInfo
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Computes where this application keeps its data and its configuration, following
 * the XDG Base Directory specification.
 *
 * Resolving is a pure calculation: nothing is read from or written to disk.
 */
class XdgAppPathsResolver(
    private val appId: String = AppInfo.Current.id,
    private val environment: (String) -> String? = System::getenv,
    private val userHome: () -> String? = { System.getProperty("user.home") },
) {
    fun resolve(): XdgAppPaths {
        val dataDirectory = baseDirectory(DATA_HOME_VARIABLE, DATA_HOME_FALLBACK).resolve(appId)
        val configDirectory = baseDirectory(CONFIG_HOME_VARIABLE, CONFIG_HOME_FALLBACK).resolve(appId)
        return XdgAppPaths(
            dataDirectory = dataDirectory,
            databaseFile = dataDirectory.resolve(DATABASE_FILE_NAME),
            backupsDirectory = dataDirectory.resolve(BACKUPS_DIRECTORY_NAME),
            configDirectory = configDirectory,
            settingsFile = configDirectory.resolve(SETTINGS_FILE_NAME),
        )
    }

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
            homeDirectory(variableName).resolve(fallbackBelowHome).normalize()
        }
    }

    private fun homeDirectory(variableName: String): Path {
        val raw =
            userHome() ?: throw IllegalStateException(
                "Cannot fall back to the home directory while resolving $variableName: " +
                    "the user.home system property is not set.",
            )
        if (raw.isBlank()) {
            throw IllegalStateException(
                "Cannot fall back to the home directory while resolving $variableName: " +
                    "the user.home system property is empty.",
            )
        }
        val home =
            raw.toPathOrNull() ?: throw IllegalStateException(
                "Cannot fall back to the home directory while resolving $variableName: " +
                    "the user.home system property is not a valid path: $raw",
            )
        if (!home.isAbsolute) {
            throw IllegalStateException(
                "Cannot fall back to the home directory while resolving $variableName: " +
                    "the user.home system property must be an absolute path, was: $raw",
            )
        }
        return home.normalize()
    }

    private fun String.toPathOrNull(): Path? =
        try {
            Path.of(this)
        } catch (_: InvalidPathException) {
            null
        }

    private companion object {
        const val DATA_HOME_VARIABLE = "XDG_DATA_HOME"
        const val CONFIG_HOME_VARIABLE = "XDG_CONFIG_HOME"
        const val DATA_HOME_FALLBACK = ".local/share"
        const val CONFIG_HOME_FALLBACK = ".config"
        const val DATABASE_FILE_NAME = "pnp.db"
        const val BACKUPS_DIRECTORY_NAME = "backups"
        const val SETTINGS_FILE_NAME = "settings.json"
    }
}
