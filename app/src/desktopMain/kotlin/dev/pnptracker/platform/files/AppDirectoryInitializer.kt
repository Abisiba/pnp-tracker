package dev.pnptracker.platform.files

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Creates the directories this application owns. Path resolution lives in
 * [XdgAppPathsResolver]; this class is the only place that touches the disk.
 *
 * Never creates `pnp.db` or `settings.json`: those belong to the components that
 * own them.
 */
class AppDirectoryInitializer {
    /**
     * Creates the data, backups and config directories if they are missing.
     * Safe to call repeatedly. Directories that already exist are left untouched,
     * including their permissions.
     */
    fun ensureDirectories(paths: XdgAppPaths) {
        createAppDirectory(paths.dataDirectory, "data")
        createAppDirectory(paths.backupsDirectory, "backups")
        createAppDirectory(paths.configDirectory, "config")
    }

    private fun createAppDirectory(
        directory: Path,
        label: String,
    ) {
        if (Files.isDirectory(directory)) return
        try {
            // Parents such as ~/.local/share are not ours, so they keep the default
            // permissions; only the application directory itself is owner-only.
            directory.parent?.let { Files.createDirectories(it) }
            Files.createDirectory(directory, *ownerOnlyAttributes(directory))
        } catch (alreadyExists: FileAlreadyExistsException) {
            if (!Files.isDirectory(directory)) {
                throw IOException(
                    "Cannot create the $label directory at $directory because a file with that name exists.",
                    alreadyExists,
                )
            }
        } catch (failure: IOException) {
            throw IOException("Cannot create the $label directory at $directory.", failure)
        }
    }

    private fun ownerOnlyAttributes(directory: Path): Array<FileAttribute<*>> =
        if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } else {
            emptyArray()
        }

    private companion object {
        val OWNER_ONLY: Set<PosixFilePermission> =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
    }
}
