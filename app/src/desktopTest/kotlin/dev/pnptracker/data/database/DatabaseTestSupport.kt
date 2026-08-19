package dev.pnptracker.data.database

import dev.pnptracker.data.database.entity.DraftTaskEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.entity.ImportBatchEntity
import dev.pnptracker.data.database.entity.ItemEntity
import dev.pnptracker.data.database.entity.RawImportBlockEntity
import dev.pnptracker.data.database.entity.TaskEntity
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
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
    notes: String? = null,
    isManuallyCompleted: Boolean = false,
    completedAt: Instant? = null,
): GameEntity =
    GameEntity(
        id = id,
        name = name,
        notes = notes,
        isManuallyCompleted = isManuallyCompleted,
        completedAt = completedAt,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

fun anItem(
    gameId: EntityId,
    id: EntityId = IdGenerator.Random.newId(),
    name: String = "Token",
    notes: String? = null,
): ItemEntity =
    ItemEntity(
        id = id,
        gameId = gameId,
        name = name,
        notes = notes,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

fun aTask(
    itemId: EntityId,
    id: EntityId = IdGenerator.Random.newId(),
    poolType: PoolType = PoolType.THREE_D,
    trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
    name: String = "Gri token",
    requiredQuantity: Int? = 14,
): TaskEntity =
    TaskEntity(
        id = id,
        itemId = itemId,
        poolType = poolType,
        trackingMode = trackingMode,
        name = name,
        requiredQuantity = requiredQuantity,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

/** Inserts a game, an item below it and a task below that, returning the task. */
suspend fun insertGameItemAndTask(
    database: AppDatabase,
    gameName: String = "Harmonies",
    itemName: String = "Token",
    task: (EntityId) -> TaskEntity = { itemId -> aTask(itemId) },
): TaskEntity {
    val game = aGame(name = gameName)
    val item = anItem(gameId = game.id, name = itemName)
    val created = task(item.id)
    database.gameDao().insert(game)
    database.itemDao().insert(item)
    database.taskDao().insert(created)
    return created
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
