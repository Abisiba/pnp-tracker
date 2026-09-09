package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BackupCellSegmentRow
import dev.pnptracker.domain.backup.BackupColorAliasRow
import dev.pnptracker.domain.backup.BackupColorRow
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupDraftTaskColorRow
import dev.pnptracker.domain.backup.BackupDraftTaskRow
import dev.pnptracker.domain.backup.BackupGameCellRow
import dev.pnptracker.domain.backup.BackupGameRow
import dev.pnptracker.domain.backup.BackupHistoryEventRow
import dev.pnptracker.domain.backup.BackupImportBatchCellRow
import dev.pnptracker.domain.backup.BackupImportBatchRow
import dev.pnptracker.domain.backup.BackupProgressEventRow
import dev.pnptracker.domain.backup.BackupRawImportBlockRow
import dev.pnptracker.domain.backup.BackupTaskColorRow
import dev.pnptracker.domain.backup.BackupTaskRow
import dev.pnptracker.domain.backup.BackupTaskStageRow
import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.backup.sha256Of
import kotlin.time.Instant

/*
 * The material the reading tests are made of: one backup that is right in every
 * way, and the machinery to make one that is wrong in exactly one way.
 *
 * Built as records and written out by the real writer rather than typed as JSON
 * by hand. A document typed out by hand would carry its own checksum, its own
 * field order and its own idea of the format, and every test using it would be
 * testing that idea rather than the one the application writes.
 */

const val COLOR = "aa000000-0000-4000-8000-000000000001"
const val BATCH = "bb000000-0000-4000-8000-000000000001"
const val GAME = "cc000000-0000-4000-8000-000000000001"
const val CELL = "dd000000-0000-4000-8000-000000000001"
const val BLOCK = "ee000000-0000-4000-8000-000000000001"
const val TASK_PRINTED = "f0000000-0000-4000-8000-000000000001"
const val TASK_PIPELINE = "f0000000-0000-4000-8000-000000000002"
const val SEGMENT_TEXT = "10000000-0000-4000-8000-000000000001"
const val SEGMENT_PRINTED = "10000000-0000-4000-8000-000000000002"
const val SEGMENT_PIPELINE = "10000000-0000-4000-8000-000000000003"
const val PROGRESS = "20000000-0000-4000-8000-000000000001"
const val HISTORY_TASK = "30000000-0000-4000-8000-000000000001"
const val HISTORY_GAME = "30000000-0000-4000-8000-000000000002"
const val DRAFT = "40000000-0000-4000-8000-000000000001"

const val CREATED = 1_700_000_000_000L
const val UPDATED = 1_700_000_600_000L

/** A moment written the way the format writes one. */
const val WRITTEN_AT = "2026-09-08T09:15:00Z"

val moment: Instant = Instant.parse(WRITTEN_AT)

/**
 * A backup with a row in all fifteen tables and nothing wrong with it.
 *
 * Rows are already in the order PLAN 14.4.2 sorts them by, so this is what the
 * writer would have produced from a database holding exactly this — which
 * matters to any test that puts it into a database and reads it back.
 */
fun aWholeBackup(): BackupData =
    BackupData(
        colors =
            listOf(
                BackupColorRow(
                    id = COLOR,
                    canonicalName = "Kırmızı",
                    normalizedName = "kirmizi",
                    hex = "#FF0000",
                    sortOrder = 0,
                ),
            ),
        colorAliases = listOf(BackupColorAliasRow(colorId = COLOR, alias = "KIRMIZI", normalizedAlias = "kirmizi")),
        importBatches =
            listOf(
                BackupImportBatchRow(
                    id = BATCH,
                    fileName = "oyunlar.xlsx",
                    sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    sourceFormat = "XLSX",
                    sheetName = "Sayfa1",
                    startRowIndex = 0,
                    endRowIndex = 4,
                    startColumnIndex = 0,
                    endColumnIndex = 6,
                    createdGameCount = 1,
                    rawBlockCount = 1,
                    createdTaskCount = 2,
                    status = "CONFIRMED",
                    importedAt = CREATED,
                    updatedAt = UPDATED,
                ),
            ),
        games =
            listOf(
                BackupGameRow(
                    id = GAME,
                    name = "Harmonies",
                    isManuallyCompleted = false,
                    completedAt = null,
                    createdAt = CREATED,
                    updatedAt = UPDATED,
                    deletedAt = null,
                    sourceImportBatchId = BATCH,
                ),
            ),
        gameCells =
            listOf(
                BackupGameCellRow(
                    id = CELL,
                    gameId = GAME,
                    columnType = "THREE_D",
                    createdAt = CREATED,
                    updatedAt = UPDATED,
                ),
            ),
        rawImportBlocks =
            listOf(
                BackupRawImportBlockRow(
                    id = BLOCK,
                    importBatchId = BATCH,
                    rawText = "15 KIRMIZI**",
                    sheetName = "Sayfa1",
                    rowIndex = 1,
                    columnIndex = 1,
                    sourceColumnType = "THREE_D",
                    fillColorArgb = null,
                    gameCompletionHint = "NONE",
                    completionTargetGameId = null,
                    isProcessed = true,
                    createdAt = CREATED,
                    updatedAt = UPDATED,
                ),
            ),
        tasks =
            listOf(
                aTaskRow(id = TASK_PRINTED, pool = "THREE_D", mode = "THREE_D_BATCH", sourceBlock = BLOCK),
                aTaskRow(id = TASK_PIPELINE, pool = "CARD", mode = "PIPELINE", sourceBlock = null),
            ),
        cellSegments =
            listOf(
                aSegment(SEGMENT_TEXT, order = 0, kind = "PLAIN_TEXT", text = "Notum", taskId = null),
                aSegment(SEGMENT_PRINTED, order = 1, kind = "TASK", text = null, taskId = TASK_PRINTED),
                aSegment(SEGMENT_PIPELINE, order = 2, kind = "TASK", text = null, taskId = TASK_PIPELINE),
            ),
        taskColors = listOf(BackupTaskColorRow(taskId = TASK_PRINTED, colorId = COLOR, slotIndex = 0)),
        taskStages =
            listOf("PRINT" to 10, "LAMINATE" to 4, "CUT" to 0).mapIndexed { order, (stage, done) ->
                BackupTaskStageRow(
                    taskId = TASK_PIPELINE,
                    stage = stage,
                    orderIndex = order,
                    completedQuantity = done,
                    createdAt = CREATED,
                    updatedAt = UPDATED,
                )
            },
        progressEvents =
            listOf(
                BackupProgressEventRow(
                    id = PROGRESS,
                    taskId = TASK_PRINTED,
                    kind = "FAILURE_REPORTED",
                    quantity = 2,
                    note = "kenarları bozuldu",
                    cardReference = null,
                    stage = null,
                    recordedAt = UPDATED,
                ),
            ),
        historyEvents =
            listOf(
                BackupHistoryEventRow(
                    id = HISTORY_TASK,
                    kind = "TASK_COMPLETED",
                    occurredAt = UPDATED,
                    gameId = GAME,
                    taskId = TASK_PIPELINE,
                    stage = null,
                    previousQuantity = null,
                    newQuantity = null,
                ),
                BackupHistoryEventRow(
                    id = HISTORY_GAME,
                    kind = "IMPORT_CONFIRMED",
                    occurredAt = UPDATED,
                    gameId = GAME,
                    taskId = null,
                    stage = null,
                    previousQuantity = null,
                    newQuantity = null,
                ),
            ),
        importBatchCells =
            listOf(BackupImportBatchCellRow(importBatchId = BATCH, cellId = CELL, documentBefore = "  önceki metin  ")),
        draftTasks =
            listOf(
                BackupDraftTaskRow(
                    id = DRAFT,
                    rawImportBlockId = BLOCK,
                    name = "Kırmızı token",
                    suggestedPoolType = "THREE_D",
                    selectedPoolType = "THREE_D",
                    selectedTrackingMode = "THREE_D_BATCH",
                    requiredQuantity = 15,
                    notes = null,
                    targetCellId = CELL,
                    selectionStartIndex = 3,
                    selectionEndIndex = 11,
                    completionHint = "NONE",
                    isMissing = false,
                    isBorrowed = false,
                    needsInfo = false,
                    needsClassification = false,
                    materializedTaskId = TASK_PRINTED,
                    createdAt = CREATED,
                    updatedAt = UPDATED,
                ),
            ),
        draftTaskColors = listOf(BackupDraftTaskColorRow(draftTaskId = DRAFT, colorId = COLOR, slotIndex = 0)),
    )

fun aTaskRow(
    id: String,
    pool: String,
    mode: String,
    sourceBlock: String?,
): BackupTaskRow =
    BackupTaskRow(
        id = id,
        poolType = pool,
        trackingMode = mode,
        name = "Kılıç",
        requiredQuantity = 15,
        notes = null,
        isCompleted = false,
        completedAt = null,
        primaryBatchCompleted = false,
        currentMissingQuantity = 0,
        createdAt = CREATED,
        updatedAt = UPDATED,
        deletedAt = null,
        sourceRawImportBlockId = sourceBlock,
        isMissing = false,
        isBorrowed = false,
        needsInfo = false,
        needsClassification = false,
    )

fun aSegment(
    id: String,
    order: Int,
    kind: String,
    text: String?,
    taskId: String?,
): BackupCellSegmentRow =
    BackupCellSegmentRow(
        id = id,
        cellId = CELL,
        orderIndex = order,
        kind = kind,
        text = text,
        taskId = taskId,
        createdAt = CREATED,
        updatedAt = UPDATED,
    )

/** The whole document, written by the real writer, checksum and all. */
fun documentOf(data: BackupData = aWholeBackup()): String =
    backupDocumentOf(data = data, appVersion = "0.1.0", sourceSchemaVersion = 8, createdAt = moment).json

/** The checksum the format defines for [data]: its canonical bytes, hashed once. */
fun canonicalChecksumOf(data: BackupData): String = sha256Of(canonicalBackupDataJson(data).encodeToByteArray())

/** A file that holds [text], with the size the file system would report for it. */
fun fileOf(text: String): FakeBackupInput = FakeBackupInput(text.encodeToByteArray())

/**
 * A backup file that is whatever a test needs it to be.
 *
 * The declared size can be made to lie, because the file system's answer is a
 * hint and the reader is required not to depend on it, and the stream counts how
 * often it was opened and whether it was closed, because "the file was closed
 * however the reading ended" is not something one can see from the outside.
 */
class FakeBackupInput(
    private val bytes: ByteArray,
    private val declared: Long? = null,
    private val failToOpen: Boolean = false,
    private val failAfter: Int = -1,
    override val fileName: String = "pnp-yedek-2026-09-08.json",
) : BackupInput {
    var opened = 0
        private set
    var closed = 0
        private set

    override suspend fun declaredSize(): Long = declared ?: bytes.size.toLong()

    override suspend fun open(): BackupBytes {
        if (failToOpen) throw BackupInputException()
        opened++
        return Stream()
    }

    private inner class Stream : BackupBytes {
        private var at = 0

        override suspend fun read(into: ByteArray): Int {
            if (failAfter in 0..at) throw BackupInputException()
            if (at >= bytes.size) return -1
            val count = minOf(into.size, bytes.size - at)
            bytes.copyInto(into, destinationOffset = 0, startIndex = at, endIndex = at + count)
            at += count
            return count
        }

        override suspend fun close() {
            closed++
        }
    }
}

/** A probe that says yes to everything, and remembers whether it was asked. */
class CountingProbe(
    private val answer: BackupRejection? = null,
) : BackupProbe {
    var asked = 0
        private set

    override suspend fun probe(
        data: BackupData,
        dataSha256: String,
    ): BackupRejection? {
        asked++
        return answer
    }
}

/**
 * A backup of a database with nothing in it.
 *
 * Which is a real thing to have: somebody who has just installed the
 * application, opened it once and taken a backup before adding anything. It also
 * happens to be the only way to see whether a restore empties what it finds — a
 * database Room has just made already holds twelve colours, so a probe of this
 * coming back with twelve of anything would be a merge rather than a replacement.
 */
fun anEmptyBackup(): BackupData =
    BackupData(
        colors = emptyList(),
        colorAliases = emptyList(),
        importBatches = emptyList(),
        games = emptyList(),
        gameCells = emptyList(),
        rawImportBlocks = emptyList(),
        tasks = emptyList(),
        cellSegments = emptyList(),
        taskColors = emptyList(),
        taskStages = emptyList(),
        progressEvents = emptyList(),
        historyEvents = emptyList(),
        importBatchCells = emptyList(),
        draftTasks = emptyList(),
        draftTaskColors = emptyList(),
    )
