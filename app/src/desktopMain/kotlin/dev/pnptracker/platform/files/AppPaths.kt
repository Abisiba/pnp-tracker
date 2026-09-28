package dev.pnptracker.platform.files

import java.nio.file.Path

/**
 * Absolute locations this application uses on disk.
 *
 * The three areas below — data, configuration and state — are the same three on
 * every supported system; only where they land differs, and that is decided in
 * one place ([AppPathsResolver]). Holding a value of this type says nothing
 * about whether the directories or files exist; see [AppDirectoryInitializer].
 */
data class AppPaths(
    /** The user's own data: the database and the backups beside it. */
    val dataDirectory: Path,
    val databaseFile: Path,
    val backupsDirectory: Path,
    /** The one setting this application has (PLAN 14.4.12). */
    val configDirectory: Path,
    val settingsFile: Path,
    /** What the application keeps about itself on this machine, never user data. */
    val stateDirectory: Path,
    /** Where the diagnostic log lives (PLAN 14.7.1); made only when a first line is written. */
    val logsDirectory: Path,
    /**
     * The sizes the game table is drawn at on this machine (PLAN 12.17).
     *
     * State and not configuration: the user does not edit it, and losing it costs
     * a layout rather than a setting. Made only when somebody resizes something.
     */
    val tableSizesFile: Path,
)
