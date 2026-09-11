package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.automatic.AutomaticSnapshotWriter
import dev.pnptracker.domain.backup.restore.BackupInput
import dev.pnptracker.domain.backup.retention.importSnapshotFileName
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.files.AtomicFileWriter
import java.nio.file.Path

/**
 * Writes the backup that stands in front of an import, into the application's
 * own backups directory.
 *
 * The twin of [DesktopSafetyBackupWriter], down to the way it claims its name,
 * and different in exactly two ways: the name it asks for is
 * [importSnapshotFileName]'s, and it hands the written file back as something to
 * read rather than as a name alone.
 *
 * That second difference is the point of this class. PLAN 14.4.7 will not let a
 * file be called a backup until it has been read back, and reading it back has
 * to mean reading the file that is really on the disk — not the bytes that were
 * handed over to be written. So the [Path] stays in here, where every other path
 * in this application stays, and what leaves is a [BackupInput]: a name and a
 * way to open it (PLAN 14.4.5).
 */
class DesktopImportSnapshotWriter(
    backupsDirectory: Path,
    writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = AUTOMATIC_BACKUP_SUFFIX),
) : AutomaticSnapshotWriter {
    private val names = ClaimedNameWriter(backupsDirectory, writer)

    override suspend fun writeImportSnapshot(
        bytes: ByteArray,
        moment: LocalMoment,
    ): BackupInput {
        val (_, file) = names.write(bytes) { attempt -> importSnapshotFileName(moment, attempt) }
        return PathBackupInput(file)
    }
}
