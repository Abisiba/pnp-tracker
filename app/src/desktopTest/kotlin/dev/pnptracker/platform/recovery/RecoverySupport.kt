package dev.pnptracker.platform.recovery

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.data.repository.DraftEdit
import dev.pnptracker.data.repository.ImportConfirmationStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.automatic.VerifiedSnapshotTaker
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.backup.sha256Of
import dev.pnptracker.domain.importprep.PreparedImportDraft
import dev.pnptracker.domain.importprep.PreparedRawBlock
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.spreadsheet.SheetVisibility
import dev.pnptracker.domain.time.localMomentOf
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.backupfiles.DesktopImportSnapshotWriter
import dev.pnptracker.platform.files.XdgAppPaths
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.platform.startup.MigrationSnapshotSetWriter
import dev.pnptracker.platform.startup.StartupGate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

// The shared vocabulary of the interrupted-write tests and the process they kill.
//
// Both sides of the process boundary build the application the way `Main` does —
// the gate, the real stores, the real snapshot writer and reader — so the only
// thing that differs between a killed run and the application a person uses is
// the one statement the child is told to stop in front of.

/** How many raw cells the draft below has; the save is stopped part way through them. */
const val PREPARED_CELL_COUNT = 5

/** The `-wal` beside a database. */
fun walOf(databaseFile: Path): Path = Path.of("$databaseFile-wal")

/** The gate exactly as `Main` builds it, over whichever factory the caller hands in. */
fun gateFor(
    paths: XdgAppPaths,
    databases: DatabaseFactory = DatabaseFactory(),
    temporaryDirectory: () -> Path = { Files.createTempDirectory("pnp-tracker-recovery-probe") },
): StartupGate =
    StartupGate(
        paths = paths,
        databases = databases,
        sets =
            MigrationSnapshotSetWriter(
                backupsDirectory = paths.backupsDirectory,
                reader = readerFor(paths, temporaryDirectory),
                moment = { localMomentOf(Clock.System.now()) },
            ),
        housekeeping = housekeepingFor(paths),
    )

fun readerFor(
    paths: XdgAppPaths,
    temporaryDirectory: () -> Path,
): UntrustedBackupReader =
    UntrustedBackupReader(
        TemporaryBackupProbe(temporaryDirectory = temporaryDirectory, applicationDataDirectory = { paths.dataDirectory }),
    )

fun housekeepingFor(paths: XdgAppPaths): SettingsDrivenHousekeeping =
    SettingsDrivenHousekeeping(
        settings = DesktopSettingsStore(paths.settingsFile),
        rotation = AutomaticBackupRotation(DesktopBackupDirectory(paths.backupsDirectory)),
    )

/** The confirmation store exactly as `Main` wires it: a snapshot written, read back and kept. */
fun realConfirmationStore(
    database: AppDatabase,
    paths: XdgAppPaths,
    temporaryDirectory: () -> Path,
): ImportConfirmationStore =
    ImportConfirmationStore(
        database = database,
        importDao = database.importDao(),
        gameCellDao = database.gameCellDao(),
        gameDao = database.gameDao(),
        snapshots =
            VerifiedSnapshotTaker(
                exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System),
                writer = DesktopImportSnapshotWriter(paths.backupsDirectory),
                reader = readerFor(paths, temporaryDirectory),
                clock = Clock.System,
            ),
        housekeeping = housekeepingFor(paths),
    )

/** Every table of the database, read the way a backup reads it. */
suspend fun everythingIn(database: AppDatabase): BackupData = BackupStore(database).snapshot().data

/** One fingerprint for the whole of the data: the backup's own checksum of it. */
fun fingerprintOf(data: BackupData): String = sha256Of(canonicalBackupDataJson(data).encodeToByteArray())

/** The journal mode and synchronous level of both of Room's kinds of connection, as one line. */
suspend fun durabilityOf(database: AppDatabase): Durability =
    Durability(
        writerJournalMode = database.useWriterConnection { it.usePrepared("PRAGMA journal_mode") { s -> firstText(s) } },
        writerSynchronous = database.useWriterConnection { it.usePrepared("PRAGMA synchronous") { s -> firstText(s) } }.toLong(),
        readerJournalMode = database.useReaderConnection { it.usePrepared("PRAGMA journal_mode") { s -> firstText(s) } },
        readerSynchronous = database.useReaderConnection { it.usePrepared("PRAGMA synchronous") { s -> firstText(s) } }.toLong(),
    )

private fun firstText(statement: SQLiteStatement): String {
    check(statement.step()) { "the PRAGMA returned no row" }
    return statement.getText(0)
}

/**
 * What `DatabaseFactory` gives both of Room's connections on this setup.
 *
 * Measured, not assumed: [DurabilityTest] opens a database the way the
 * application does and asks. The killed-process tests hold every child and every
 * reopened database to the same value, so a driver wrapper that quietly changed
 * the journal mode could not make them pass for the wrong reason.
 */
val PRODUCTION_DURABILITY = Durability(writerJournalMode = "wal", writerSynchronous = 1, readerJournalMode = "wal", readerSynchronous = 1)

/** What the connections Room hands out say about durability. */
data class Durability(
    val writerJournalMode: String,
    val writerSynchronous: Long,
    val readerJournalMode: String,
    val readerSynchronous: Long,
) {
    /** One token, so it can cross a process boundary on a single line. */
    fun encoded(): String = "$writerJournalMode/$writerSynchronous/$readerJournalMode/$readerSynchronous"

    companion object {
        fun decoded(text: String): Durability {
            val (writerMode, writerLevel, readerMode, readerLevel) = text.split("/")
            return Durability(writerMode, writerLevel.toLong(), readerMode, readerLevel.toLong())
        }
    }
}

// ------------------------------------------------------------------ fixtures

/** A small CSV-shaped import of [PREPARED_CELL_COUNT] 3D cells. */
fun aPreparedImport(): PreparedImportDraft =
    PreparedImportDraft(
        fileName = "liste.csv",
        sha256 = "a".repeat(64),
        sourceFormat = ImportSourceFormat.CSV,
        sheetName = "",
        sheetVisibility = SheetVisibility.VISIBLE,
        startRowIndex = 0,
        endRowIndex = PREPARED_CELL_COUNT - 1,
        startColumnIndex = 0,
        endColumnIndex = 0,
        blocks =
            (0 until PREPARED_CELL_COUNT).map { row ->
                PreparedRawBlock(
                    rowIndex = row,
                    columnIndex = 1,
                    sourceColumnType = SourceColumnType.THREE_D,
                    rawText = "${row + 2} KIRMIZI",
                    fillColorArgb = null,
                    gameCompletionHint = HintDecision.NONE,
                )
            },
    )

/** A game with a 3D cell to aim at; the cell's identity. */
suspend fun aGameWithACell(database: AppDatabase): EntityId {
    val game = aGame(name = "Harmonies")
    val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
    database.gameDao().insert(game)
    database.gameCellDao().insert(cell)
    return cell.id
}

/** The two colours every test uses, in catalogue order. */
suspend fun twoColours(database: AppDatabase): List<EntityId> =
    database
        .colorDao()
        .allColors()
        .sortedWith(compareBy({ it.sortOrder }, { it.id.toString() }))
        .take(2)
        .map { it.id }

/**
 * One saved draft import with one draft cut out of its first cell, aimed, and
 * ready to confirm — built through the real stores, the way a person builds one.
 */
suspend fun aReadyDraftImport(database: AppDatabase): EntityId {
    val cellId = aGameWithACell(database)
    val batchId = ImportDraftStore(database.importDao()).save(aPreparedImport()).batchId
    val review = ImportReviewStore(database.importDao(), database.gameDao(), database.colorDao())
    val block = database.importDao().rawBlocksOfBatch(batchId).first()
    val draftId = review.createDraftFromSelection(block.id, 0, block.rawText.length)
    review.saveDraft(
        aDraftEdit(draftId = draftId, cellId = cellId, name = "Kırmızı figür", quantity = 3, colours = twoColours(database).take(1)),
    )
    return batchId
}

fun aDraftEdit(
    draftId: EntityId,
    cellId: EntityId,
    name: String,
    quantity: Int,
    colours: List<EntityId>,
): DraftEdit =
    DraftEdit(
        draftTaskId = draftId,
        name = name,
        targetCellId = cellId,
        poolType = PoolType.THREE_D,
        trackingMode = TrackingMode.THREE_D_BATCH,
        requiredQuantity = quantity,
        notes = null,
        isMissing = false,
        isBorrowed = false,
        needsInfo = false,
        needsClassification = false,
        completionHint = HintDecision.NONE,
        colorIds = colours,
    )
