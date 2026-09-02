package dev.pnptracker.data.database

import androidx.room3.useWriterConnection
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.search.PoolFilter
import dev.pnptracker.domain.search.filterPoolTasks
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A temporary database location owned by a single test, plus the small fixtures
 * the database tests build their rows from. None of this belongs in production
 * code.
 */
class TemporaryDatabaseDirectory {
    val root: Path = Files.createTempDirectory("pnp-tracker-db-test")

    val databaseFile: Path get() = root.resolve("pnp.db")

    /** Deletes only this directory, after proving it is the one this test created. */
    fun delete() {
        val systemTemporaryDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val absoluteRoot = root.toAbsolutePath().normalize()
        check(absoluteRoot.startsWith(systemTemporaryDirectory) && absoluteRoot != systemTemporaryDirectory) {
            "Refusing to delete $absoluteRoot: it is outside $systemTemporaryDirectory"
        }
        val realUserHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        check(!absoluteRoot.startsWith(realUserHome)) {
            "Refusing to delete $absoluteRoot: it is below the real user home"
        }
        Files.walk(absoluteRoot).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    /** Proves the test never wrote to the real application database. */
    fun assertRealApplicationDatabaseUntouched(existedBefore: Boolean) {
        assertTrue(
            Files.exists(realApplicationDatabaseFile()) == existedBefore,
            "the test changed whether ${realApplicationDatabaseFile()} exists",
        )
    }

    companion object {
        fun realApplicationDatabaseFile(): Path =
            Path
                .of(System.getProperty("user.home"))
                .resolve(".local/share/pnp-tracker/pnp.db")
    }
}

/** A clock that never moves, so rows written together can be compared exactly. */
class StoppedClock(
    private val fixed: Instant,
) : kotlin.time.Clock {
    override fun now(): Instant = fixed
}

const val EPOCH_MILLISECONDS_CREATED = 1_700_000_000_000L
const val EPOCH_MILLISECONDS_UPDATED = 1_700_000_600_000L
const val EPOCH_MILLISECONDS_DELETED = 1_700_001_200_000L

val createdAt: Instant = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_CREATED)
val updatedAt: Instant = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_UPDATED)
val deletedAt: Instant = Instant.fromEpochMilliseconds(EPOCH_MILLISECONDS_DELETED)

fun aGame(
    id: EntityId = IdGenerator.Random.newId(),
    name: String = "Harmonies",
    isManuallyCompleted: Boolean = false,
    completedAt: Instant? = null,
): GameEntity =
    GameEntity(
        id = id,
        name = name,
        isManuallyCompleted = isManuallyCompleted,
        completedAt = completedAt,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

fun aCell(
    gameId: EntityId,
    id: EntityId = IdGenerator.Random.newId(),
    columnType: CellColumnType = CellColumnType.THREE_D,
): GameCellEntity =
    GameCellEntity(
        id = id,
        gameId = gameId,
        columnType = columnType,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

fun aTask(
    id: EntityId = IdGenerator.Random.newId(),
    poolType: PoolType = PoolType.THREE_D,
    trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
    name: String = "Gri token",
    requiredQuantity: Int? = 14,
): TaskEntity =
    TaskEntity(
        id = id,
        poolType = poolType,
        trackingMode = trackingMode,
        name = name,
        requiredQuantity = requiredQuantity,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

/** Inserts a game, a cell in it and a task written in that cell, returning the task. */
suspend fun insertGameCellAndTask(
    database: AppDatabase,
    gameName: String = "Harmonies",
    columnType: CellColumnType = CellColumnType.THREE_D,
    task: () -> TaskEntity = { aTask() },
): TaskEntity {
    val game = aGame(name = gameName)
    val cell = aCell(gameId = game.id, columnType = columnType)
    val created = task()
    database.gameDao().insert(game)
    database.gameCellDao().insert(cell)
    database.taskDao().addTaskToCell(
        task = created,
        cellId = cell.id,
        segmentId = IdGenerator.Random.newId(),
        moment = createdAt,
    )
    return created
}

/** Inserts a game and one cell in it, returning the cell. */
suspend fun insertGameAndCell(
    database: AppDatabase,
    gameName: String = "Harmonies",
    columnType: CellColumnType = CellColumnType.THREE_D,
): GameCellEntity {
    val game = aGame(name = gameName)
    val cell = aCell(gameId = game.id, columnType = columnType)
    database.gameDao().insert(game)
    database.gameCellDao().insert(cell)
    return cell
}

const val SHA_256_ONE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
const val SHA_256_TWO = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

fun anImportBatch(
    id: EntityId = IdGenerator.Random.newId(),
    fileName: String = "Kitap.xlsx",
    sha256: String = SHA_256_ONE,
    sourceFormat: ImportSourceFormat = ImportSourceFormat.XLSX,
    sheetName: String = "Sayfa1",
    startRowIndex: Int? = 0,
    endRowIndex: Int? = 4,
    startColumnIndex: Int? = 0,
    endColumnIndex: Int? = 6,
    createdGameCount: Int = 0,
    rawBlockCount: Int = 0,
    createdTaskCount: Int = 0,
    status: dev.pnptracker.domain.model.ImportBatchStatus = dev.pnptracker.domain.model.ImportBatchStatus.DRAFT,
): ImportBatchEntity =
    ImportBatchEntity(
        id = id,
        fileName = fileName,
        sha256 = sha256,
        sourceFormat = sourceFormat,
        sheetName = sheetName,
        startRowIndex = startRowIndex,
        endRowIndex = endRowIndex,
        startColumnIndex = startColumnIndex,
        endColumnIndex = endColumnIndex,
        createdGameCount = createdGameCount,
        rawBlockCount = rawBlockCount,
        createdTaskCount = createdTaskCount,
        status = status,
        importedAt = createdAt,
        updatedAt = createdAt,
    )

fun aRawImportBlock(
    importBatchId: EntityId,
    id: EntityId = IdGenerator.Random.newId(),
    rawText: String = "15 KIRMIZI** 19 YEŞİL**",
    sheetName: String = "Sayfa1",
    rowIndex: Int = 1,
    columnIndex: Int = 1,
    sourceColumnType: SourceColumnType = SourceColumnType.THREE_D,
    fillColorArgb: Int? = null,
): RawImportBlockEntity =
    RawImportBlockEntity(
        id = id,
        importBatchId = importBatchId,
        rawText = rawText,
        sheetName = sheetName,
        rowIndex = rowIndex,
        columnIndex = columnIndex,
        sourceColumnType = sourceColumnType,
        fillColorArgb = fillColorArgb,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

fun aDraftTask(
    rawImportBlockId: EntityId,
    id: EntityId = IdGenerator.Random.newId(),
    name: String = "Kırmızı token",
    selectionStartIndex: Int? = null,
    selectionEndIndex: Int? = null,
): DraftTaskEntity =
    DraftTaskEntity(
        id = id,
        rawImportBlockId = rawImportBlockId,
        name = name,
        selectionStartIndex = selectionStartIndex,
        selectionEndIndex = selectionEndIndex,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

/**
 * Writes a cell piece straight into the table, for building fixtures.
 *
 * The production path writes pieces only through the one transaction that knows
 * what a cell may look like afterwards, so its insert is not reachable from
 * outside the DAO. A test that needs a cell in a shape the editor would never
 * produce — several adjacent pieces, say — writes it here instead, which keeps
 * that ability in the tests rather than opening it up in production.
 */
suspend fun insertSegmentDirectly(
    database: AppDatabase,
    segment: CellSegmentEntity,
) {
    database.useWriterConnection { transactor ->
        transactor.usePrepared(
            "INSERT INTO cell_segments (id, cell_id, order_index, kind, text, task_id, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ) { statement ->
            statement.bindText(1, segment.id.toString())
            statement.bindText(2, segment.cellId.toString())
            statement.bindLong(3, segment.orderIndex.toLong())
            statement.bindText(4, segment.kind.name)
            segment.text?.let { statement.bindText(5, it) } ?: statement.bindNull(5)
            segment.taskId?.let { statement.bindText(6, it.toString()) } ?: statement.bindNull(6)
            statement.bindLong(7, segment.createdAt.toEpochMilliseconds())
            statement.bindLong(8, segment.updatedAt.toEpochMilliseconds())
            statement.step()
        }
    }
}

/**
 * The tasks a pool screen shows when nothing has been filtered.
 *
 * The pool's reads return every task of the pool — the work still to do, the
 * work finished, and the work nobody can start yet — because PLAN 13 lets the
 * user ask for any of the three and PLAN 16 will not have a query per answer.
 * Which of them is on screen is decided in memory by [PoolFilter], and its
 * default is the active work.
 *
 * So a test asking what is "in the pool" asks through the same arithmetic the
 * screen does. Reading `snapshot.tasks` straight would be asking what the
 * database returned, which is a different and much wider question.
 */
fun activePoolTasks(snapshot: PoolSnapshot): List<PoolTask> = filterPoolTasks(snapshot.tasks, PoolFilter.NONE)
