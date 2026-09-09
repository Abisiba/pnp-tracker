package dev.pnptracker.data.database

import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.repository.backupDataOf
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.restore.BackupRestorer
import dev.pnptracker.domain.backup.restore.RestoreProblem
import dev.pnptracker.domain.backup.restore.SUPPORTED_SOURCE_SCHEMA_VERSION
import dev.pnptracker.domain.backup.restore.SafetySnapshot
import dev.pnptracker.domain.backup.restore.ValidatedBackup
import dev.pnptracker.domain.backup.sha256Of

/**
 * Replaces everything in the live database with a backup, in one transaction.
 *
 * This is the only place in the application that empties the user's tables, and
 * everything about it is arranged so that it cannot happen by accident. It takes
 * a [ValidatedBackup], which exists only at the end of the untrusted reader and
 * cannot be constructed anywhere else, and a [SafetySnapshot], which exists only
 * where the safety backup was made. A file, a path, a parsed document or a bare
 * list of records cannot be handed to it, so no caller can shorten the route.
 *
 * The transaction is `BEGIN IMMEDIATE`, so the write lock is taken before the
 * first read rather than upgraded to partway through. Room keeps one writer
 * connection, so from the moment it begins to the moment it commits nothing else
 * in this application writes; and because the lock is held from the start, the
 * reading that decides whether to go ahead describes the same database the
 * writing lands on.
 *
 * What happens inside it, in order (PLAN 14.4.3):
 *
 * ```text
 * 1  read the fifteen tables and hold them against the safety snapshot
 * 2  refuse, having written nothing, if they differ
 * 3  defer foreign keys
 * 4  empty the fifteen tables against the direction of the keys
 * 5  write the backup's rows along it
 * 6  run foreign_key_check explicitly
 * 7  read the fifteen tables again and hold them against the backup
 * 8  commit only if they are the same
 * ```
 *
 * Steps 1 and 7 are whole readings rather than counts. A count would agree with
 * a database that had the right number of the wrong rows, and the thing being
 * promised is that the data *is* the backup. They cost fifteen queries each and
 * never one per row.
 *
 * Nothing here writes a history event. PLAN 14.4.3 is explicit: the history a
 * restore leaves is the history that was in the backup, and a line saying "a
 * restore happened" would be replaced by the next restore anyway.
 */
class LiveBackupRestorer(
    private val database: AppDatabase,
) : BackupRestorer {
    override suspend fun restore(
        backup: ValidatedBackup,
        asItWas: SafetySnapshot,
    ): RestoreProblem? {
        try {
            return database.useWriterConnection { transactor ->
                transactor.immediateTransaction<Unit> {
                    val before = backupDataOf(database.backupDao().snapshot())
                    if (before != asItWas.data) throw ChangedUnderneath()

                    replaceEverythingWith(this, backup.data)

                    val after = backupDataOf(database.backupDao().snapshot())
                    if (after != backup.data) throw PostconditionFailed()
                }
                // Still holding the writer connection, and Room has only one, so
                // nothing else in this application can have committed between the
                // transaction ending and this reading it back.
                confirmAfterwards(backup)
            }
        } catch (changed: ChangedUnderneath) {
            return RestoreProblem.DATA_CHANGED_MEANWHILE
        } catch (pointsAtNothing: BrokenBackupGraph) {
            // The graph was checked in memory and again in a throwaway database
            // of this very schema, so arriving here means one of those checks
            // has a hole in it. The transaction has already been rolled back; the
            // user is told their data is untouched, which it is.
            return RestoreProblem.REFERENCES_NOT_WHOLE
        } catch (didNotHold: PostconditionFailed) {
            return RestoreProblem.COULD_NOT_APPLY
        } catch (refusedByStorage: SQLiteException) {
            return RestoreProblem.COULD_NOT_APPLY
        } catch (refusedByARow: IllegalArgumentException) {
            // An entity's own invariant, raised while the rows were read back.
            // It is a statement about the backup rather than about this code, and
            // the transaction it happened in is already undone.
            return RestoreProblem.COULD_NOT_APPLY
        }
        // A broken invariant of this application's own — an IllegalStateException
        // or a NullPointerException — is deliberately not caught. The transaction
        // is rolled back by the same unwinding either way, and dressing a defect
        // up as "the backup could not be applied" would hide it (PLAN 14.4.5).
    }

    /**
     * Asks the committed database whether it is what the backup said.
     *
     * The atomicity guarantee is the postcondition inside the transaction; this
     * is a second reading after the commit, and it exists to catch the case that
     * guarantee cannot see — a commit that succeeded and left something else
     * behind.
     *
     * It runs while the caller is still holding the writer connection. That is
     * not tidiness: Room keeps one writer, so holding it is what makes this a
     * statement about the restore rather than about whatever else happened to
     * write a moment later. Read outside the connection, a write landing in the
     * gap would make this disagree and be reported as a defect in here.
     *
     * The two kinds of answer are kept apart on purpose. Storage refusing to be
     * read is a fact about this machine, so it becomes an outcome the user is
     * told about, pointing them at the safety backup. Storage answering with data
     * that is not the backup, after a transaction whose own postcondition passed,
     * is impossible unless this code is wrong, so it is raised as the invariant
     * failure it is rather than dressed up as a bad backup (PLAN 14.4.5).
     */
    private suspend fun confirmAfterwards(backup: ValidatedBackup): RestoreProblem? {
        val committed =
            try {
                Confirmation(
                    schemaVersion = schemaVersion(),
                    referencesWhole = referencesAreWhole(),
                    data = backupDataOf(database.backupDao().snapshot()),
                )
            } catch (unreadable: SQLiteException) {
                return RestoreProblem.NOT_VERIFIED_AFTERWARDS
            }

        check(committed.schemaVersion == SUPPORTED_SOURCE_SCHEMA_VERSION) {
            "The restore committed against a schema this build does not support"
        }
        check(committed.referencesWhole) { "The restore committed a database with a reference to nothing" }
        check(committed.data == backup.data) { "The restore committed data that is not the backup's" }
        check(sha256Of(canonicalBackupDataJson(committed.data).encodeToByteArray()) == backup.dataSha256) {
            "The restore committed data whose checksum is not the backup's"
        }

        // Room refreshes what it observes when a writer connection is given back,
        // and that has already happened; asking again here makes it something the
        // caller can wait for rather than something that will have happened by
        // the time anybody looks. Every screen reads through one of these tables,
        // so this is what puts the restored data on them without a restart
        // (PLAN 14.4.3).
        database.invalidationTracker.refresh(*RESTORE_ORDER.map { (table, _) -> table }.toTypedArray())
        return null
    }

    private suspend fun schemaVersion(): Int =
        database.useReaderConnection { transactor ->
            transactor.usePrepared("PRAGMA user_version") { statement ->
                check(statement.step()) { "PRAGMA user_version returned no row" }
                statement.getInt(0)
            }
        }

    private suspend fun referencesAreWhole(): Boolean =
        database.useReaderConnection { transactor ->
            transactor.usePrepared("PRAGMA foreign_key_check") { statement -> !statement.step() }
        }

    private class Confirmation(
        val schemaVersion: Int,
        val referencesWhole: Boolean,
        val data: BackupData,
    )
}

/** The live data is no longer what the safety backup describes; nothing is written. */
private class ChangedUnderneath : Exception("The database changed after the safety backup was taken")

/** What went in is not what the backup says, so none of it stays in. */
private class PostconditionFailed : Exception("The restored rows are not the rows the backup holds")
