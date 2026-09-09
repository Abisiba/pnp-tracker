package dev.pnptracker.data.database

import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.restore.BackupPlace
import dev.pnptracker.domain.backup.restore.BackupProbe
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.BackupRejection
import dev.pnptracker.domain.backup.restore.SUPPORTED_SOURCE_SCHEMA_VERSION
import dev.pnptracker.domain.backup.sha256Of
import dev.pnptracker.platform.files.XdgAppPathsResolver
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tries a backup out on a database that exists for the length of the question.
 *
 * Everything before this reasoned about the file. This asks storage: it makes a
 * database of the current schema in a temporary directory, empties it — the
 * twelve colours Room seeds a new database with included, because a restore
 * replaces the data rather than joining it (PLAN 14.4.3) — writes all fifteen
 * tables in the order PLAN 14.4.2 gives, runs `foreign_key_check` while the
 * transaction is still open, and then reads the whole thing back out through the
 * same reader a real backup is taken with. If what comes back is the same data
 * and the same checksum, the file describes a database that can exist.
 *
 * Three things about the boundary, and they are the point of the class.
 *
 * It takes no path. Not from the caller, not from configuration, not from a
 * default that could be changed elsewhere: it makes its own directory under the
 * system's temporary one and works only there. There is no argument anybody
 * could pass that would aim this at the user's database.
 *
 * It takes no database either. A production `AppDatabase` cannot be handed in,
 * so nothing here can be pointed at a connection that is already open on
 * somebody's data.
 *
 * And it offers no way to keep what it built. The temporary database is deleted
 * before this returns, on every path — the one where it worked as much as the
 * ones where it did not. Putting a validated backup into the live database is a
 * later slice's work and there is deliberately no road from here to there.
 */
class TemporaryBackupProbe(
    private val databases: DatabaseFactory = DatabaseFactory(),
    /**
     * Where the throwaway database goes.
     *
     * Named so a test can watch it, not so a caller can choose it: whatever it
     * answers is held against the two rules below before anything is created,
     * and a directory that is not a temporary one, or that is anywhere near the
     * application's own data, is refused rather than used.
     */
    private val temporaryDirectory: () -> Path = { Files.createTempDirectory("pnp-tracker-backup-probe") },
    private val applicationDataDirectory: () -> Path = { XdgAppPathsResolver().resolve().dataDirectory },
) : BackupProbe {
    override suspend fun probe(
        data: BackupData,
        dataSha256: String,
    ): BackupRejection? {
        val root = temporaryDirectory().toAbsolutePath().normalize()
        refuseAnythingButATemporaryDirectory(root)

        val file = root.resolve(PROBE_DATABASE_NAME)
        try {
            val database = databases.open(file)
            try {
                // Opens the database and runs the migrations and the seed, so
                // what the transaction below starts from is a real, current
                // schema rather than an empty file.
                database.gameDao().activeCount()
                load(database, data)
                return readBackAndCompare(database, data, dataSha256)
            } catch (refusedByStorage: SQLiteException) {
                return refused()
            } catch (refusedByARow: IllegalArgumentException) {
                // A row that storage accepted and the entities will not: an
                // invariant the checks above do not mirror. The backup is the
                // thing at fault, not this code, so it is refused rather than
                // allowed to travel as an exception.
                return refused()
            } catch (pointsAtNothing: BrokenBackupGraph) {
                // The graph was already checked in memory, so reaching this
                // means that check has a hole in it; the database is the second
                // opinion and it is the one that counts.
                return BackupRejection(BackupProblem.BROKEN_REFERENCE, BackupPlace("data"))
            } finally {
                database.close()
            }
        } finally {
            deleteEverythingUnder(root)
        }
    }

    /**
     * Empties the database and fills it from the backup, once, or not at all.
     *
     * The statements are the live restore's own, run through the same shared
     * writer, so what this trial proves is what a restore would do rather than
     * what a second copy of it would do. One transaction, as PLAN 14.4.3 requires
     * of a restore, so a probe that fails halfway leaves nothing behind to
     * confuse the reading that follows.
     */
    private suspend fun load(
        database: AppDatabase,
        data: BackupData,
    ) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction { replaceEverythingWith(this, data) }
        }
    }

    /**
     * Reads the whole database back and holds it against the file it came from.
     *
     * This is the strongest thing the probe says, and it is deliberately said
     * with the same reader a real backup is taken with. Every row goes back
     * through the entities on the way out, so every `init` invariant they state
     * is checked again by the database itself rather than by a list somebody
     * kept in step by hand; and the canonical writer and the digest are the ones
     * the format is defined by, so agreement here means the file, the storage
     * and the checksum all say the same thing.
     */
    private suspend fun readBackAndCompare(
        database: AppDatabase,
        data: BackupData,
        dataSha256: String,
    ): BackupRejection? {
        val userVersion =
            database.useReaderConnection { transactor ->
                transactor.usePrepared("PRAGMA user_version") { statement ->
                    check(statement.step()) { "PRAGMA user_version returned no row" }
                    statement.getInt(0)
                }
            }
        if (userVersion != SUPPORTED_SOURCE_SCHEMA_VERSION) return refused()

        val reread = BackupStore(database).snapshot()
        if (reread.sourceSchemaVersion != SUPPORTED_SOURCE_SCHEMA_VERSION) return refused()
        if (reread.data != data) return refused()
        if (sha256Of(canonicalBackupDataJson(reread.data).encodeToByteArray()) != dataSha256) return refused()
        return null
    }

    private fun refuseAnythingButATemporaryDirectory(root: Path) {
        val systemTemporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(root.startsWith(systemTemporary) && root != systemTemporary) {
            "A backup is only ever tried out in a temporary directory, and this one is not below $systemTemporary."
        }
        val applicationData = applicationDataDirectory().toAbsolutePath().normalize()
        check(!root.startsWith(applicationData)) {
            "Refusing to build a throwaway database inside the application's own data directory."
        }
    }

    private fun deleteEverythingUnder(root: Path) {
        if (!Files.exists(root)) return
        try {
            Files.walk(root).use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        } catch (couldNotDelete: IOException) {
            // The answer about the backup has already been worked out, and a
            // temporary file that outlived its directory does not change it.
        }
    }

    private companion object {
        const val PROBE_DATABASE_NAME = "backup-probe.db"
    }
}

private fun refused() = BackupRejection(BackupProblem.TEMP_VALIDATION_FAILED, BackupPlace("data"))
