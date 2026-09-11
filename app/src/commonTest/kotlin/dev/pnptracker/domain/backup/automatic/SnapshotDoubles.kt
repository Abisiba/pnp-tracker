package dev.pnptracker.domain.backup.automatic

import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.restore.BackupInput
import dev.pnptracker.domain.backup.restore.FakeBackupInput
import dev.pnptracker.domain.backup.restore.aWholeBackup
import dev.pnptracker.domain.backup.restore.canonicalChecksumOf
import dev.pnptracker.domain.backup.retention.importSnapshotFileName
import dev.pnptracker.domain.time.LocalMoment

/** The name an automatic import snapshot really carries, for the doubles below. */
const val A_SNAPSHOT_NAME: String = "pnp-otomatik-import-2026-09-09-143355.json"

/**
 * A snapshot obtained without a disk.
 *
 * The internal constructor is reached here and nowhere in production, which is
 * the point of it: only the taker that has written *and* read a file back may
 * make one, so a confirmation cannot be handed a promise nobody kept.
 */
fun anAutomaticSnapshot(
    data: BackupData = aWholeBackup(),
    fileName: String = A_SNAPSHOT_NAME,
) = AutomaticSnapshot(fileName = fileName, data = data, dataSha256 = canonicalChecksumOf(data))

/**
 * A place to write snapshots that is not a place at all.
 *
 * It keeps the bytes it was given and hands them back as the file, so a test can
 * make the disk lie — [corruptWith] replaces what comes back up with something
 * else, which is the only way to reach the case where a file is written and does
 * not read back as what was written.
 */
class FakeSnapshotWriter(
    private val refuseWith: BackupFailure? = null,
    private val corruptWith: String? = null,
    private val fileName: String = A_SNAPSHOT_NAME,
) : AutomaticSnapshotWriter {
    val written = mutableListOf<String>()
    var moments = mutableListOf<LocalMoment>()
        private set

    override suspend fun writeImportSnapshot(
        bytes: ByteArray,
        moment: LocalMoment,
    ): BackupInput {
        refuseWith?.let { throw BackupException(it) }
        moments += moment
        written += fileName
        val readBack = corruptWith?.encodeToByteArray() ?: bytes
        return FakeBackupInput(readBack, fileName = fileName)
    }
}

/**
 * A writer that names its files the way the real one does.
 *
 * Used where the *name* matters — a name rotation would not recognise is a name
 * housekeeping can do nothing with — rather than the contents.
 */
class NamingSnapshotWriter : AutomaticSnapshotWriter {
    val written = mutableListOf<String>()

    override suspend fun writeImportSnapshot(
        bytes: ByteArray,
        moment: LocalMoment,
    ): BackupInput {
        val name = importSnapshotFileName(moment, attempt = written.size + 1)
        written += name
        return FakeBackupInput(bytes, fileName = name)
    }
}

/**
 * A snapshot taker that simply answers, and counts.
 *
 * What matters above the taker is *whether* a snapshot was taken and *how many*,
 * not how it was produced — that is [VerifiedSnapshotTaker]'s own test.
 */
class FakeSnapshotTaker(
    private val data: BackupData = aWholeBackup(),
    private val fileName: String = A_SNAPSHOT_NAME,
    private val refuseWith: SnapshotProblem? = null,
) : AutomaticSnapshotTaker {
    var taken = 0
        private set

    override suspend fun takeBeforeImport(): AutomaticSnapshot {
        taken++
        refuseWith?.let { throw SnapshotNotTaken(it) }
        return anAutomaticSnapshot(data, fileName)
    }
}
