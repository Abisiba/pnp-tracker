package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.migration.UnconvertibleLegacyDataException
import dev.pnptracker.domain.backup.automatic.MigrationSnapshotSet
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.domain.backup.automatic.StartupRefused
import dev.pnptracker.domain.backup.restore.SUPPORTED_SOURCE_SCHEMA_VERSION
import dev.pnptracker.domain.backup.retention.AutomaticBackupHousekeeping
import dev.pnptracker.platform.files.XdgAppPaths
import kotlinx.coroutines.runBlocking

/**
 * What a startup that went ahead produced.
 *
 * [set] is null on every start that needed no migration — a new database, or one
 * already on this build's schema — which is most of them.
 */
class OpenedDatabase(
    val database: AppDatabase,
    val set: MigrationSnapshotSet?,
)

/**
 * The only way this application opens the database it was started on.
 *
 * PLAN 14.4.10 is a numbered list and this is it, in order and without gaps:
 *
 * ```text
 *  1  the instance lock is taken, or the copy stops here
 *  2  the file's existence and user_version are established without Room
 *  3  no file, or nothing written yet  → ordinary creation, no snapshot
 *  4  version 8                        → no snapshot; open it
 *  5  version 1..7                     → a snapshot set is built first
 *  6  above 8, or unreadable           → the database is NOT opened
 *  7-12 the set is made and proved  (MigrationSnapshotSetWriter)
 * 13  nothing below runs until the set has succeeded
 * 14  so a failed clone or document means the real database is never migrated
 * 15  and only then is it opened by the real chain
 * 16  the lock is held across all of it
 * 17  every temporary file goes, on every path
 * ```
 *
 * Step 13 is the one worth making structural rather than remembering, and it is:
 * the real open happens inside the branch that holds a [MigrationSnapshotSet],
 * and that type cannot be made without both halves having been written and read
 * back. There is no path through this class that reaches Room with an old
 * database and no set.
 *
 * Retention runs after the set is proved and before the real migration. It is
 * fail open by contract — the housekeeping does not throw for an outcome — so a
 * folder that will not give up an old set cannot stop somebody starting their
 * application (PLAN 14.4.13).
 */
class StartupGate(
    private val paths: XdgAppPaths,
    private val databases: DatabaseFactory,
    private val sets: MigrationSnapshotSetWriter,
    private val housekeeping: AutomaticBackupHousekeeping,
    private val clone: ConsistentDatabaseClone = ConsistentDatabaseClone(),
    private val lock: InstanceLock = InstanceLock(paths.dataDirectory.resolve(INSTANCE_LOCK_NAME)),
) {
    /**
     * @throws StartupRefused if the application may not go on. The database has
     *   not been migrated and, on every problem but the last, not even opened.
     */
    fun open(): OpenedDatabase {
        val opened =
            try {
                lock.withLock { openUnderTheLock() }
            } catch (noLockFile: StartupLockUnavailable) {
                throw StartupRefused(StartupProblem.ANOTHER_COPY_IS_RUNNING, noLockFile)
            }
        return opened ?: throw StartupRefused(StartupProblem.ANOTHER_COPY_IS_RUNNING)
    }

    private fun openUnderTheLock(): OpenedDatabase {
        val version =
            try {
                clone.schemaVersionOf(paths.databaseFile)
            } catch (notADatabase: SQLiteException) {
                throw StartupRefused(StartupProblem.DATABASE_NOT_READABLE, notADatabase)
            }

        return when {
            version == NO_SCHEMA_YET -> OpenedDatabase(openForReal(), set = null)
            version == SUPPORTED_SOURCE_SCHEMA_VERSION -> OpenedDatabase(openForReal(), set = null)
            version > SUPPORTED_SOURCE_SCHEMA_VERSION -> throw StartupRefused(StartupProblem.SCHEMA_TOO_NEW)
            version < 0 -> throw StartupRefused(StartupProblem.DATABASE_NOT_READABLE)
            else -> migrateBehindASet(version)
        }
    }

    private fun migrateBehindASet(fromSchemaVersion: Int): OpenedDatabase {
        val set = sets.writeSetFor(paths.databaseFile, fromSchemaVersion)

        // Both halves are on disk and proved, so this set is now one of the
        // newest few its kind keeps. Nothing it does can stop the migration.
        runBlocking { housekeeping.afterWriting(set.setName) }

        return OpenedDatabase(openForReal(), set)
    }

    /**
     * Opens the real database, which is where any real migration happens.
     *
     * A failure here is the one problem in the list that leaves the set on disk
     * and says so: the copy migrated and the original did not, which is exactly
     * the case both artefacts exist for (PLAN 14.4.10).
     */
    private fun openForReal(): AppDatabase {
        val database = databases.open(paths.databaseFile)
        try {
            // The first read is what runs the chain, so a database that cannot
            // be migrated is found here rather than behind the first screen the
            // user opens.
            runBlocking { database.gameDao().activeCount() }
        } catch (refused: SQLiteException) {
            database.close()
            throw StartupRefused(StartupProblem.MIGRATION_FAILED, refused)
        } catch (refused: IllegalStateException) {
            // Room's own answer to a schema it cannot reconcile.
            database.close()
            throw StartupRefused(StartupProblem.MIGRATION_FAILED, refused)
        } catch (refused: UnconvertibleLegacyDataException) {
            // A migration that says no on purpose (PLAN 18). Unreachable behind
            // a successful set — the working copy would have refused first — and
            // caught anyway, because a refusal is an answer to show rather than
            // a crash to watch.
            database.close()
            throw StartupRefused(StartupProblem.MIGRATION_FAILED, refused)
        }
        return database
    }
}
