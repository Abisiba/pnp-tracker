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
    /** The settings the user chose, each in its own document (PLAN 14.4.12, 12.16). */
    val configDirectory: Path,
    val settingsFile: Path,
    /**
     * How the application looks: the theme and the accent colour (PLAN 12.16).
     *
     * Configuration and not state, because the user chose it on purpose and would
     * miss it if it went. Its own file beside the settings rather than a field in
     * them, because the two are written and read by different parts of the
     * application at different moments.
     */
    val appearanceFile: Path,
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
    /**
     * The order the user put their games in, on this machine (PLAN 12.18).
     *
     * State beside the table's sizes, for the same reason they are state: it is
     * how this machine lays the games out, not a fact about the games, so it is not
     * in the database and does not travel in a backup.
     */
    val gameOrderFile: Path,
)
