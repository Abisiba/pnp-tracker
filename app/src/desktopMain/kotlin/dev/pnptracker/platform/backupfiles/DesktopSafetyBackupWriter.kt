package dev.pnptracker.platform.backupfiles

import dev.pnptracker.domain.backup.restore.SAFETY_BACKUP_NAME_ATTEMPTS
import dev.pnptracker.domain.backup.restore.SafetyBackupWriter
import dev.pnptracker.domain.backup.restore.safetyBackupFileName
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.files.AtomicFileWriter
import java.nio.file.Path

/**
 * Writes the pre-restore backup into the application's own backups directory.
 *
 * The same canonical bytes and the same atomic writer as a manual backup — PLAN
 * 14.4.4 is explicit that there is no second format — so the file this leaves is
 * an ordinary backup that the ordinary reader will open and the ordinary restore
 * will put back. That is the whole point of it: a way back that only a special
 * code path could use would not be one.
 *
 * How the name is taken, and why it is taken that way, is [ClaimedNameWriter]'s
 * to explain; both automatic backups claim their names by the same rule.
 */
class DesktopSafetyBackupWriter(
    backupsDirectory: Path,
    writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = AUTOMATIC_BACKUP_SUFFIX),
) : SafetyBackupWriter {
    private val names = ClaimedNameWriter(backupsDirectory, writer, attempts = SAFETY_BACKUP_NAME_ATTEMPTS)

    override suspend fun writeSafetyBackup(
        bytes: ByteArray,
        moment: LocalMoment,
    ): String = names.write(bytes) { attempt -> safetyBackupFileName(moment, attempt) }.first
}
