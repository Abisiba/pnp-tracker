package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.retention.BACKUP_HEADER_BYTES
import dev.pnptracker.domain.backup.retention.BackupDirectory
import dev.pnptracker.domain.backup.retention.InspectedBackupFile
import dev.pnptracker.domain.backup.retention.automaticBackupNameOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The backups folder on this machine, and the only place rotation touches disk.
 *
 * Everything above this works in file names; this is where a name becomes a
 * path, and the path never travels back. PLAN 14.4.7 keeps absolute paths out of
 * state, messages and logs, and the way that is made true rather than remembered
 * is that there is nothing above here to put one in.
 *
 * Three things it will not do.
 *
 * It does not look outside its folder. Entries are listed, never walked into,
 * and a name is resolved against the folder and then checked to be a direct
 * child of it — so a listing that somehow answered with `../pnp.db` resolves to
 * something this refuses rather than deletes.
 *
 * It does not follow links. Every question about a file is asked with
 * [LinkOption.NOFOLLOW_LINKS], so a symbolic link is seen as a link — which is
 * not an ordinary file, and therefore not something rotation may remove. A link
 * pointing at the user's database is the case this is for.
 *
 * And it does not read whole files. Only the first [BACKUP_HEADER_BYTES] of a
 * candidate are read, which is all the ownership test needs; a folder of large
 * backups costs the same to inspect as a folder of small ones.
 */
class DesktopBackupDirectory(
    private val backupsDirectory: Path,
) : BackupDirectory {
    override suspend fun inspect(): List<InspectedBackupFile> =
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(backupsDirectory)) return@withContext emptyList()
            val found = mutableListOf<InspectedBackupFile>()
            try {
                Files.newDirectoryStream(backupsDirectory).use { entries ->
                    entries.forEach { entry ->
                        // Not one of ours by name, so nothing else about it is
                        // any of this code's business — not its type, not its
                        // contents, not its existence.
                        val name = automaticBackupNameOf(entry.fileName.toString()) ?: return@forEach
                        val ordinary = Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        found +=
                            InspectedBackupFile(
                                name = name,
                                ordinaryFile = ordinary,
                                header = if (ordinary) headerOf(entry) else ByteArray(0),
                            )
                    }
                }
            } catch (couldNotList: IOException) {
                // A folder that cannot be read is a folder nothing will be
                // deleted from. Rotation has no answer to give and no reason to
                // stop the work it was following (PLAN 14.4.13).
                return@withContext emptyList()
            }
            found
        }

    override suspend fun remove(fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = insideThisFolder(fileName) ?: return@withContext false
            // Checked again here rather than trusted from the listing: whatever
            // was decided, what is deleted has to be a plain file at the moment
            // it is deleted.
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@withContext false
            try {
                Files.deleteIfExists(file)
            } catch (couldNotDelete: IOException) {
                false
            }
        }

    /**
     * [fileName] as a direct child of the backups folder, or null.
     *
     * A name is only ever a name here — no separators, no `..`, no second
     * folder. The check is on the resolved path rather than on the text, so it
     * holds however the name was spelled.
     */
    private fun insideThisFolder(fileName: String): Path? {
        val folder = backupsDirectory.toAbsolutePath().normalize()
        val file = folder.resolve(fileName).toAbsolutePath().normalize()
        if (file.parent != folder) return null
        return file
    }

    /** The first bytes of [file], or an empty array if it cannot be read. */
    private fun headerOf(file: Path): ByteArray =
        try {
            Files.newInputStream(file).use { stream ->
                val buffer = ByteArray(BACKUP_HEADER_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = stream.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
                buffer.copyOf(filled)
            }
        } catch (couldNotRead: IOException) {
            // Unreadable is not shown to be ours, and what is not shown to be
            // ours is left where it is.
            ByteArray(0)
        }
}
