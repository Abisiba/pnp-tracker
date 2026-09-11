package dev.pnptracker.domain.backup.automatic

import androidx.sqlite.SQLiteException
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.time.localMomentOf
import kotlinx.serialization.SerializationException
import kotlin.time.Clock

/**
 * Reads the database, writes the backup, and then proves the backup.
 *
 * Three steps, and the third is the one that makes the other two worth anything.
 * PLAN 14.4.7 will not let a file be called a backup until it has been through
 * the real reading — the same [UntrustedBackupReader] a person's own file goes
 * through on its way back in, with its size limit, its strict UTF-8, its
 * checksum and its throwaway database. Saying "backed up" about bytes nobody has
 * read back is the failure this is built to make impossible.
 *
 * The verification is stricter than the reader alone. A file that reads as *a*
 * backup is not enough; it has to read as *this* backup, so the checksum and the
 * rows that come back are held against the ones that went down. That catches the
 * case the reader cannot see on its own — a write that landed on top of
 * something else, or a name that turned out to belong to another file.
 *
 * A file that fails verification is left where it is. It was written from the
 * live database and may well be perfectly good — verification can fail for a
 * reason about this machine rather than about the bytes — so throwing away a
 * copy of somebody's data because it could not be read a second time would be
 * the wrong direction entirely. Nothing calls it a backup; it simply stays,
 * exactly as an unrecognised file in that folder stays (PLAN 14.4.11).
 */
class VerifiedSnapshotTaker(
    private val exporter: DatabaseBackupExporter,
    private val writer: AutomaticSnapshotWriter,
    private val reader: UntrustedBackupReader,
    private val clock: Clock,
) : AutomaticSnapshotTaker {
    override suspend fun takeBeforeImport(): AutomaticSnapshot {
        val document =
            try {
                exporter.backupDocument()
            } catch (unreadable: SQLiteException) {
                throw SnapshotNotTaken(SnapshotProblem.DATABASE_NOT_READ, unreadable)
            } catch (unwritable: SerializationException) {
                throw SnapshotNotTaken(SnapshotProblem.DATABASE_NOT_READ, unwritable)
            }
        // A broken invariant of this application's own is deliberately not caught
        // here. It is a defect rather than an outcome, and hiding one behind "the
        // backup could not be taken" would hide it under an import (PLAN 14.4.5).

        val written =
            try {
                writer.writeImportSnapshot(document.json.encodeToByteArray(), localMomentOf(clock.now()))
            } catch (notWritten: BackupException) {
                throw SnapshotNotTaken(SnapshotProblem.NOT_WRITTEN, notWritten)
            }

        val readBack =
            when (val read = reader.read(written)) {
                is BackupReadResult.Refused -> throw SnapshotNotTaken(SnapshotProblem.NOT_VERIFIED)
                is BackupReadResult.Valid -> read.backup
            }
        // The file is a backup. These two say it is this one.
        if (readBack.dataSha256 != document.envelope.dataSha256) throw SnapshotNotTaken(SnapshotProblem.NOT_VERIFIED)
        if (readBack.data != document.envelope.data) throw SnapshotNotTaken(SnapshotProblem.NOT_VERIFIED)

        return AutomaticSnapshot(
            fileName = written.fileName,
            // The rows as they were read, which are the rows the transaction
            // will hold the database against. Taken from the document rather
            // than from what came back off the disk: the two are equal by the
            // checks just above, and the document is the reading that actually
            // happened at that moment.
            data = document.envelope.data,
            dataSha256 = document.envelope.dataSha256,
        )
    }
}
