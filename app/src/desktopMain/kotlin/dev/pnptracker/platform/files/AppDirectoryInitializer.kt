package dev.pnptracker.platform.files

import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
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
 * own them. Nothing here removes, moves or writes over anything — a directory
 * that is already there is left exactly as it is, permissions included.
 */
class AppDirectoryInitializer {
    /**
     * Creates the data, backups and config directories if they are missing.
     * Safe to call repeatedly. Directories that already exist are left untouched,
     * including their permissions.
     *
     * @throws StartupRefused with [StartupProblem.FOLDERS_NOT_CREATED] if one of
     *   them could not be made. The application may not go on to open the
     *   database after that (PLAN 14.7.6), and the refusal is what the window
     *   and the log are both made out of — neither of them looks at the cause,
     *   which is where the absolute path lives.
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
                throw refusal(label, alreadyExists)
            }
        } catch (failure: IOException) {
            throw refusal(label, failure)
        }
    }

    /**
     * The refusal this failure becomes, with the path left behind.
     *
     * The message used to name the directory, and that directory is under the
     * user's home: it carried their account name out of here and into whatever
     * read it. Which folder it was is of no use to the person reading the
     * window either — all three are ours and they are made together — so the
     * label stays for a developer reading a stack in a debugger and goes no
     * further (PLAN 14.4.5, PLAN 14.7.1).
     */
    private fun refusal(
        label: String,
        cause: IOException,
    ): StartupRefused = StartupRefused(StartupProblem.FOLDERS_NOT_CREATED, IOException("Cannot create the $label directory.", cause))

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
