package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.SourceColumnType
import kotlin.time.Duration.Companion.seconds

// What the tests of removing an unconfirmed import share: a draft of any size
// built through the review paths the screen uses, the whole database read as
// one value, that value with one draft's own rows taken out, and SQLite's two
// soundness checks.

/** The identities of a draft built by [aDraftImport]. */
data class DraftImport(
    val batchId: EntityId,
    val blockIds: List<EntityId>,
    val draftIds: List<EntityId>,
)

/**
 * An unconfirmed import of [blocks] raw cells, each carrying [draftsPerBlock]
 * drafts with [coloursPerDraft] colours chosen.
 *
 * The counts on the batch row agree with what is written, so the draft is a
 * sound one in the sense of PLAN 11.4.5 — none of `D1`–`D9` holds.
 */
suspend fun aDraftImport(
    database: AppDatabase,
    blocks: Int,
    fingerprint: String,
    draftsPerBlock: Int = 1,
    coloursPerDraft: Int = 2,
    fileName: String = "taslak.csv",
): DraftImport {
    val importDao = database.importDao()
    val batch = anImportBatch(sha256 = fingerprint, fileName = fileName, rawBlockCount = blocks)
    val colours = database.colorDao().allColors().map { it.id }
    val drafts = mutableListOf<EntityId>()
    val cells =
        (0 until blocks).map { at ->
            aRawImportBlock(
                importBatchId = batch.id,
                rawText = "${at + 1} KIRMIZI** ${at + 2} YEŞİL**",
                rowIndex = at + 1,
                columnIndex = 2,
                sourceColumnType = SourceColumnType.THREE_D,
            )
        }
    importDao.saveDraftBatch(batch, cells)
    cells.forEachIndexed { at, cell ->
        repeat(draftsPerBlock) { nth ->
            val draft = aDraftTask(cell.id, name = "Taslak $at-$nth").copy(createdAt = createdAt + (at * 10 + nth).seconds)
            importDao.addDraftTaskUnderReview(draft)
            importDao.setDraftColorsUnderReview(draft.id, colours.take(coloursPerDraft), StoppedClock(updatedAt))
            drafts += draft.id
        }
    }
    return DraftImport(batch.id, cells.map { it.id }, drafts)
}

/** Every row of the fifteen tables, read the way a backup reads them. */
suspend fun wholeDatabase(database: AppDatabase): BackupData = BackupStore(database).snapshot().data

/**
 * The whole database as it would read with one draft import removed, and
 * nothing else: its batch row, its raw cells, their drafts, those drafts'
 * colours and its cell snapshots. Every other row of every table stays, value
 * for value — which is exactly what PLAN 11.4.5 promises a removal leaves.
 */
fun BackupData.withoutDraft(batchId: EntityId): BackupData {
    val batch = batchId.toString()
    val blocks = rawImportBlocks.filter { it.importBatchId == batch }.map { it.id }.toSet()
    val drafts = draftTasks.filter { it.rawImportBlockId in blocks }.map { it.id }.toSet()
    return copy(
        importBatches = importBatches.filterNot { it.id == batch },
        rawImportBlocks = rawImportBlocks.filterNot { it.id in blocks },
        draftTasks = draftTasks.filterNot { it.id in drafts },
        draftTaskColors = draftTaskColors.filterNot { it.draftTaskId in drafts },
        importBatchCells = importBatchCells.filterNot { it.importBatchId == batch },
    )
}

/** What `foreign_key_check` and `integrity_check` object to; empty when the file is sound. */
suspend fun soundnessProblemsOf(database: AppDatabase): List<String> =
    database.useReaderConnection { transactor ->
        transactor.usePrepared("PRAGMA foreign_key_check") { statement ->
            buildList { while (statement.step()) add("foreign key: ${statement.getText(0)}") }
        } +
            transactor
                .usePrepared("PRAGMA integrity_check") { statement ->
                    buildList { while (statement.step()) add(statement.getText(0)) }
                }.filterNot { it == "ok" }
    }

/** Runs one statement of SQL the application itself never runs, to build a shape or a trap. */
suspend fun executeRawSql(
    database: AppDatabase,
    sql: String,
    vararg arguments: String,
) {
    database.useWriterConnection { transactor ->
        transactor.usePrepared(sql) { statement ->
            arguments.forEachIndexed { at, argument -> statement.bindText(at + 1, argument) }
            statement.step()
        }
    }
}
