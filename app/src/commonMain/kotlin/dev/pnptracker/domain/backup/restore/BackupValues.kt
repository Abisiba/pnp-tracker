package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.ProgressEventKind
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.rules.isTrackingModeAllowed
import dev.pnptracker.domain.rules.isValidColorHex
import dev.pnptracker.domain.rules.normalizeColorTerm

/*
 * Every row, against the rules its own table lives under.
 *
 * The document has already been shown to be JSON of the right shape by the time
 * this runs, so nothing here is about types. This is about values: an identifier
 * that is not a UUID, an enum name that no longer exists, a count below nothing,
 * a moment before the epoch or long after anybody will be using this, a pair of
 * fields that contradict each other.
 *
 * The rules are the ones the entities already state in their `init` blocks, said
 * again here rather than found out by trying to build the entities. Two reasons.
 * A failed `require` is an `IllegalArgumentException` whose message quotes the
 * offending value, which is the user's data and may not travel (PLAN 14.4.5).
 * And a backup is refused for saying something wrong, which is a different fact
 * about the world from a piece of code being wrong — the second must never be
 * dressed up as the first (PLAN 14.4.5 again). The entities still get the last
 * word: the temporary database reads every row back through them, so a rule
 * missed here is caught there rather than let through.
 *
 * Nothing is repaired. PLAN 14.4.2 has values travel exactly as they are, and a
 * reader that trimmed a name or clamped a count would hand back data the user
 * never had while claiming to have restored their backup.
 */

private val poolTypes = PoolType.entries.associateBy { it.name }
private val trackingModes = TrackingMode.entries.associateBy { it.name }
private val segmentKinds = SegmentKind.entries.associateBy { it.name }
private val columnTypes = CellColumnType.entries.associateBy { it.name }
private val sourceColumnTypes = SourceColumnType.entries.associateBy { it.name }
private val hintDecisions = HintDecision.entries.associateBy { it.name }
private val importStatuses = ImportBatchStatus.entries.associateBy { it.name }
private val sourceFormats = ImportSourceFormat.entries.associateBy { it.name }
private val progressKinds = ProgressEventKind.entries.associateBy { it.name }
private val historyKinds = HistoryEventKind.entries.associateBy { it.name }
private val stages = ProductionStage.entries.associateBy { it.name }

/** Checks every row of every table, and stops at the first one that is wrong. */
internal fun checkValues(data: BackupData): BackupRejection? {
    checkColors(data)?.let { return it }
    checkImports(data)?.let { return it }
    checkGames(data)?.let { return it }
    checkTasks(data)?.let { return it }
    checkCellDocuments(data)?.let { return it }
    checkEvents(data)?.let { return it }
    checkDrafts(data)?.let { return it }
    return null
}

private fun checkColors(data: BackupData): BackupRejection? {
    data.colors.forEach { row ->
        val place = BackupPlace("colors")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (row.canonicalName.isBlank()) return place.bad(BackupProblem.INVALID_VALUE, "canonicalName")
        // The normalised name is what colour matching looks a term up by, so a
        // row whose two names disagree would answer to something it is not
        // called (PLAN 5.11).
        if (row.normalizedName != normalizeColorTerm(row.canonicalName)) {
            return place.bad(BackupProblem.INVALID_VALUE, "normalizedName")
        }
        if (!isValidColorHex(row.hex)) return place.bad(BackupProblem.INVALID_VALUE, "hex")
    }
    data.colorAliases.forEach { row ->
        val place = BackupPlace("colorAliases")
        if (!isCanonicalUuid(row.colorId)) return place.bad(BackupProblem.INVALID_ID, "colorId")
        if (row.alias.isBlank()) return place.bad(BackupProblem.INVALID_VALUE, "alias")
        if (row.normalizedAlias != normalizeColorTerm(row.alias)) {
            return place.bad(BackupProblem.INVALID_VALUE, "normalizedAlias")
        }
    }
    return null
}

private fun checkImports(data: BackupData): BackupRejection? {
    data.importBatches.forEach { row ->
        val place = BackupPlace("importBatches")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (row.fileName.isBlank()) return place.bad(BackupProblem.INVALID_VALUE, "fileName")
        if (!LOWER_CASE_SHA_256.matches(row.sha256)) return place.bad(BackupProblem.INVALID_VALUE, "sha256")
        if (row.sourceFormat !in sourceFormats) return place.bad(BackupProblem.INVALID_ENUM, "sourceFormat")
        if (row.status !in importStatuses) return place.bad(BackupProblem.INVALID_ENUM, "status")
        if (row.createdGameCount < 0) return place.bad(BackupProblem.INVALID_VALUE, "createdGameCount")
        if (row.rawBlockCount < 0) return place.bad(BackupProblem.INVALID_VALUE, "rawBlockCount")
        if (row.createdTaskCount < 0) return place.bad(BackupProblem.INVALID_VALUE, "createdTaskCount")
        if (!isMoment(row.importedAt)) return place.bad(BackupProblem.INVALID_VALUE, "importedAt")
        // Only that it is a moment: a confirmation or a rollback on a clock that
        // had gone back writes an updatedAt before importedAt, and that is the
        // application's own data (PLAN 14.7.3).
        if (!isMoment(row.updatedAt)) return place.bad(BackupProblem.INVALID_VALUE, "updatedAt")

        val bounds = listOf(row.startRowIndex, row.endRowIndex, row.startColumnIndex, row.endColumnIndex)
        if (bounds.any { it == null } && bounds.any { it != null }) {
            return place.bad(BackupProblem.DOMAIN_INVARIANT, "startRowIndex")
        }
        if (bounds.all { it != null }) {
            if (row.startRowIndex!! < 0 || row.startColumnIndex!! < 0) {
                return place.bad(BackupProblem.INVALID_VALUE, "startRowIndex")
            }
            if (row.startRowIndex > row.endRowIndex!!) return place.bad(BackupProblem.DOMAIN_INVARIANT, "endRowIndex")
            if (row.startColumnIndex > row.endColumnIndex!!) {
                return place.bad(BackupProblem.DOMAIN_INVARIANT, "endColumnIndex")
            }
        }
    }
    data.rawImportBlocks.forEach { row ->
        val place = BackupPlace("rawImportBlocks")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (!isCanonicalUuid(row.importBatchId)) return place.bad(BackupProblem.INVALID_ID, "importBatchId")
        if (row.completionTargetGameId != null && !isCanonicalUuid(row.completionTargetGameId)) {
            return place.bad(BackupProblem.INVALID_ID, "completionTargetGameId")
        }
        if (row.sourceColumnType !in sourceColumnTypes) return place.bad(BackupProblem.INVALID_ENUM, "sourceColumnType")
        val hint = hintDecisions[row.gameCompletionHint] ?: return place.bad(BackupProblem.INVALID_ENUM, "gameCompletionHint")
        if (row.rowIndex < 0) return place.bad(BackupProblem.INVALID_VALUE, "rowIndex")
        if (row.columnIndex < 0) return place.bad(BackupProblem.INVALID_VALUE, "columnIndex")
        // A game named as the answer to a question nobody accepted is not an
        // answer at all.
        if (row.completionTargetGameId != null && hint != HintDecision.ACCEPTED) {
            return place.bad(BackupProblem.DOMAIN_INVARIANT, "completionTargetGameId")
        }
        checkWrittenAndChanged(place, row.createdAt, row.updatedAt)?.let { return it }
    }
    data.importBatchCells.forEach { row ->
        val place = BackupPlace("importBatchCells")
        if (!isCanonicalUuid(row.importBatchId)) return place.bad(BackupProblem.INVALID_ID, "importBatchId")
        if (!isCanonicalUuid(row.cellId)) return place.bad(BackupProblem.INVALID_ID, "cellId")
    }
    return null
}

private fun checkGames(data: BackupData): BackupRejection? {
    data.games.forEach { row ->
        val place = BackupPlace("games")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (row.sourceImportBatchId != null && !isCanonicalUuid(row.sourceImportBatchId)) {
            return place.bad(BackupProblem.INVALID_ID, "sourceImportBatchId")
        }
        if (row.name.isBlank()) return place.bad(BackupProblem.INVALID_VALUE, "name")
        if (!isMoment(row.completedAt)) return place.bad(BackupProblem.INVALID_VALUE, "completedAt")
        if (!isMoment(row.deletedAt)) return place.bad(BackupProblem.INVALID_VALUE, "deletedAt")
        checkWrittenAndChanged(place, row.createdAt, row.updatedAt)?.let { return it }
    }
    data.gameCells.forEach { row ->
        val place = BackupPlace("gameCells")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (!isCanonicalUuid(row.gameId)) return place.bad(BackupProblem.INVALID_ID, "gameId")
        if (row.columnType !in columnTypes) return place.bad(BackupProblem.INVALID_ENUM, "columnType")
        checkWrittenAndChanged(place, row.createdAt, row.updatedAt)?.let { return it }
    }
    return null
}

private fun checkTasks(data: BackupData): BackupRejection? {
    data.tasks.forEach { row ->
        val place = BackupPlace("tasks")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (row.sourceRawImportBlockId != null && !isCanonicalUuid(row.sourceRawImportBlockId)) {
            return place.bad(BackupProblem.INVALID_ID, "sourceRawImportBlockId")
        }
        val pool = poolTypes[row.poolType] ?: return place.bad(BackupProblem.INVALID_ENUM, "poolType")
        val mode = trackingModes[row.trackingMode] ?: return place.bad(BackupProblem.INVALID_ENUM, "trackingMode")
        if (row.name.isBlank()) return place.bad(BackupProblem.INVALID_VALUE, "name")
        if (row.requiredQuantity != null && row.requiredQuantity <= 0) {
            return place.bad(BackupProblem.INVALID_VALUE, "requiredQuantity")
        }
        if (row.currentMissingQuantity < 0) return place.bad(BackupProblem.INVALID_VALUE, "currentMissingQuantity")
        if (!isMoment(row.completedAt)) return place.bad(BackupProblem.INVALID_VALUE, "completedAt")
        if (!isMoment(row.deletedAt)) return place.bad(BackupProblem.INVALID_VALUE, "deletedAt")
        checkWrittenAndChanged(place, row.createdAt, row.updatedAt)?.let { return it }

        // PLAN 3.4: a pool tracks its work one way, and a task claiming another
        // way could not have been made by this application.
        if (!isTrackingModeAllowed(pool, mode)) return place.bad(BackupProblem.DOMAIN_INVARIANT, "trackingMode")
        // PLAN 6.4: the flag and the moment say the same thing or the row is not
        // a valid one.
        if (row.isCompleted != (row.completedAt != null)) return place.bad(BackupProblem.DOMAIN_INVARIANT, "completedAt")
        if (row.requiredQuantity != null && row.currentMissingQuantity > row.requiredQuantity) {
            return place.bad(BackupProblem.DOMAIN_INVARIANT, "currentMissingQuantity")
        }
        // PLAN 10 gives missing and borrowed a column each, and a cell is in one.
        if (row.isMissing && row.isBorrowed) return place.bad(BackupProblem.DOMAIN_INVARIANT, "isBorrowed")
    }
    return null
}

private fun checkCellDocuments(data: BackupData): BackupRejection? {
    data.cellSegments.forEach { row ->
        val place = BackupPlace("cellSegments")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (!isCanonicalUuid(row.cellId)) return place.bad(BackupProblem.INVALID_ID, "cellId")
        if (row.taskId != null && !isCanonicalUuid(row.taskId)) return place.bad(BackupProblem.INVALID_ID, "taskId")
        val kind = segmentKinds[row.kind] ?: return place.bad(BackupProblem.INVALID_ENUM, "kind")
        if (row.orderIndex < 0) return place.bad(BackupProblem.INVALID_VALUE, "orderIndex")
        checkWrittenAndChanged(place, row.createdAt, row.updatedAt)?.let { return it }

        // PLAN 5.5: a piece is words or it is a task standing in for words, and
        // the two carry different fields. Anything else is a cell document that
        // cannot be read.
        when (kind) {
            SegmentKind.PLAIN_TEXT -> {
                if (row.text.isNullOrEmpty()) return place.bad(BackupProblem.DOMAIN_INVARIANT, "text")
                if (row.taskId != null) return place.bad(BackupProblem.DOMAIN_INVARIANT, "taskId")
            }

            SegmentKind.TASK -> {
                if (row.taskId == null) return place.bad(BackupProblem.DOMAIN_INVARIANT, "taskId")
                if (row.text != null) return place.bad(BackupProblem.DOMAIN_INVARIANT, "text")
            }
        }
    }
    data.taskColors.forEach { row ->
        val place = BackupPlace("taskColors")
        if (!isCanonicalUuid(row.taskId)) return place.bad(BackupProblem.INVALID_ID, "taskId")
        if (!isCanonicalUuid(row.colorId)) return place.bad(BackupProblem.INVALID_ID, "colorId")
        if (row.slotIndex < 0) return place.bad(BackupProblem.INVALID_VALUE, "slotIndex")
    }
    data.taskStages.forEach { row ->
        val place = BackupPlace("taskStages")
        if (!isCanonicalUuid(row.taskId)) return place.bad(BackupProblem.INVALID_ID, "taskId")
        if (row.stage !in stages) return place.bad(BackupProblem.INVALID_ENUM, "stage")
        if (row.orderIndex < 0) return place.bad(BackupProblem.INVALID_VALUE, "orderIndex")
        if (row.completedQuantity < 0) return place.bad(BackupProblem.INVALID_VALUE, "completedQuantity")
        checkWrittenAndChanged(place, row.createdAt, row.updatedAt)?.let { return it }
    }
    return null
}

private fun checkEvents(data: BackupData): BackupRejection? {
    data.progressEvents.forEach { row ->
        val place = BackupPlace("progressEvents")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (!isCanonicalUuid(row.taskId)) return place.bad(BackupProblem.INVALID_ID, "taskId")
        if (row.kind !in progressKinds) return place.bad(BackupProblem.INVALID_ENUM, "kind")
        if (row.stage != null && row.stage !in stages) return place.bad(BackupProblem.INVALID_ENUM, "stage")
        // PLAN 5.12: an event is about at least one piece, or it is not an event.
        if (row.quantity <= 0) return place.bad(BackupProblem.INVALID_VALUE, "quantity")
        if (row.note != null && row.note.isBlank()) return place.bad(BackupProblem.INVALID_VALUE, "note")
        if (row.cardReference != null && row.cardReference.isBlank()) {
            return place.bad(BackupProblem.INVALID_VALUE, "cardReference")
        }
        if (!isMoment(row.recordedAt)) return place.bad(BackupProblem.INVALID_VALUE, "recordedAt")
    }
    data.historyEvents.forEach { row ->
        val place = BackupPlace("historyEvents")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (!isCanonicalUuid(row.gameId)) return place.bad(BackupProblem.INVALID_ID, "gameId")
        if (row.taskId != null && !isCanonicalUuid(row.taskId)) return place.bad(BackupProblem.INVALID_ID, "taskId")
        val kind = historyKinds[row.kind] ?: return place.bad(BackupProblem.INVALID_ENUM, "kind")
        if (row.stage != null && row.stage !in stages) return place.bad(BackupProblem.INVALID_ENUM, "stage")
        if (!isMoment(row.occurredAt)) return place.bad(BackupProblem.INVALID_VALUE, "occurredAt")

        // PLAN 12.15: what a line carries follows from what kind of line it is.
        if (kind.namesNoTask && row.taskId != null) return place.bad(BackupProblem.DOMAIN_INVARIANT, "taskId")
        if (!kind.namesNoTask && row.taskId == null) return place.bad(BackupProblem.DOMAIN_INVARIANT, "taskId")
        if (kind.carriesStageQuantities) {
            if (row.stage == null) return place.bad(BackupProblem.DOMAIN_INVARIANT, "stage")
            val before = row.previousQuantity ?: return place.bad(BackupProblem.DOMAIN_INVARIANT, "previousQuantity")
            val after = row.newQuantity ?: return place.bad(BackupProblem.DOMAIN_INVARIANT, "newQuantity")
            if (before < 0 || after < 0) return place.bad(BackupProblem.INVALID_VALUE, "previousQuantity")
            if (before == after) return place.bad(BackupProblem.DOMAIN_INVARIANT, "newQuantity")
        } else {
            if (row.stage != null) return place.bad(BackupProblem.DOMAIN_INVARIANT, "stage")
            if (row.previousQuantity != null) return place.bad(BackupProblem.DOMAIN_INVARIANT, "previousQuantity")
            if (row.newQuantity != null) return place.bad(BackupProblem.DOMAIN_INVARIANT, "newQuantity")
        }
    }
    return null
}

private fun checkDrafts(data: BackupData): BackupRejection? {
    data.draftTasks.forEach { row ->
        val place = BackupPlace("draftTasks")
        if (!isCanonicalUuid(row.id)) return place.bad(BackupProblem.INVALID_ID, "id")
        if (!isCanonicalUuid(row.rawImportBlockId)) return place.bad(BackupProblem.INVALID_ID, "rawImportBlockId")
        if (row.targetCellId != null && !isCanonicalUuid(row.targetCellId)) {
            return place.bad(BackupProblem.INVALID_ID, "targetCellId")
        }
        if (row.materializedTaskId != null && !isCanonicalUuid(row.materializedTaskId)) {
            return place.bad(BackupProblem.INVALID_ID, "materializedTaskId")
        }
        if (row.suggestedPoolType != null && row.suggestedPoolType !in poolTypes) {
            return place.bad(BackupProblem.INVALID_ENUM, "suggestedPoolType")
        }
        val pool = row.selectedPoolType?.let { poolTypes[it] ?: return place.bad(BackupProblem.INVALID_ENUM, "selectedPoolType") }
        val mode =
            row.selectedTrackingMode?.let {
                trackingModes[it] ?: return place.bad(BackupProblem.INVALID_ENUM, "selectedTrackingMode")
            }
        if (row.completionHint !in hintDecisions) return place.bad(BackupProblem.INVALID_ENUM, "completionHint")
        if (row.name.isBlank()) return place.bad(BackupProblem.INVALID_VALUE, "name")
        if (row.requiredQuantity != null && row.requiredQuantity <= 0) {
            return place.bad(BackupProblem.INVALID_VALUE, "requiredQuantity")
        }
        checkWrittenAndChanged(place, row.createdAt, row.updatedAt)?.let { return it }

        if (row.isMissing && row.isBorrowed) return place.bad(BackupProblem.DOMAIN_INVARIANT, "isBorrowed")
        if (pool != null && mode != null && !isTrackingModeAllowed(pool, mode)) {
            return place.bad(BackupProblem.DOMAIN_INVARIANT, "selectedTrackingMode")
        }
        // A selection of the raw text has both ends or neither, and covers at
        // least one character (PLAN 11.4.1).
        if ((row.selectionStartIndex == null) != (row.selectionEndIndex == null)) {
            return place.bad(BackupProblem.DOMAIN_INVARIANT, "selectionEndIndex")
        }
        val start = row.selectionStartIndex
        val end = row.selectionEndIndex
        if (start != null && end != null) {
            if (start < 0) return place.bad(BackupProblem.INVALID_VALUE, "selectionStartIndex")
            if (end <= start) return place.bad(BackupProblem.DOMAIN_INVARIANT, "selectionEndIndex")
        }
    }
    data.draftTaskColors.forEach { row ->
        val place = BackupPlace("draftTaskColors")
        if (!isCanonicalUuid(row.draftTaskId)) return place.bad(BackupProblem.INVALID_ID, "draftTaskId")
        if (!isCanonicalUuid(row.colorId)) return place.bad(BackupProblem.INVALID_ID, "colorId")
        if (row.slotIndex < 0) return place.bad(BackupProblem.INVALID_VALUE, "slotIndex")
    }
    return null
}

/**
 * When a row was written and when it last changed: both real moments, and
 * nothing more.
 *
 * In particular not that the change comes after the writing. The system clock
 * is not monotonic — an NTP correction, a second operating system, a restored
 * virtual machine or a hand-set clock all move it back — and this application's
 * own writes then leave `updatedAt < createdAt`. Refusing that refused the
 * user's own data: every import snapshot on such a machine failed to verify and
 * stopped the import, and their own backups would not go back (PLAN 14.7.3).
 *
 * Nor did the rule protect anything. PLAN 11.4.4 decides whether an imported
 * task was touched by `updatedAt != createdAt`, so a row that runs backwards
 * counts as touched and stops a rollback rather than enabling one; and a
 * document can claim "untouched" with two equal values, which no ordering rule
 * would ever catch. The values travel exactly as they are, never corrected.
 */
private fun checkWrittenAndChanged(
    place: BackupPlace,
    createdAt: Long,
    updatedAt: Long,
): BackupRejection? {
    if (!isMoment(createdAt)) return place.bad(BackupProblem.INVALID_VALUE, "createdAt")
    if (!isMoment(updatedAt)) return place.bad(BackupProblem.INVALID_VALUE, "updatedAt")
    return null
}

/**
 * Whether [value] is a moment this application could have recorded.
 *
 * Epoch milliseconds, at or after the epoch and no later than the end of the
 * year 9999. The lower bound is not pedantry: a negative moment is what a
 * corrupted or hand-edited number looks like, and it would sort a history line
 * before everything else forever.
 */
private fun isMoment(value: Long): Boolean = value in 0..LATEST_TIMESTAMP

private fun isMoment(value: Long?): Boolean = value == null || isMoment(value)

/** The canonical UUID text `EntityId` writes and reads: lower case, hyphenated. */
internal fun isCanonicalUuid(text: String): Boolean = CANONICAL_UUID.matches(text)

private val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun BackupPlace.bad(
    problem: BackupProblem,
    field: String,
) = BackupRejection(problem, copy(field = field))
