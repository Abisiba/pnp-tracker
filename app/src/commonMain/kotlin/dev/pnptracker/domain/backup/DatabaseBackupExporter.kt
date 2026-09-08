package dev.pnptracker.domain.backup

import dev.pnptracker.AppInfo
import kotlin.time.Clock

/**
 * Everything the database holds, read once, with the schema version that wrote it.
 */
data class BackupSnapshot(
    val sourceSchemaVersion: Int,
    val data: BackupData,
)

/**
 * One reading of the whole database, seen from the side that must not know what
 * a Room entity looks like.
 *
 * Kept as an interface for the same reason [dev.pnptracker.data.repository.ImportRollback]
 * is: the exporter above it can be driven without a database at all.
 */
interface BackupSource {
    /** @throws androidx.sqlite.SQLiteException if the database cannot be read. */
    suspend fun snapshot(): BackupSnapshot
}

/**
 * Makes a backup document out of what the database holds.
 *
 * It reads and it returns; it does not know what a file is. Choosing a
 * destination, replacing something already there and writing bytes to disk are a
 * later slice's work, and keeping them out means this cannot lose anybody's data
 * however it fails.
 *
 * The three things a document says about itself beyond the data — which
 * application version wrote it, which schema the rows came from, and when — come
 * from the three collaborators below rather than from ambient state, so the same
 * database and the same clock always produce the same document.
 */
class DatabaseBackupExporter(
    private val source: BackupSource,
    private val appInfo: AppInfo,
    private val clock: Clock,
) {
    /**
     * Reads the database and builds the document in memory.
     *
     * The clock is read once, and after the transaction has closed: a backup is
     * stamped with the moment it was taken, and holding a transaction open
     * across the hashing and the writing would keep the database busy for work
     * that has nothing to do with it.
     */
    suspend fun backupDocument(): BackupDocument {
        val snapshot = source.snapshot()
        val createdAt = clock.now()
        return backupDocumentOf(
            data = snapshot.data,
            appVersion = appInfo.version,
            sourceSchemaVersion = snapshot.sourceSchemaVersion,
            createdAt = createdAt,
        )
    }
}
