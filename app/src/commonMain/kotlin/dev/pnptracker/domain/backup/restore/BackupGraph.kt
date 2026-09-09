package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BackupData

/**
 * One pointer the file has to keep whole.
 *
 * These are the foreign keys of schema version 8, written in the format's own
 * words: the array a row lives in, the field that points, and the array it
 * points into. A backup is a closed world — everything a row names has to be
 * somewhere in the same file — and this is the list of every way it can fail to
 * be.
 *
 * Written out rather than derived, and held against the committed `8.json` by a
 * test. Derived from the database it would agree with the database whatever it
 * said, and the case worth catching is exactly the one where somebody adds a
 * foreign key and nobody teaches the reader about it: then a backup with a
 * dangling reference gets as far as the database, and the first thing that
 * notices is a transaction failing halfway through somebody's restore.
 */
internal data class BackupReference(
    val from: String,
    val field: String,
    val to: String,
    /** Whether the field may be null, which is not the same as pointing nowhere. */
    val optional: Boolean,
)

/** A key no two rows of an array may share: a primary key, or a unique index. */
internal data class BackupUniqueKey(
    val array: String,
    val fields: List<String>,
    /** Whether a null in any field means the row is exempt, as it is in SQL. */
    val nullsAreExempt: Boolean = false,
)

internal val backupReferences: List<BackupReference> =
    listOf(
        BackupReference("colorAliases", "colorId", "colors", optional = false),
        BackupReference("games", "sourceImportBatchId", "importBatches", optional = true),
        BackupReference("gameCells", "gameId", "games", optional = false),
        BackupReference("rawImportBlocks", "importBatchId", "importBatches", optional = false),
        BackupReference("rawImportBlocks", "completionTargetGameId", "games", optional = true),
        BackupReference("tasks", "sourceRawImportBlockId", "rawImportBlocks", optional = true),
        BackupReference("cellSegments", "cellId", "gameCells", optional = false),
        BackupReference("cellSegments", "taskId", "tasks", optional = true),
        BackupReference("taskColors", "taskId", "tasks", optional = false),
        BackupReference("taskColors", "colorId", "colors", optional = false),
        BackupReference("taskStages", "taskId", "tasks", optional = false),
        BackupReference("progressEvents", "taskId", "tasks", optional = false),
        BackupReference("historyEvents", "gameId", "games", optional = false),
        BackupReference("historyEvents", "taskId", "tasks", optional = true),
        BackupReference("importBatchCells", "importBatchId", "importBatches", optional = false),
        BackupReference("importBatchCells", "cellId", "gameCells", optional = false),
        BackupReference("draftTasks", "rawImportBlockId", "rawImportBlocks", optional = false),
        BackupReference("draftTasks", "targetCellId", "gameCells", optional = true),
        BackupReference("draftTasks", "materializedTaskId", "tasks", optional = true),
        BackupReference("draftTaskColors", "draftTaskId", "draftTasks", optional = false),
        BackupReference("draftTaskColors", "colorId", "colors", optional = false),
    )

internal val backupUniqueKeys: List<BackupUniqueKey> =
    listOf(
        BackupUniqueKey("colors", listOf("id")),
        BackupUniqueKey("colors", listOf("normalizedName")),
        BackupUniqueKey("colorAliases", listOf("colorId", "normalizedAlias")),
        BackupUniqueKey("colorAliases", listOf("normalizedAlias")),
        BackupUniqueKey("importBatches", listOf("id")),
        BackupUniqueKey("games", listOf("id")),
        BackupUniqueKey("gameCells", listOf("id")),
        BackupUniqueKey("gameCells", listOf("gameId", "columnType")),
        BackupUniqueKey("rawImportBlocks", listOf("id")),
        BackupUniqueKey("rawImportBlocks", listOf("importBatchId", "sheetName", "rowIndex", "columnIndex")),
        BackupUniqueKey("tasks", listOf("id")),
        BackupUniqueKey("cellSegments", listOf("id")),
        BackupUniqueKey("cellSegments", listOf("cellId", "orderIndex")),
        BackupUniqueKey("cellSegments", listOf("taskId"), nullsAreExempt = true),
        BackupUniqueKey("taskColors", listOf("taskId", "colorId")),
        BackupUniqueKey("taskColors", listOf("taskId", "slotIndex")),
        BackupUniqueKey("taskStages", listOf("taskId", "stage")),
        BackupUniqueKey("taskStages", listOf("taskId", "orderIndex")),
        BackupUniqueKey("progressEvents", listOf("id")),
        BackupUniqueKey("historyEvents", listOf("id")),
        BackupUniqueKey("importBatchCells", listOf("importBatchId", "cellId")),
        BackupUniqueKey("draftTasks", listOf("id")),
        BackupUniqueKey("draftTasks", listOf("materializedTaskId"), nullsAreExempt = true),
        BackupUniqueKey("draftTaskColors", listOf("draftTaskId", "colorId")),
        BackupUniqueKey("draftTaskColors", listOf("draftTaskId", "slotIndex")),
    )

/**
 * The lists that other rows point into, and the field they are found by.
 *
 * Every foreign key in schema version 8 points at an `id`, so one set of
 * identifiers per array is all the graph needs.
 */
private val referenceTargets = listOf("colors", "importBatches", "games", "gameCells", "rawImportBlocks", "tasks", "draftTasks")

/**
 * Whether the file makes sense as a whole.
 *
 * Three questions, each answered in one pass over the rows and none of them by
 * asking a database. Nothing here opens a connection or runs a query: a
 * thousand-task backup is checked with a handful of sets, and the temporary
 * database that comes later is a second opinion rather than the first.
 *
 * Are the rows distinct — no two claiming the same key, and none claiming a
 * place that holds one row. Does everything a row names exist in the same file.
 * And do the ordered things run without gaps, which is PLAN 16's rule for a
 * cell's pieces and, for the colour slots, what the catalogue itself maintains
 * when a colour is deleted.
 */
internal fun checkGraph(data: BackupData): BackupRejection? {
    val arrays = viewsOf(data)
    checkUniqueness(arrays)?.let { return it }
    checkReferences(arrays)?.let { return it }
    checkOrders(data)?.let { return it }
    return null
}

private fun checkUniqueness(arrays: Map<String, ArrayView>): BackupRejection? {
    backupUniqueKeys.forEach { key ->
        val view = arrays.getValue(key.array)
        val seen = HashSet<String>(view.size)
        for (index in 0 until view.size) {
            val parts = key.fields.map { view.field(index, it) }
            if (key.nullsAreExempt && parts.any { it == null }) continue
            // Length prefixed rather than joined by a separator: a normalised
            // alias is the user's own text and may hold whatever character was
            // chosen as the separator, and two different pairs must never
            // compose into the same string.
            val composed = parts.joinToString("") { part -> "${part?.length ?: -1}:$part" }
            if (!seen.add(composed)) {
                return BackupRejection(BackupProblem.DUPLICATE_RECORD, BackupPlace(key.array, key.fields.first()))
            }
        }
    }
    return null
}

private fun checkReferences(arrays: Map<String, ArrayView>): BackupRejection? {
    val known =
        referenceTargets.associateWith { name ->
            val view = arrays.getValue(name)
            HashSet<String>(view.size).apply {
                for (index in 0 until view.size) add(view.field(index, "id")!!)
            }
        }
    backupReferences.forEach { reference ->
        val view = arrays.getValue(reference.from)
        val targets = known.getValue(reference.to)
        for (index in 0 until view.size) {
            val pointed = view.field(index, reference.field)
            if (pointed == null) {
                if (reference.optional) continue
                return BackupRejection(BackupProblem.BROKEN_REFERENCE, BackupPlace(reference.from, reference.field))
            }
            if (pointed !in targets) {
                return BackupRejection(BackupProblem.BROKEN_REFERENCE, BackupPlace(reference.from, reference.field))
            }
        }
    }
    return null
}

/**
 * The four things the format keeps in order, checked for holes.
 *
 * A cell's pieces run `0..n-1` because PLAN 16 says a cell document is ordered,
 * gapless and non-overlapping. The colour slots of a task run the same way and
 * the catalogue keeps them that way: deleting a colour renumbers the slots that
 * are left rather than leaving the hole behind. A backup with a hole in either
 * would restore a cell that cannot be rendered or a multi-colour name that
 * cannot be split.
 *
 * Duplicates inside a group are not this function's business — a unique key
 * already covers each of these — so a group whose indexes are distinct and whose
 * largest is `size - 1` has no gaps.
 */
private fun checkOrders(data: BackupData): BackupRejection? {
    gapIn(data.cellSegments.groupBy { it.cellId }) { it.orderIndex }?.let {
        return BackupRejection(BackupProblem.DOMAIN_INVARIANT, BackupPlace("cellSegments", "orderIndex"))
    }
    gapIn(data.taskColors.groupBy { it.taskId }) { it.slotIndex }?.let {
        return BackupRejection(BackupProblem.DOMAIN_INVARIANT, BackupPlace("taskColors", "slotIndex"))
    }
    gapIn(data.taskStages.groupBy { it.taskId }) { it.orderIndex }?.let {
        return BackupRejection(BackupProblem.DOMAIN_INVARIANT, BackupPlace("taskStages", "orderIndex"))
    }
    gapIn(data.draftTaskColors.groupBy { it.draftTaskId }) { it.slotIndex }?.let {
        return BackupRejection(BackupProblem.DOMAIN_INVARIANT, BackupPlace("draftTaskColors", "slotIndex"))
    }
    return null
}

/** The first group whose indexes do not run from zero without a hole. */
private fun <R> gapIn(
    groups: Map<String, List<R>>,
    indexOf: (R) -> Int,
): String? = groups.entries.firstOrNull { (_, rows) -> rows.maxOf(indexOf) != rows.size - 1 }?.key

/**
 * The fields of one array the graph needs, read straight off the records.
 *
 * Only the fields that take part in a key or a reference. Copying every field of
 * every row into a map would double what a large backup costs to hold, for the
 * sake of a lookup that is written out below in one line per field anyway.
 */
private class ArrayView(
    val size: Int,
    val field: (Int, String) -> String?,
)

private fun viewsOf(data: BackupData): Map<String, ArrayView> =
    mapOf(
        "colors" to
            ArrayView(data.colors.size) { at, field ->
                val row = data.colors[at]
                when (field) {
                    "id" -> row.id
                    "normalizedName" -> row.normalizedName
                    else -> unknown(field)
                }
            },
        "colorAliases" to
            ArrayView(data.colorAliases.size) { at, field ->
                val row = data.colorAliases[at]
                when (field) {
                    "colorId" -> row.colorId
                    "normalizedAlias" -> row.normalizedAlias
                    else -> unknown(field)
                }
            },
        "importBatches" to
            ArrayView(data.importBatches.size) { at, field ->
                when (field) {
                    "id" -> data.importBatches[at].id
                    else -> unknown(field)
                }
            },
        "games" to
            ArrayView(data.games.size) { at, field ->
                val row = data.games[at]
                when (field) {
                    "id" -> row.id
                    "sourceImportBatchId" -> row.sourceImportBatchId
                    else -> unknown(field)
                }
            },
        "gameCells" to
            ArrayView(data.gameCells.size) { at, field ->
                val row = data.gameCells[at]
                when (field) {
                    "id" -> row.id
                    "gameId" -> row.gameId
                    "columnType" -> row.columnType
                    else -> unknown(field)
                }
            },
        "rawImportBlocks" to
            ArrayView(data.rawImportBlocks.size) { at, field ->
                val row = data.rawImportBlocks[at]
                when (field) {
                    "id" -> row.id
                    "importBatchId" -> row.importBatchId
                    "completionTargetGameId" -> row.completionTargetGameId
                    "sheetName" -> row.sheetName
                    "rowIndex" -> row.rowIndex.toString()
                    "columnIndex" -> row.columnIndex.toString()
                    else -> unknown(field)
                }
            },
        "tasks" to
            ArrayView(data.tasks.size) { at, field ->
                val row = data.tasks[at]
                when (field) {
                    "id" -> row.id
                    "sourceRawImportBlockId" -> row.sourceRawImportBlockId
                    else -> unknown(field)
                }
            },
        "cellSegments" to
            ArrayView(data.cellSegments.size) { at, field ->
                val row = data.cellSegments[at]
                when (field) {
                    "id" -> row.id
                    "cellId" -> row.cellId
                    "taskId" -> row.taskId
                    "orderIndex" -> row.orderIndex.toString()
                    else -> unknown(field)
                }
            },
        "taskColors" to
            ArrayView(data.taskColors.size) { at, field ->
                val row = data.taskColors[at]
                when (field) {
                    "taskId" -> row.taskId
                    "colorId" -> row.colorId
                    "slotIndex" -> row.slotIndex.toString()
                    else -> unknown(field)
                }
            },
        "taskStages" to
            ArrayView(data.taskStages.size) { at, field ->
                val row = data.taskStages[at]
                when (field) {
                    "taskId" -> row.taskId
                    "stage" -> row.stage
                    "orderIndex" -> row.orderIndex.toString()
                    else -> unknown(field)
                }
            },
        "progressEvents" to
            ArrayView(data.progressEvents.size) { at, field ->
                val row = data.progressEvents[at]
                when (field) {
                    "id" -> row.id
                    "taskId" -> row.taskId
                    else -> unknown(field)
                }
            },
        "historyEvents" to
            ArrayView(data.historyEvents.size) { at, field ->
                val row = data.historyEvents[at]
                when (field) {
                    "id" -> row.id
                    "gameId" -> row.gameId
                    "taskId" -> row.taskId
                    else -> unknown(field)
                }
            },
        "importBatchCells" to
            ArrayView(data.importBatchCells.size) { at, field ->
                val row = data.importBatchCells[at]
                when (field) {
                    "importBatchId" -> row.importBatchId
                    "cellId" -> row.cellId
                    else -> unknown(field)
                }
            },
        "draftTasks" to
            ArrayView(data.draftTasks.size) { at, field ->
                val row = data.draftTasks[at]
                when (field) {
                    "id" -> row.id
                    "rawImportBlockId" -> row.rawImportBlockId
                    "targetCellId" -> row.targetCellId
                    "materializedTaskId" -> row.materializedTaskId
                    else -> unknown(field)
                }
            },
        "draftTaskColors" to
            ArrayView(data.draftTaskColors.size) { at, field ->
                val row = data.draftTaskColors[at]
                when (field) {
                    "draftTaskId" -> row.draftTaskId
                    "colorId" -> row.colorId
                    "slotIndex" -> row.slotIndex.toString()
                    else -> unknown(field)
                }
            },
    )

/**
 * A field the graph asked for and the array does not offer.
 *
 * Not a way a backup can be wrong: the only things that ask are the two lists at
 * the top of this file, so reaching here means one of them names a field that
 * was never wired up. That is a mistake in this code and it says so, rather than
 * quietly answering null and turning into a broken reference somebody would go
 * looking for in the user's file.
 */
private fun unknown(field: String): Nothing = error("The backup graph asks for a field nothing provides: $field")
