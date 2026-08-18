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
)
