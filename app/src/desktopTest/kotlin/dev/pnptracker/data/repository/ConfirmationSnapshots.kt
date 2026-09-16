package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.dao.GameCellDao
import dev.pnptracker.data.database.dao.GameDao
import dev.pnptracker.data.database.dao.ImportDao
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshot
import dev.pnptracker.domain.backup.automatic.AutomaticSnapshotTaker
import dev.pnptracker.domain.backup.automatic.SnapshotNotTaken
import dev.pnptracker.domain.backup.automatic.SnapshotProblem
import dev.pnptracker.domain.backup.automatic.anAutomaticSnapshot
import dev.pnptracker.domain.backup.retention.AutomaticBackupHousekeeping
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.ui.feature.settings.RecordingHousekeeping
import kotlin.time.Clock

/**
 * A snapshot of the real database, taken without a disk.
 *
 * What the confirmation needs from a snapshot is the rows, and this reads them
 * through the very same [BackupStore] the file would have been built from — so
 * the guard inside the transaction is being held against a true reading rather
 * than against something a test invented. Writing and verifying a file is
 * [dev.pnptracker.domain.backup.automatic.VerifiedSnapshotTaker]'s own test, and
 * the end to end tests are where the two meet on a real disk.
 *
 * [stale] is how the race is reached: the snapshot is taken once and held, so a
 * change made afterwards leaves the confirmation holding a description of a
 * database that has moved on.
 */
class LiveSnapshotTaker(
    private val database: AppDatabase,
    private val refuseWith: SnapshotProblem? = null,
    private val stale: Boolean = false,
) : AutomaticSnapshotTaker {
    var taken = 0
        private set

    private var held: AutomaticSnapshot? = null

    override suspend fun takeBeforeImport(): AutomaticSnapshot {
        taken++
        refuseWith?.let { throw SnapshotNotTaken(it) }
        held?.takeIf { stale }?.let { return it }
        return anAutomaticSnapshot(BackupStore(database).snapshot().data).also { held = it }
    }
}

/**
 * The confirmation store as every test builds it: a real database, a real
 * import DAO, and a snapshot that is taken for real but written nowhere.
 */
fun confirmationStore(
    database: AppDatabase,
    importDao: ImportDao = database.importDao(),
    gameCellDao: GameCellDao = database.gameCellDao(),
    gameDao: GameDao = database.gameDao(),
    snapshots: AutomaticSnapshotTaker = LiveSnapshotTaker(database),
    housekeeping: AutomaticBackupHousekeeping = RecordingHousekeeping(),
    idGenerator: IdGenerator = IdGenerator.Random,
    clock: Clock = Clock.System,
    diagnostics: Diagnostics = Diagnostics.None,
) = ImportConfirmationStore(
    database = database,
    importDao = importDao,
    gameCellDao = gameCellDao,
    gameDao = gameDao,
    snapshots = snapshots,
    housekeeping = housekeeping,
    idGenerator = idGenerator,
    clock = clock,
    diagnostics = diagnostics,
)
