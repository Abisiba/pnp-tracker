package dev.pnptracker.data.database

import dev.pnptracker.AppInfo
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.confirmationStore
import dev.pnptracker.domain.backup.BackupCellSegmentRow
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupImportBatchCellRow
import dev.pnptracker.domain.backup.BackupImportBatchRow
import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.BackupRejection
import dev.pnptracker.domain.backup.restore.FakeBackupInput
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.importprep.PreparedRawBlock
import dev.pnptracker.domain.importrollback.ImportRollbackException
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.platform.recovery.aDraftEdit
import dev.pnptracker.platform.recovery.aPreparedImport
import dev.pnptracker.platform.recovery.aReadyDraftImport
import dev.pnptracker.ui.feature.settings.aSafetySnapshot
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

private val WRITTEN_AT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/**
 * PLAN 14.7.5, Dilim 5: which of the confirmed and rolled back import candidates
 * the application's own paths keep, which legitimate old states they must leave
 * alone, and which of them a restored backup can carry into a live database —
 * measured, not assumed. Nothing in production changes here.
 *
 * The reach half is İş 7's method exactly: a sound database built by the real
 * stores is read into a canonical backup, one relation and nothing else is
 * broken in it, the checksum is computed again by the format's own function, and
 * the document goes through the real [UntrustedBackupReader], the real
 * [TemporaryBackupProbe] and the real [LiveBackupRestorer]. Rows written straight
 * into a live database are not evidence of anything a user can reach.
 *
 * For each candidate the result of every stage is kept apart: what the reader
 * said, what the throwaway database said (both are one answer, `Valid` or a
 * rejection), whether the live database took it value for value, which
 * candidates the live database then breaks, and what today's rollback does with
 * the confirmed batch. The last column is the harm observation: recorded, and
 * pinned here only so that Dilim 6 has to change it on purpose.
 */
class LifecycleContradictionReachTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var applicationData: Path
    private val probeRoots = mutableListOf<Path>()
    private var realDatabaseExistedBefore = false
    private lateinit var live: AppDatabase

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        applicationData = Files.createTempDirectory("pnp-tracker-lifecycle-data")
        live = DatabaseFactory().open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        live.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        probeRoots.forEach { check(Files.notExists(it)) { "the probe left $it behind" } }
        Files.delete(applicationData)
        directory.delete()
    }

    // ------------------------------------------------ built the way a person builds it

    /** A game with an empty 3D cell of its own; the cell. */
    private suspend fun aCellOf(
        database: AppDatabase,
        gameName: String,
    ): EntityId {
        val game = aGame(name = gameName)
        database.gameDao().insert(game)
        val cell = aCell(gameId = game.id, columnType = CellColumnType.THREE_D)
        database.gameCellDao().insert(cell)
        return cell.id
    }

    /** An import of [tasks] cells, a draft cut from each and aimed in turn at [cells], confirmed for real. */
    private suspend fun aConfirmedImport(
        database: AppDatabase,
        tasks: Int,
        cells: List<EntityId>,
    ): EntityId {
        val importDao = database.importDao()
        val prepared =
            aPreparedImport().copy(
                sha256 = "%064x".format(tasks * 1_000 + cells.size + importDao.allBatches().size),
                endRowIndex = tasks - 1,
                blocks =
                    (0 until tasks).map { row ->
                        PreparedRawBlock(row, 1, SourceColumnType.THREE_D, "${row + 2} KIRMIZI", null, HintDecision.NONE)
                    },
            )
        val batchId = ImportDraftStore(importDao).save(prepared).batchId
        val review = ImportReviewStore(importDao, database.gameDao(), database.colorDao())
        importDao.rawBlocksOfBatch(batchId).forEachIndexed { at, block ->
            val draftId = review.createDraftFromSelection(block.id, 0, block.rawText.length)
            review.saveDraft(aDraftEdit(draftId, cells[at % cells.size], "Figür $at", 3, emptyList()))
        }
        confirmationStore(database).confirm(batchId, acknowledgeUnprocessedBlocks = true)
        return batchId
    }

    private suspend fun rollBack(
        database: AppDatabase,
        batchId: EntityId,
    ) {
        ImportRollbackStore(database.importDao()).rollBack(batchId)
    }

    // ------------------------------------------------------------------ (a) own paths

    @Test
    fun `the application's own imports keep every candidate, at one task and at forty-two`() =
        runBlocking<Unit> {
            val cells = listOf(aCellOf(live, "Harmonies"), aCellOf(live, "Wingspan"))
            listOf(1, 42).forEach { tasks ->
                rollBack(live, aConfirmedImport(live, tasks, cells))
                aConfirmedImport(live, tasks, cells)
            }
            aReadyDraftImport(live)

            val data = wholeDatabase(live)
            assertEquals(mapOf("CONFIRMED" to 2, "ROLLED_BACK" to 2, "DRAFT" to 1), data.importBatches.groupingBy { it.status }.eachCount())
            assertEquals(data.importBatches.associate { it.id to emptySet<LifecycleCandidate>() }, lifecycleCandidatesBrokenIn(data))
        }

    // ------------------------------------------------------- (a) older schemas

    @Test
    fun `imports confirmed under schemas three to seven arrive with nothing counted as a contradiction`() =
        runBlocking<Unit> {
            (3..7).forEach { version ->
                val file = directory.root.resolve("v$version.db")
                val batchId = IdGenerator.Random.newId()
                CommittedSchema.createDatabase(file, version = version) { connection ->
                    // What each version's own code left behind after a confirmation.
                    insertVersion5ImportBatch(connection, batchId, status = "CONFIRMED", createdTaskCount = 1)
                    val blockId = IdGenerator.Random.newId()
                    if (version >= 6) {
                        insertVersion6RawImportBlock(connection, blockId, batchId)
                    } else {
                        insertVersion3RawImportBlock(connection, blockId, batchId)
                    }
                    if (version == 3) {
                        insertVersion3DraftTask(connection, IdGenerator.Random.newId(), blockId)
                    } else {
                        val gameId = IdGenerator.Random.newId()
                        val cellId = IdGenerator.Random.newId()
                        val taskId = IdGenerator.Random.newId()
                        insertVersion4Game(connection, gameId)
                        insertVersion4GameCell(connection, cellId, gameId, "CARD")
                        when (version) {
                            4 -> insertVersion4Task(connection, taskId, "CARD", "PIPELINE", "Kırmızı token")
                            5 -> insertVersion5Task(connection, taskId, "CARD", "PIPELINE", "Kırmızı token")
                            else -> insertVersion6Task(connection, taskId, "CARD", "PIPELINE", "Kırmızı token")
                        }
                        connection.prepare("UPDATE tasks SET source_raw_import_block_id = ? WHERE id = ?").use { statement ->
                            statement.bindText(1, blockId.toString())
                            statement.bindText(2, taskId.toString())
                            statement.step()
                        }
                        insertVersion4TaskSegment(connection, IdGenerator.Random.newId(), cellId, 0, taskId)
                        insertVersion4DraftTask(connection, IdGenerator.Random.newId(), blockId, cellId, materializedTaskId = taskId)
                    }
                }

                val database = DatabaseFactory().open(file)
                try {
                    val data = wholeDatabase(database)
                    assertEquals("CONFIRMED", data.importBatches.single().status, "v$version")
                    // Schema 8's record of cells did not exist: the relation is not claimed.
                    assertEquals(emptyList(), data.importBatchCells, "v$version")
                    assertEquals(mapOf(batchId.toString() to emptySet()), lifecycleCandidatesBrokenIn(data), "v$version")
                    if (version == 3) {
                        // Migration3To4 empties what version three called materialised.
                        assertEquals(listOf(null), data.draftTasks.map { it.materializedTaskId }, "v3")
                    }
                } finally {
                    database.close()
                }
            }
        }

    // ------------------------------------------------------------------ (b) reach

    private sealed interface Reach {
        data class Refused(
            val rejection: BackupRejection,
        ) : Reach

        data object Live : Reach
    }

    private suspend fun measure(data: BackupData): Reach {
        val bytes = backupDocumentOf(data, AppInfo.Current.version, 8, WRITTEN_AT).json.encodeToByteArray()
        val reader =
            UntrustedBackupReader(
                TemporaryBackupProbe(
                    temporaryDirectory = { Files.createTempDirectory("pnp-tracker-lifecycle-probe").also { probeRoots.add(it) } },
                    applicationDataDirectory = { applicationData },
                ),
            )
        return when (val read = reader.read(FakeBackupInput(bytes))) {
            is BackupReadResult.Refused -> Reach.Refused(read.rejection)
            is BackupReadResult.Valid -> {
                assertEquals(null, LiveBackupRestorer(live).restore(read.backup, aSafetySnapshot(wholeDatabase(live))))
                assertEquals(data, wholeDatabase(live), "the live database is not what was restored")
                assertEquals(emptyList(), soundnessProblemsOf(live))
                Reach.Live
            }
        }
    }

    /** What today's rollback does with the batch: refused and why, or done and to what. */
    private suspend fun rollbackOutcome(batchId: EntityId): String {
        val before = wholeDatabase(live)
        val preview = ImportRollbackStore(live.importDao()).previewRollback(batchId)
        return try {
            val result = ImportRollbackStore(live.importDao()).rollBack(batchId)
            val after = wholeDatabase(live)
            val tombstoned = after.tasks.filter { it.deletedAt != null && before.tasks.single { b -> b.id == it.id }.deletedAt == null }
            val notTheImports =
                tombstoned.count { task ->
                    before.rawImportBlocks.none { it.id == task.sourceRawImportBlockId && it.importBatchId == batchId.toString() }
                }
            val touchedCells = after.gameCells.count { cell -> before.gameCells.single { it.id == cell.id } != cell }
            "done: preview=${preview.blockingFailure}, tasks=${result.removedTaskCount}, cells=${result.restoredCellCount}, " +
                "tombstoned-not-sourced-from-it=$notTheImports, cells-rewritten=$touchedCells"
        } catch (refused: ImportRollbackException) {
            assertEquals(before, wholeDatabase(live), "a refused rollback wrote something")
            "refused: preview=${preview.blockingFailure}, rollback=${refused.failure}"
        }
    }

    @Test
    fun `each candidate alone, through the real restore line, and what rollback does with it`() =
        runBlocking<Unit> {
            val cellA = aCellOf(live, "Harmonies")
            val cellB = aCellOf(live, "Wingspan")
            // A cell of the user's own, written in and never imported into.
            val own = aGame(name = "Kendi oyunum")
            live.gameDao().insert(own)
            live.cellSegmentDao().saveDocumentText(own.id, CellColumnType.THREE_D, "", "Kendi notum", Clock.System, IdGenerator.Random)
            val ownCell = live.gameCellDao().cellOfGame(own.id, CellColumnType.THREE_D)!!.id

            val rolledBack = aConfirmedImport(live, 2, listOf(cellA, cellB)).also { rollBack(live, it) }.toString()
            val confirmedId = aConfirmedImport(live, 2, listOf(cellA, cellB))
            val confirmed = confirmedId.toString()
            val base = wholeDatabase(live)
            assertEquals(Reach.Live, measure(base))
            assertEquals(base.importBatches.associate { it.id to emptySet<LifecycleCandidate>() }, lifecycleCandidatesBrokenIn(base))

            fun BackupData.batch(
                id: String,
                change: (BackupImportBatchRow) -> BackupImportBatchRow,
            ) = copy(importBatches = importBatches.map { if (it.id == id) change(it) else it })

            val blocksOf = { id: String ->
                base.rawImportBlocks
                    .filter { it.importBatchId == id }
                    .map { it.id }
                    .toSet()
            }
            val draftsOf = { id: String -> base.draftTasks.filter { it.rawImportBlockId in blocksOf(id) } }
            val firstMade = draftsOf(confirmed).first()
            val firstRolled = draftsOf(rolledBack).first()

            val mutations: List<Triple<LifecycleCandidate, String, (BackupData) -> BackupData>> =
                listOf(
                    Triple(LifecycleCandidate.C1, confirmed) { d ->
                        d.copy(draftTasks = d.draftTasks.map { if (it.id == firstMade.id) it.copy(materializedTaskId = null) else it })
                    },
                    Triple(
                        LifecycleCandidate.C2,
                        confirmed,
                    ) { d -> d.batch(confirmed) { it.copy(createdTaskCount = it.createdTaskCount + 1) } },
                    Triple(LifecycleCandidate.C3, confirmed) { d ->
                        d.copy(
                            tasks =
                                d.tasks.map {
                                    if (it.id ==
                                        firstMade.materializedTaskId
                                    ) {
                                        it.copy(sourceRawImportBlockId = null)
                                    } else {
                                        it
                                    }
                                },
                        )
                    },
                    Triple(LifecycleCandidate.C4, confirmed) { d ->
                        d.copy(
                            importBatchCells =
                                (d.importBatchCells + BackupImportBatchCellRow(confirmed, ownCell.toString(), "Kendi notum"))
                                    .sortedWith(compareBy({ it.importBatchId }, { it.cellId })),
                        )
                    },
                    Triple(LifecycleCandidate.C5, confirmed) { d -> d.batch(confirmed) { it.copy(createdGameCount = 1) } },
                    Triple(LifecycleCandidate.RB1, rolledBack) { d ->
                        d.copy(
                            importBatchCells =
                                d.importBatchCells.filter {
                                    it.importBatchId !=
                                        rolledBack
                                },
                        )
                    },
                    Triple(LifecycleCandidate.RB2, rolledBack) { d ->
                        d.batch(rolledBack) {
                            it.copy(
                                createdTaskCount =
                                    it.createdTaskCount + 1,
                            )
                        }
                    },
                    Triple(LifecycleCandidate.RB3, rolledBack) { d ->
                        d.copy(tasks = d.tasks.map { if (it.id == firstRolled.materializedTaskId) it.copy(deletedAt = null) else it })
                    },
                    Triple(LifecycleCandidate.RB4, rolledBack) { d ->
                        val last = d.cellSegments.filter { it.cellId == cellA.toString() }.maxOf { it.orderIndex }
                        val piece =
                            BackupCellSegmentRow(
                                id = IdGenerator.Random.newId().toString(),
                                cellId = cellA.toString(),
                                orderIndex = last + 1,
                                kind = "TASK",
                                text = null,
                                taskId = firstRolled.materializedTaskId,
                                createdAt = WRITTEN_AT.toEpochMilliseconds(),
                                updatedAt = WRITTEN_AT.toEpochMilliseconds(),
                            )
                        d.copy(cellSegments = (d.cellSegments + piece).sortedWith(compareBy({ it.cellId }, { it.orderIndex })))
                    },
                    Triple(LifecycleCandidate.RB5, rolledBack) { d ->
                        d.copy(
                            historyEvents =
                                d.historyEvents.filterNot {
                                    it.kind == "TASK_ROLLED_BACK" &&
                                        it.taskId == firstRolled.materializedTaskId
                                },
                        )
                    },
                    Triple(LifecycleCandidate.U1, confirmed) { d ->
                        d.copy(
                            rawImportBlocks =
                                d.rawImportBlocks.map {
                                    if (it.id ==
                                        firstMade.rawImportBlockId
                                    ) {
                                        it.copy(gameCompletionHint = "PENDING")
                                    } else {
                                        it
                                    }
                                },
                        )
                    },
                    Triple(LifecycleCandidate.U2, confirmed) { d ->
                        val text = d.rawImportBlocks.single { it.id == firstMade.rawImportBlockId }.rawText
                        d.copy(
                            draftTasks =
                                d.draftTasks.map {
                                    if (it.id ==
                                        firstMade.id
                                    ) {
                                        it.copy(selectionEndIndex = text.length + 1)
                                    } else {
                                        it
                                    }
                                },
                        )
                    },
                    Triple(LifecycleCandidate.U3, confirmed) { d -> d.batch(confirmed) { it.copy(rawBlockCount = it.rawBlockCount + 1) } },
                )

            val observed =
                mutations.associate { (candidate, batchId, mutate) ->
                    val damaged = mutate(base)
                    check(damaged != base) { "$candidate: the mutation changed nothing" }
                    val reach = measure(damaged)
                    val breaks = if (reach == Reach.Live) lifecycleCandidatesBrokenIn(wholeDatabase(live)).getValue(batchId) else null
                    val rollback = if (reach == Reach.Live && batchId == confirmed) rollbackOutcome(confirmedId) else "—"
                    candidate to Triple(reach, breaks, rollback)
                }

            // Every candidate reaches the live database, and the live database then
            // breaks that candidate and no other.
            mutations.forEach { (candidate, _, _) ->
                val (reach, breaks, _) = observed.getValue(candidate)
                assertEquals(Reach.Live, reach, "$candidate")
                val expected =
                    if (candidate == LifecycleCandidate.RB1) {
                        // A rolled back batch without its cells no longer meets C4 either.
                        setOf(LifecycleCandidate.RB1, LifecycleCandidate.RB2)
                    } else {
                        setOf(candidate)
                    }
                assertEquals(expected, breaks, "$candidate")
            }

            // What today's rollback does with the confirmed batch (measured; the
            // harm Dilim 6 closes). C1 is already refused by the trail check.
            val rollback = observed.mapValues { it.value.third }
            assertEquals("refused: preview=PROVENANCE_BROKEN, rollback=PROVENANCE_BROKEN", rollback[LifecycleCandidate.C1])
            // C2: the count is wrong and nothing stops the rollback.
            assertEquals(HARMLESS_ROLLBACK, rollback[LifecycleCandidate.C2])
            // C3: a task whose own record does not say the import made it is tombstoned.
            assertEquals(
                "done: preview=null, tasks=2, cells=2, tombstoned-not-sourced-from-it=1, cells-rewritten=2",
                rollback[LifecycleCandidate.C3],
            )
            // C4: a cell the import never wrote into is "restored" too.
            assertEquals(
                "done: preview=null, tasks=2, cells=3, tombstoned-not-sourced-from-it=0, cells-rewritten=3",
                rollback[LifecycleCandidate.C4],
            )
            // C5 and U1–U3 change nothing about what a rollback touches.
            listOf(LifecycleCandidate.C5, LifecycleCandidate.U1, LifecycleCandidate.U2, LifecycleCandidate.U3).forEach {
                assertEquals(HARMLESS_ROLLBACK, rollback[it], "$it")
            }
        }

    private companion object {
        const val HARMLESS_ROLLBACK = "done: preview=null, tasks=2, cells=2, tombstoned-not-sourced-from-it=0, cells-rewritten=2"
    }
}
