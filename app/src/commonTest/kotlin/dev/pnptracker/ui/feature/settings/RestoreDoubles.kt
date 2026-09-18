package dev.pnptracker.ui.feature.settings

import androidx.sqlite.SQLiteException
import dev.pnptracker.AppInfo
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.BackupSnapshot
import dev.pnptracker.domain.backup.BackupSource
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.restore.BackupInput
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.BackupRestorer
import dev.pnptracker.domain.backup.restore.BackupSourceGateway
import dev.pnptracker.domain.backup.restore.CountingProbe
import dev.pnptracker.domain.backup.restore.FakeBackupInput
import dev.pnptracker.domain.backup.restore.RestoreProblem
import dev.pnptracker.domain.backup.restore.SafetyBackupWriter
import dev.pnptracker.domain.backup.restore.SafetySnapshot
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.restore.ValidatedBackup
import dev.pnptracker.domain.backup.restore.aRestorableBackup
import dev.pnptracker.domain.backup.restore.aWholeBackup
import dev.pnptracker.domain.backup.restore.canonicalChecksumOf
import dev.pnptracker.domain.backup.restore.documentOf
import dev.pnptracker.domain.backup.restore.fileOf
import dev.pnptracker.domain.backup.retention.AutomaticBackupHousekeeping
import dev.pnptracker.domain.time.LocalMoment
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Clock
import kotlin.time.Instant

/** Storage saying no, in the one way this application turns into an answer. */
fun storageRefusal(): Throwable = SQLiteException("the database would not answer")

/** A clock that always says the same thing, so a file name is a fact and not a race. */
class StoppedRestoreClock(
    private val fixed: Instant = Instant.fromEpochMilliseconds(1_757_320_364_031),
) : Clock {
    override fun now(): Instant = fixed
}

/**
 * The open dialog, answering whatever a test needs.
 *
 * The gate is the interesting part: held open, it makes "the dialog is still on
 * screen" a state a test can press keys against, which is where a repeated key
 * or a second click does its damage.
 */
class FakeSourceGateway(
    private val chosen: BackupInput?,
    private val refuse: BackupFailure? = null,
) : BackupSourceGateway {
    var asked = 0
        private set
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun chooseSource(): BackupInput? {
        asked++
        gate?.await()
        refuse?.let { throw BackupException(it) }
        return chosen
    }
}

/** Somewhere the safety backup goes, and a record of whether it ever did. */
class FakeSafetyWriter(
    private val name: String = "pnp-oncesi-2026-09-09-090924.json",
    private val refuse: BackupFailure? = null,
) : SafetyBackupWriter {
    var writes = 0
        private set
    var lastBytes: ByteArray? = null
        private set
    var lastMoment: LocalMoment? = null
        private set
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun writeSafetyBackup(
        bytes: ByteArray,
        moment: LocalMoment,
    ): String {
        gate?.await()
        refuse?.let { throw BackupException(it) }
        writes++
        lastBytes = bytes
        lastMoment = moment
        return name
    }
}

/** The live replacement, counted rather than done. */
class FakeRestorer(
    private val problem: RestoreProblem? = null,
) : BackupRestorer {
    var applied = 0
        private set
    var lastBackup: ValidatedBackup? = null
        private set
    var lastSnapshot: SafetySnapshot? = null
        private set

    override suspend fun restore(
        backup: ValidatedBackup,
        asItWas: SafetySnapshot,
    ): RestoreProblem? {
        applied++
        lastBackup = backup
        lastSnapshot = asItWas
        return problem
    }
}

/** A restore that fails the way a defect fails, rather than the way a backup does. */
class BrokenRestorer(
    private val raise: () -> Throwable,
) : BackupRestorer {
    override suspend fun restore(
        backup: ValidatedBackup,
        asItWas: SafetySnapshot,
    ): RestoreProblem? = throw raise()
}

/** A database that answers with [data], or refuses to answer at all. */
class FakeBackupSource(
    private val data: BackupData = aWholeBackup(),
    private val refusal: (() -> Throwable)? = null,
) : BackupSource {
    var reads = 0
        private set

    override suspend fun snapshot(): BackupSnapshot {
        reads++
        refusal?.let { throw it() }
        return BackupSnapshot(sourceSchemaVersion = 8, data = data)
    }
}

/** A file holding a backup this application really wrote. */
fun aRealBackupFile(data: BackupData = aRestorableBackup()) = fileOf(documentOf(data))

/**
 * A backup that has been through every check, obtained the only way there is one.
 *
 * Not by reaching for the internal constructor: a [ValidatedBackup] means "this
 * went through the reader", and a test that made one another way would be
 * testing the restore against a promise nobody kept. The probe is the counting
 * one, because whether a throwaway database accepts these rows is its own test's
 * question.
 */
suspend fun aValidatedBackup(
    data: BackupData = aWholeBackup(),
    fileName: String = "pnp-yedek-2026-09-08.json",
): ValidatedBackup {
    val read = UntrustedBackupReader(CountingProbe()).read(FakeBackupInput(documentOf(data).encodeToByteArray(), fileName = fileName))
    return (read as BackupReadResult.Valid).backup
}

/** What the database held when the safety backup was taken. */
fun aSafetySnapshot(
    data: BackupData,
    fileName: String = "pnp-oncesi-2026-09-09-090924.json",
) = SafetySnapshot(fileName = fileName, data = data, dataSha256 = canonicalChecksumOf(data))

/**
 * A restore controller with everything around it standing still.
 *
 * The reader is the real one — this slice must not be tested against a
 * pretend check — with a probe that says yes, because what a throwaway database
 * does with a backup is [dev.pnptracker.data.database.TemporaryBackupProbe]'s own
 * test rather than this one's.
 */
fun aRestoreController(
    gateway: FakeSourceGateway,
    safety: FakeSafetyWriter = FakeSafetyWriter(),
    restorer: FakeRestorer = FakeRestorer(),
    source: FakeBackupSource = FakeBackupSource(),
    probe: CountingProbe = CountingProbe(),
    housekeeping: AutomaticBackupHousekeeping = RecordingHousekeeping(),
    clock: Clock = StoppedRestoreClock(),
) = RestoreController(
    sources = gateway,
    reader = UntrustedBackupReader(probe),
    exporter = DatabaseBackupExporter(source, AppInfo.Current, clock),
    safety = safety,
    restorer = restorer,
    housekeeping = housekeeping,
    clock = clock,
)

/**
 * Housekeeping that only remembers being asked.
 *
 * What matters at this level is *which* backup retention was told about and
 * *when*, not what it then removed — that is the rotation's own test. [failWith]
 * is how "clearing up went wrong" is put in front of the restore, which PLAN
 * 14.4.13 says must change nothing about it.
 */
class RecordingHousekeeping(
    private val failWith: (() -> Throwable)? = null,
) : AutomaticBackupHousekeeping {
    val askedAbout = mutableListOf<String>()

    override suspend fun afterWriting(setName: String) {
        askedAbout += setName
        failWith?.let { throw it() }
    }
}
