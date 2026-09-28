package dev.pnptracker.platform.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

/**
 * Taking a place away from the application, on whichever system the suite runs on.
 *
 * "A folder that cannot be written to refuses the startup" and "a file that
 * cannot be read is reported rather than guessed at" are contracts about the
 * application, not about Linux (PLAN 14.8.5), so the mechanism is chosen here and
 * the tests keep their claims. A POSIX file system says it with a mode; NTFS says
 * it with a deny entry in the access list, which is the same sentence in the only
 * language that system speaks.
 */
internal object PlatformFileRules {
    private val onWindows =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase(Locale.ROOT)
            .contains("windows")

    /** The name the Java launcher has here; a child process is started with it. */
    fun javaLauncher(): Path = Path.of(System.getProperty("java.home"), "bin", if (onWindows) "java.exe" else "java")

    /** Stops anything being made inside [directory]. Undone by [letWritingBack]. */
    fun refuseWriting(directory: Path) {
        if (usesAccessLists(directory)) {
            deny(directory, setOf(AclEntryPermission.ADD_FILE, AclEntryPermission.ADD_SUBDIRECTORY, AclEntryPermission.WRITE_DATA))
        } else {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
        }
    }

    /** Gives [directory] back, so a temporary tree can be removed afterwards. */
    fun letWritingBack(directory: Path) {
        if (usesAccessLists(directory)) {
            allow(directory)
        } else {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }
    }

    /** Stops [file] being read. Undone by [letReadingBack]. */
    fun refuseReading(file: Path) {
        if (usesAccessLists(file)) {
            deny(file, setOf(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES))
        } else {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
        }
    }

    /** Gives [file] back. */
    fun letReadingBack(file: Path) {
        if (usesAccessLists(file)) {
            allow(file)
        } else {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
    }

    /**
     * Whether this path answers with an access list rather than a mode.
     *
     * The same question the application itself asks before it sets an owner-only
     * mode (`AppDirectoryInitializer`), so a test and the code it tests agree on
     * what the file system is.
     */
    private fun usesAccessLists(path: Path): Boolean = !path.fileSystem.supportedFileAttributeViews().contains("posix")

    private fun deny(
        path: Path,
        permissions: Set<AclEntryPermission>,
    ) {
        val view =
            Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                ?: error("neither POSIX permissions nor an access list are available for $path")
        val entry =
            AclEntry
                .newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(Files.getOwner(path))
                .setPermissions(permissions)
                // Inherited, so what is made inside afterwards is refused too; a
                // directory with nothing in it yet is the ordinary case here.
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build()
        // First in the list: Windows reads the entries in order and the first one
        // that answers wins, so an allow further down must not get there first.
        view.acl = listOf(entry) + view.acl
    }

    private fun allow(path: Path) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
        view.acl = view.acl.filterNot { it.type() == AclEntryType.DENY }
    }
}
