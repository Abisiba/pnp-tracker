package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteException
import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.migration.UnconvertibleLegacyDataException
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.automatic.MigrationSnapshotSet
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.SUPPORTED_SOURCE_SCHEMA_VERSION
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.BACKUP_HEADER_BYTES
import dev.pnptracker.domain.backup.retention.beginsLikeADatabaseOfVersion
import dev.pnptracker.domain.backup.retention.migrationSnapshotSetName
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.platform.backupfiles.ClaimedSetNames
import dev.pnptracker.platform.backupfiles.PathBackupInput
import dev.pnptracker.platform.backupfiles.claimSetNames
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.AtomicWriteException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Clock

/** What a half-written half of a set is called while it is still half written. */
private const val PART_SUFFIX = ".part"

/** The name the working copy of the old database is migrated under. */
private const val WORKING_COPY_NAME = "calisma-kopyasi.db"

/**
 * Builds the two matched artefacts that stand in front of a migration.
 *
 * The order is the whole of PLAN 14.4.10's steps 7 to 12, and each step is the
 * ground the next one stands on:
 *
 * ```text
 *  7  both names are claimed at once, so the pair share an ordinal
 *  8  the old database is cloned read-only; the clone is proved before anything
 *     is done with it — header, user_version, integrity, references, and the
 *     same tables with the same number of rows as the source
 *  9  a separate working copy of that clone is migrated by the real chain
 * 10  the canonical document is exported from the migrated working copy
 * 11  it is written and then read back by the real untrusted reader
 * 12  and held against a fresh reading of the working copy
 * ```
 *
 * Two things about it are worth saying plainly.
 *
 * **The migration never touches the user's database.** What is migrated is a
 * copy of a copy: the raw clone is left exactly as it was cloned, and a third
 * file — in a temporary directory, deleted on every path — is what the chain
 * runs over. That is what makes the raw half worth having; a `.db` produced by
 * the code it is meant to protect against would protect against nothing.
 *
 * **Nothing that holds data is thrown away.** If the set fails, the empty name
 * claims are given back and the `.part` files and the working directory go, but
 * a half that was really written stays where it is. It is a consistent copy of
 * somebody's database made seconds ago; rotation will never touch it (an
 * unmatched half is not a set), and it costs disk rather than data.
 */
class MigrationSnapshotSetWriter(
    private val backupsDirectory: Path,
    private val clone: ConsistentDatabaseClone = ConsistentDatabaseClone(),
    private val databases: DatabaseFactory = DatabaseFactory(),
    private val reader: UntrustedBackupReader,
    private val appInfo: AppInfo = AppInfo.Current,
    private val clock: Clock = Clock.System,
    private val documents: AtomicFileWriter = AtomicFileWriter(temporarySuffix = PART_SUFFIX),
    private val workingDirectory: () -> Path = { Files.createTempDirectory("pnp-tracker-migration-copy") },
    private val applicationDataDirectory: () -> Path = { backupsDirectory.parent },
    private val moment: () -> LocalMoment,
) {
    /**
     * @throws StartupRefused if either half could not be made or proved. The
     *   user's database has not been touched when this throws.
     */
    fun writeSetFor(
        databaseFile: Path,
        fromSchemaVersion: Int,
    ): MigrationSnapshotSet {
        val names =
            try {
                claimSetNames(backupsDirectory) { attempt ->
                    migrationSnapshotSetName(
                        fromSchemaVersion = fromSchemaVersion,
                        toSchemaVersion = SUPPORTED_SOURCE_SCHEMA_VERSION,
                        moment = moment(),
                        attempt = attempt,
                    )
                }
            } catch (notClaimed: BackupException) {
                throw StartupRefused(StartupProblem.SNAPSHOT_NOT_WRITTEN, notClaimed)
            }

        var rawHalfProved = false
        var finished = false
        try {
            cloneTheOldDatabase(databaseFile, names, fromSchemaVersion)
            rawHalfProved = true
            writeTheDocument(names)
            finished = true
            return MigrationSnapshotSet(
                setName = names.setName,
                fromSchemaVersion = fromSchemaVersion,
                toSchemaVersion = SUPPORTED_SOURCE_SCHEMA_VERSION,
            )
        } finally {
            if (!finished) giveBackWhatIsNotWorthKeeping(names, rawHalfProved)
        }
    }

    // ------------------------------------------------------------- the raw half

    private fun cloneTheOldDatabase(
        databaseFile: Path,
        names: ClaimedSetNames,
        fromSchemaVersion: Int,
    ) {
        val half = Path.of("${names.database}$PART_SUFFIX")
        // `VACUUM INTO` refuses a target that exists, and the claim is a real
        // empty file — so the clone is made beside it and moved on top. The
        // `.part` name is ours by the same claim, and a crash could have left one
        // under it; it is removed first rather than allowed to refuse the vacuum.
        runCatching { Files.deleteIfExists(half) }
        try {
            clone.cloneInto(databaseFile, half)
            Files.move(half, names.database, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (couldNotClone: SQLiteException) {
            runCatching { Files.deleteIfExists(half) }
            throw StartupRefused(StartupProblem.SNAPSHOT_NOT_CLONED, couldNotClone)
        } catch (couldNotMove: IOException) {
            runCatching { Files.deleteIfExists(half) }
            throw StartupRefused(StartupProblem.SNAPSHOT_NOT_CLONED, couldNotMove)
        }

        val proved =
            try {
                provesToBeTheOldDatabase(databaseFile, names.database, fromSchemaVersion)
            } catch (unreadable: SQLiteException) {
                throw StartupRefused(StartupProblem.SNAPSHOT_NOT_CLONED, unreadable)
            } catch (unreadable: IOException) {
                throw StartupRefused(StartupProblem.SNAPSHOT_NOT_CLONED, unreadable)
            }
        if (!proved) throw StartupRefused(StartupProblem.SNAPSHOT_NOT_CLONED)
    }

    /**
     * Whether the clone is the old database, asked four ways.
     *
     * The row counts are the one that matters most and the one a checksum could
     * not give: `VACUUM` rewrites the file, so the clone is not the source's
     * bytes and must not be compared as such. What it has to be is the same
     * tables holding the same number of rows, on the same schema version, with
     * nothing broken.
     */
    private fun provesToBeTheOldDatabase(
        databaseFile: Path,
        cloned: Path,
        fromSchemaVersion: Int,
    ): Boolean {
        val header = ByteArray(BACKUP_HEADER_BYTES)
        val read =
            Files.newInputStream(cloned).use { stream ->
                var filled = 0
                while (filled < header.size) {
                    val count = stream.read(header, filled, header.size - filled)
                    if (count < 0) break
                    filled += count
                }
                filled
            }
        if (!beginsLikeADatabaseOfVersion(header.copyOf(read), fromSchemaVersion)) return false
        if (clone.schemaVersionOf(cloned) != fromSchemaVersion) return false
        if (!clone.isWholeAndConsistent(cloned)) return false
        return clone.tableRowCounts(cloned) == clone.tableRowCounts(databaseFile)
    }

    // ---------------------------------------------------------- the other half

    private fun writeTheDocument(names: ClaimedSetNames) {
        val root = workingDirectory().toAbsolutePath().normalize()
        refuseAnythingButATemporaryDirectory(root)
        try {
            val workingCopy = root.resolve(WORKING_COPY_NAME)
            try {
                Files.copy(names.database, workingCopy)
            } catch (couldNotCopy: IOException) {
                throw StartupRefused(StartupProblem.SNAPSHOT_NOT_MIGRATED, couldNotCopy)
            }
            migrateAndExport(workingCopy, names)
        } finally {
            deleteEverythingUnder(root)
        }
    }

    private fun migrateAndExport(
        workingCopy: Path,
        names: ClaimedSetNames,
    ) {
        val database =
            try {
                databases.open(workingCopy)
            } catch (refused: SQLiteException) {
                throw StartupRefused(StartupProblem.SNAPSHOT_NOT_MIGRATED, refused)
            }
        try {
            val snapshot =
                try {
                    // The first read is what runs the chain. Everything this
                    // application knows about migrating lives in DatabaseFactory,
                    // and the copy goes through it exactly as the real database
                    // will a moment later — a second list of migrations here
                    // would be the one thing able to drift.
                    runBlocking {
                        database.gameDao().activeCount()
                        BackupStore(database).snapshot()
                    }
                } catch (refused: SQLiteException) {
                    throw StartupRefused(StartupProblem.SNAPSHOT_NOT_MIGRATED, refused)
                } catch (refusedByARow: IllegalArgumentException) {
                    // A row the old schema held and the current entities will
                    // not. It is a statement about the data rather than about
                    // this code, and it stops the set rather than travelling on.
                    throw StartupRefused(StartupProblem.SNAPSHOT_NOT_MIGRATED, refusedByARow)
                } catch (refused: UnconvertibleLegacyDataException) {
                    // A migration saying no on purpose. PLAN 18 lets a migration
                    // refuse rather than invent, and `Migration3To4` does exactly
                    // that for a version 3 database still holding games, items or
                    // tasks. It is the clearest possible reason not to go on: the
                    // real database would refuse in the same way a moment later,
                    // so the user is told now, with nothing of theirs touched.
                    throw StartupRefused(StartupProblem.SNAPSHOT_NOT_MIGRATED, refused)
                }
            if (snapshot.sourceSchemaVersion != SUPPORTED_SOURCE_SCHEMA_VERSION) {
                throw StartupRefused(StartupProblem.SNAPSHOT_NOT_MIGRATED)
            }

            val document =
                try {
                    runBlocking {
                        DatabaseBackupExporter(BackupStore(database), appInfo, clock).backupDocument()
                    }
                } catch (unreadable: SQLiteException) {
                    throw StartupRefused(StartupProblem.SNAPSHOT_NOT_WRITTEN, unreadable)
                } catch (unwritable: SerializationException) {
                    throw StartupRefused(StartupProblem.SNAPSHOT_NOT_WRITTEN, unwritable)
                }

            try {
                documents.write(names.document, document.json.encodeToByteArray())
            } catch (notWritten: AtomicWriteException) {
                throw StartupRefused(StartupProblem.SNAPSHOT_NOT_WRITTEN, notWritten)
            }

            // Read back off the disk by the reader a person's own file goes
            // through, and then held against a fresh reading of the copy it was
            // taken from — so the document is proved to be a backup *and* proved
            // to be this one (PLAN 14.4.9).
            val readBack =
                runBlocking { reader.read(PathBackupInput(names.document)) } as? BackupReadResult.Valid
                    ?: throw StartupRefused(StartupProblem.SNAPSHOT_NOT_VERIFIED)
            val reread = runBlocking { BackupStore(database).snapshot() }
            if (readBack.backup.sourceSchemaVersion != SUPPORTED_SOURCE_SCHEMA_VERSION) {
                throw StartupRefused(StartupProblem.SNAPSHOT_NOT_VERIFIED)
            }
            if (readBack.backup.data != reread.data) throw StartupRefused(StartupProblem.SNAPSHOT_NOT_VERIFIED)
        } finally {
            database.close()
        }
    }

    // ----------------------------------------------------------------- tidying

    /**
     * Takes back what this attempt should not leave, and keeps what it should.
     *
     * Three cases, and the distinction is what the file can be said to be.
     *
     * An **empty claim** is a name and nothing else, and an empty `.db` beside
     * an empty `.json` is two files pretending to be a backup. Those go.
     *
     * A raw half that **failed its own checks** goes too. It is not the database
     * its name says it is — that is precisely what the checks established — and
     * leaving a file whose name is untrue is worse than leaving nothing.
     *
     * A raw half that **passed** stays, whatever went wrong afterwards. It is a
     * consistent copy of somebody's database taken seconds ago, and an unmatched
     * half is out of rotation's reach for good (PLAN 14.4.11): it costs disk and
     * loses nothing.
     */
    private fun giveBackWhatIsNotWorthKeeping(
        names: ClaimedSetNames,
        rawHalfProved: Boolean,
    ) {
        listOf(names.database, names.document).forEach { half ->
            runCatching { if (Files.exists(half) && Files.size(half) == 0L) Files.delete(half) }
        }
        if (!rawHalfProved) runCatching { Files.deleteIfExists(names.database) }
        runCatching { Files.deleteIfExists(Path.of("${names.database}$PART_SUFFIX")) }
    }

    private fun refuseAnythingButATemporaryDirectory(root: Path) {
        val systemTemporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(root.startsWith(systemTemporary) && root != systemTemporary) {
            "A database is only ever migrated for a snapshot in a temporary directory, and this one is not below $systemTemporary."
        }
        val applicationData = applicationDataDirectory().toAbsolutePath().normalize()
        check(!root.startsWith(applicationData)) {
            "Refusing to migrate a working copy inside the application's own data directory."
        }
    }

    /**
     * Removes the working directory and everything in it.
     *
     * PLAN 14.4.10 step 17 names the working database, its `-wal`, its `-shm`
     * and any `.part` beside it, and this takes the directory they are all in —
     * on the path where it worked as much as on the ones where it did not.
     */
    private fun deleteEverythingUnder(root: Path) {
        if (!Files.exists(root)) return
        runCatching {
            Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
