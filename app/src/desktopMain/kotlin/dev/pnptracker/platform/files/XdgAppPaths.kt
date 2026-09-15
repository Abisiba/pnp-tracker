package dev.pnptracker.platform.files

import java.nio.file.Path

/**
 * Absolute locations this application uses on disk, resolved from the XDG base
 * directories. Holding a value of this type says nothing about whether the
 * directories or files exist; see [AppDirectoryInitializer].
 */
data class XdgAppPaths(
    val dataDirectory: Path,
    val databaseFile: Path,
    val backupsDirectory: Path,
    val configDirectory: Path,
    val settingsFile: Path,
    /** `$XDG_STATE_HOME/pnp-tracker`: what the application keeps about itself, never user data. */
    val stateDirectory: Path,
    /** Where the diagnostic log lives (PLAN 14.7.1); made only when a first line is written. */
    val logsDirectory: Path,
)
