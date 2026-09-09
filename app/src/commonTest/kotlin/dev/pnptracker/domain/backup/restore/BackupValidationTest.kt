package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BackupColorRow
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupDraftTaskColorRow
import dev.pnptracker.domain.backup.BackupGameCellRow
import dev.pnptracker.domain.backup.BackupTaskColorRow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ABSENT = "99999999-9999-4999-8999-999999999999"

/**
 * Every way a backup can be well formed JSON and still not describe a database.
 *
 * The documents here are built as records and written by the real writer, so each
 * one carries a correct checksum and gets all the way past the envelope. What is
 * left is the only thing being tested: whether the values and the shape of the
 * graph are ones this application could have produced.
 *
 * Nothing is repaired anywhere. PLAN 14.4.2 has values travel exactly as they
 * are, so every one of these is a refusal and none of them is a correction —
 * a reader that trimmed a name or clamped a count would hand back data the user
 * never had and call it their backup.
 */
class BackupValidationTest {
    private val probe = CountingProbe()
    private val reader = UntrustedBackupReader(probe)

    @Test
    fun `an identifier that is not a canonical UUID is refused`() =
        runBlocking<Unit> {
            val notAUuid = listOf("", "kırmızı", "aa000000000040008000000000000001", COLOR.uppercase(), "$COLOR ")
            notAUuid.forEach { text ->
                val data = withColor { it.copy(id = text) }
                assertEquals(BackupProblem.INVALID_ID, refusalOf(data), "accepted '$text' as an identifier")
            }
            // The optional ones are checked when they are there, and not when
            // they are absent.
            val badOptional = aWholeBackup().let { whole -> whole.copy(games = whole.games.map { it.copy(sourceImportBatchId = "x") }) }
            assertEquals(BackupProblem.INVALID_ID, refusalOf(badOptional))
            assertEquals(BackupPlace("games", "sourceImportBatchId"), placeOf(badOptional))
        }

    @Test
    fun `an enum value the application does not have is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            val broken =
                listOf(
                    BackupPlace("tasks", "poolType") to whole.copy(tasks = whole.tasks.map { it.copy(poolType = "MINIATURE") }),
                    BackupPlace("tasks", "trackingMode") to whole.copy(tasks = whole.tasks.map { it.copy(trackingMode = "SOMEHOW") }),
                    BackupPlace("gameCells", "columnType") to
                        whole.copy(gameCells = whole.gameCells.map { it.copy(columnType = "SLEEVES") }),
                    BackupPlace("cellSegments", "kind") to
                        whole.copy(cellSegments = whole.cellSegments.map { it.copy(kind = "IMAGE") }),
                    BackupPlace("taskStages", "stage") to whole.copy(taskStages = whole.taskStages.map { it.copy(stage = "VARNISH") }),
                    BackupPlace("progressEvents", "kind") to
                        whole.copy(progressEvents = whole.progressEvents.map { it.copy(kind = "LOST") }),
                    BackupPlace("historyEvents", "kind") to
                        whole.copy(historyEvents = whole.historyEvents.map { it.copy(kind = "GAME_RENAMED") }),
                    BackupPlace("importBatches", "status") to
                        whole.copy(importBatches = whole.importBatches.map { it.copy(status = "PENDING") }),
                    BackupPlace("importBatches", "sourceFormat") to
                        whole.copy(importBatches = whole.importBatches.map { it.copy(sourceFormat = "ODS") }),
                    BackupPlace("rawImportBlocks", "sourceColumnType") to
                        whole.copy(rawImportBlocks = whole.rawImportBlocks.map { it.copy(sourceColumnType = "OTHER") }),
                    BackupPlace("rawImportBlocks", "gameCompletionHint") to
                        whole.copy(rawImportBlocks = whole.rawImportBlocks.map { it.copy(gameCompletionHint = "MAYBE") }),
                    BackupPlace("draftTasks", "selectedPoolType") to
                        whole.copy(draftTasks = whole.draftTasks.map { it.copy(selectedPoolType = "MINIATURE") }),
                )
            broken.forEach { (place, data) ->
                assertEquals(BackupProblem.INVALID_ENUM, refusalOf(data), "accepted an unknown value at $place")
                assertEquals(place, placeOf(data))
            }
        }

    @Test
    fun `an enum name in the wrong case is not the enum name`() =
        runBlocking<Unit> {
            // Enums travel as the name they are stored under (PLAN 14.4.1), and
            // matching loosely would let a file decide its own spelling.
            val data = aWholeBackup().let { whole -> whole.copy(tasks = whole.tasks.map { it.copy(poolType = "three_d") }) }
            assertEquals(BackupProblem.INVALID_ENUM, refusalOf(data))
        }

    @Test
    fun `a count that cannot be what it says is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            val broken =
                listOf(
                    // PLAN 5.12: an event is about at least one piece.
                    whole.copy(progressEvents = whole.progressEvents.map { it.copy(quantity = 0) }),
                    whole.copy(progressEvents = whole.progressEvents.map { it.copy(quantity = -3) }),
                    whole.copy(tasks = whole.tasks.map { it.copy(requiredQuantity = 0) }),
                    whole.copy(tasks = whole.tasks.map { it.copy(requiredQuantity = -1) }),
                    whole.copy(tasks = whole.tasks.map { it.copy(currentMissingQuantity = -1) }),
                    whole.copy(taskStages = whole.taskStages.map { it.copy(completedQuantity = -1) }),
                    whole.copy(importBatches = whole.importBatches.map { it.copy(createdTaskCount = -1) }),
                    whole.copy(rawImportBlocks = whole.rawImportBlocks.map { it.copy(rowIndex = -1) }),
                )
            broken.forEach { data ->
                assertEquals(BackupProblem.INVALID_VALUE, refusalOf(data))
            }
            // A quantity nobody has decided yet is a different thing from a wrong
            // one, and stays allowed.
            val unknown = whole.copy(tasks = whole.tasks.map { it.copy(requiredQuantity = null) })
            assertTrue(reader.read(fileOf(documentOf(unknown))) is BackupReadResult.Valid)
        }

    @Test
    fun `a moment outside what this application could have recorded is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            assertEquals(
                BackupProblem.INVALID_VALUE,
                refusalOf(whole.copy(games = whole.games.map { it.copy(createdAt = -1) })),
            )
            assertEquals(
                BackupProblem.INVALID_VALUE,
                refusalOf(whole.copy(games = whole.games.map { it.copy(createdAt = Long.MAX_VALUE, updatedAt = Long.MAX_VALUE) })),
            )
            assertEquals(
                BackupProblem.INVALID_VALUE,
                refusalOf(whole.copy(tasks = whole.tasks.map { it.copy(deletedAt = -5) })),
            )
        }

    @Test
    fun `a row that changed before it was written is refused`() =
        runBlocking<Unit> {
            // PLAN 11.4.4 decides whether an imported task has been touched by
            // comparing these two, so a row whose moments run backwards would
            // make an import that cannot be taken back look like one that can.
            val whole = aWholeBackup()
            val data = whole.copy(tasks = whole.tasks.map { it.copy(createdAt = UPDATED, updatedAt = CREATED) })

            assertEquals(BackupProblem.DOMAIN_INVARIANT, refusalOf(data))
            assertEquals(BackupPlace("tasks", "updatedAt"), placeOf(data))
        }

    @Test
    fun `a colour that does not agree with itself is refused`() =
        runBlocking<Unit> {
            assertEquals(BackupProblem.INVALID_VALUE, refusalOf(withColor { it.copy(canonicalName = "   ") }))
            // The normalised name is what an import looks a term up by (PLAN
            // 5.11); a row whose two names disagree answers to something it is
            // not called.
            assertEquals(BackupProblem.INVALID_VALUE, refusalOf(withColor { it.copy(normalizedName = "mavi") }))
            assertEquals(BackupProblem.INVALID_VALUE, refusalOf(withColor { it.copy(hex = "kırmızı") }))
            assertEquals(BackupProblem.INVALID_VALUE, refusalOf(withColor { it.copy(hex = "#FF00") }))

            val whole = aWholeBackup()
            assertEquals(
                BackupProblem.INVALID_VALUE,
                refusalOf(whole.copy(colorAliases = whole.colorAliases.map { it.copy(normalizedAlias = "başka") })),
            )
        }

    @Test
    fun `an import fingerprint that is not one is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            assertEquals(
                BackupProblem.INVALID_VALUE,
                refusalOf(whole.copy(importBatches = whole.importBatches.map { it.copy(sha256 = "ABC") })),
            )
            assertEquals(
                BackupProblem.INVALID_VALUE,
                refusalOf(whole.copy(importBatches = whole.importBatches.map { it.copy(fileName = " ") })),
            )
        }

    @Test
    fun `a task whose fields contradict each other is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()

            // PLAN 6.4: finished exactly when there is a moment it was finished.
            assertEquals(
                BackupProblem.DOMAIN_INVARIANT,
                refusalOf(whole.copy(tasks = whole.tasks.map { it.copy(isCompleted = true) })),
            )
            assertEquals(
                BackupProblem.DOMAIN_INVARIANT,
                refusalOf(whole.copy(tasks = whole.tasks.map { it.copy(completedAt = UPDATED) })),
            )
            // PLAN 10: missing or borrowed, never both.
            assertEquals(
                BackupProblem.DOMAIN_INVARIANT,
                refusalOf(whole.copy(tasks = whole.tasks.map { it.copy(isMissing = true, isBorrowed = true) })),
            )
            // PLAN 3.4: a pool tracks its work one way.
            assertEquals(
                BackupProblem.DOMAIN_INVARIANT,
                refusalOf(whole.copy(tasks = whole.tasks.map { it.copy(poolType = "THREE_D", trackingMode = "PIPELINE") })),
            )
            assertEquals(
                BackupProblem.DOMAIN_INVARIANT,
                refusalOf(whole.copy(tasks = whole.tasks.map { it.copy(requiredQuantity = 2, currentMissingQuantity = 5) })),
            )
        }

    @Test
    fun `a piece of a cell that is neither words nor a task is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            val broken =
                listOf(
                    // PLAN 5.5: words carry text and name no task.
                    aSegment(SEGMENT_TEXT, 0, "PLAIN_TEXT", text = null, taskId = null),
                    aSegment(SEGMENT_TEXT, 0, "PLAIN_TEXT", text = "", taskId = null),
                    aSegment(SEGMENT_TEXT, 0, "PLAIN_TEXT", text = "Notum", taskId = TASK_PRINTED),
                    // And a task piece reads its words from its task.
                    aSegment(SEGMENT_TEXT, 0, "TASK", text = null, taskId = null),
                    aSegment(SEGMENT_TEXT, 0, "TASK", text = "kendi metni", taskId = TASK_PIPELINE),
                )
            broken.forEach { segment ->
                val data = whole.copy(cellSegments = listOf(segment) + whole.cellSegments.drop(1))
                assertEquals(BackupProblem.DOMAIN_INVARIANT, refusalOf(data), "accepted $segment")
            }
        }

    @Test
    fun `a history line carrying what its kind does not is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            val line = whole.historyEvents.first()
            val other = whole.historyEvents.drop(1)
            val broken =
                listOf(
                    // A game level line names no task, and a task level one must.
                    line.copy(kind = "GAME_DELETED", taskId = TASK_PRINTED),
                    line.copy(kind = "TASK_COMPLETED", taskId = null),
                    // The only kind that carries counts needs all three of them.
                    line.copy(kind = "TASK_STAGE_QUANTITY_CHANGED", stage = null, previousQuantity = 1, newQuantity = 2),
                    line.copy(kind = "TASK_STAGE_QUANTITY_CHANGED", stage = "PRINT", previousQuantity = null, newQuantity = 2),
                    line.copy(kind = "TASK_STAGE_QUANTITY_CHANGED", stage = "PRINT", previousQuantity = 1, newQuantity = null),
                    // A step that did not move is not an event.
                    line.copy(kind = "TASK_STAGE_QUANTITY_CHANGED", stage = "PRINT", previousQuantity = 2, newQuantity = 2),
                    // And a kind that carries no counts may not carry any.
                    line.copy(stage = "PRINT"),
                    line.copy(previousQuantity = 0),
                    line.copy(newQuantity = 7),
                )
            broken.forEach { changed ->
                val data = whole.copy(historyEvents = listOf(changed) + other)
                assertEquals(BackupProblem.DOMAIN_INVARIANT, refusalOf(data), "accepted $changed")
            }
            // A count below nothing is a wrong value rather than a wrong shape.
            val negative = line.copy(kind = "TASK_STAGE_QUANTITY_CHANGED", stage = "PRINT", previousQuantity = -1, newQuantity = 2)
            assertEquals(BackupProblem.INVALID_VALUE, refusalOf(whole.copy(historyEvents = listOf(negative) + other)))
        }

    @Test
    fun `an import range that is half known or upside down is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            val batch = whole.importBatches.single()
            val broken =
                listOf(
                    batch.copy(endRowIndex = null),
                    batch.copy(startColumnIndex = null),
                    batch.copy(startRowIndex = 4, endRowIndex = 0),
                    batch.copy(startColumnIndex = 6, endColumnIndex = 0),
                )
            broken.forEach { changed ->
                assertEquals(BackupProblem.DOMAIN_INVARIANT, refusalOf(whole.copy(importBatches = listOf(changed))), "accepted $changed")
            }
            // A range nobody detected is written as no range at all, and that is
            // a shape the format has.
            val unknown =
                batch.copy(startRowIndex = null, endRowIndex = null, startColumnIndex = null, endColumnIndex = null)
            assertTrue(reader.read(fileOf(documentOf(whole.copy(importBatches = listOf(unknown))))) is BackupReadResult.Valid)
        }

    @Test
    fun `a completion target belonging to a hint nobody accepted is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            val data =
                whole.copy(
                    rawImportBlocks = whole.rawImportBlocks.map { it.copy(gameCompletionHint = "PENDING", completionTargetGameId = GAME) },
                )
            assertEquals(BackupProblem.DOMAIN_INVARIANT, refusalOf(data))
            assertEquals(BackupPlace("rawImportBlocks", "completionTargetGameId"), placeOf(data))
        }

    @Test
    fun `a draft with half a selection, or an empty one, is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            val draft = whole.draftTasks.single()
            val broken =
                listOf(
                    draft.copy(selectionEndIndex = null),
                    draft.copy(selectionStartIndex = null),
                    draft.copy(selectionStartIndex = 5, selectionEndIndex = 5),
                    draft.copy(selectionStartIndex = 9, selectionEndIndex = 3),
                    draft.copy(selectedPoolType = "CARD", selectedTrackingMode = "THREE_D_BATCH"),
                    draft.copy(isMissing = true, isBorrowed = true),
                )
            broken.forEach { changed ->
                assertEquals(BackupProblem.DOMAIN_INVARIANT, refusalOf(whole.copy(draftTasks = listOf(changed))), "accepted $changed")
            }
            assertEquals(
                BackupProblem.INVALID_VALUE,
                refusalOf(whole.copy(draftTasks = listOf(draft.copy(selectionStartIndex = -1, selectionEndIndex = 4)))),
            )
            assertEquals(BackupProblem.INVALID_VALUE, refusalOf(whole.copy(draftTasks = listOf(draft.copy(name = "  ")))))
        }

    @Test
    fun `two rows claiming the same key are refused, simple keys and composite ones alike`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            val colour = whole.colors.single()
            val broken =
                listOf(
                    // The same identifier twice.
                    whole.copy(colors = listOf(colour, colour.copy(canonicalName = "Mavi", normalizedName = "mavi"))),
                    // The same name twice, which the catalogue's unique index refuses.
                    whole.copy(colors = listOf(colour, colour.copy(id = ABSENT))),
                    // A composite primary key twice.
                    whole.copy(taskColors = whole.taskColors + BackupTaskColorRow(TASK_PRINTED, COLOR, slotIndex = 1)),
                    // A game with the same column twice (PLAN 5.4).
                    whole.copy(
                        gameCells = whole.gameCells + BackupGameCellRow(ABSENT, GAME, "THREE_D", CREATED, UPDATED),
                    ),
                    // One task written into two places, which PLAN 16 forbids.
                    whole.copy(cellSegments = whole.cellSegments + aSegment(ABSENT, 3, "TASK", null, TASK_PRINTED)),
                    // And one task materialised by two drafts.
                    whole.copy(draftTasks = whole.draftTasks + whole.draftTasks.single().copy(id = ABSENT)),
                )
            broken.forEach { data ->
                assertEquals(BackupProblem.DUPLICATE_RECORD, refusalOf(data))
            }
        }

    @Test
    fun `several rows with nothing in a nullable unique column are not duplicates`() =
        runBlocking<Unit> {
            // The database's unique indexes let nulls repeat, and a cell full of
            // plain text is exactly that case.
            val whole = aWholeBackup()
            val data =
                whole.copy(
                    cellSegments = whole.cellSegments + aSegment(ABSENT, 3, "PLAIN_TEXT", "ikinci not", null),
                    draftTasks = whole.draftTasks + whole.draftTasks.single().copy(id = ABSENT, materializedTaskId = null),
                )

            assertTrue(reader.read(fileOf(documentOf(data))) is BackupReadResult.Valid)
        }

    @Test
    fun `every reference the schema declares is checked, and each one breaks on its own`() =
        runBlocking<Unit> {
            // Driven by the contract rather than by a list written out here, so a
            // foreign key added to the format without a check reaches this test
            // as a missing branch below rather than as a broken restore.
            assertEquals(21, backupReferences.size, "the set of references changed; every one of them needs a case")
            backupReferences.forEach { reference ->
                val data = pointingNowhere(reference)
                assertEquals(BackupProblem.BROKEN_REFERENCE, refusalOf(data), "a dangling ${reference.field} was accepted")
                assertEquals(BackupPlace(reference.from, reference.field), placeOf(data))
            }
        }

    @Test
    fun `a hole in anything the format keeps in order is refused`() =
        runBlocking<Unit> {
            val whole = aWholeBackup()
            val gaps =
                listOf(
                    // PLAN 16: a cell's pieces are ordered and gapless.
                    whole.copy(cellSegments = whole.cellSegments.dropLast(1) + whole.cellSegments.last().copy(orderIndex = 3)),
                    whole.copy(taskColors = listOf(BackupTaskColorRow(TASK_PRINTED, COLOR, slotIndex = 1))),
                    whole.copy(taskStages = whole.taskStages.dropLast(1) + whole.taskStages.last().copy(orderIndex = 3)),
                    whole.copy(draftTaskColors = listOf(BackupDraftTaskColorRow(DRAFT, COLOR, slotIndex = 1))),
                )
            gaps.forEach { data ->
                assertEquals(BackupProblem.DOMAIN_INVARIANT, refusalOf(data))
            }
        }

    @Test
    fun `nothing is tried out on a database while anything is still wrong with the file`() =
        runBlocking<Unit> {
            assertEquals(BackupProblem.INVALID_ID, refusalOf(withColor { it.copy(id = "x") }))
            assertEquals(BackupProblem.BROKEN_REFERENCE, refusalOf(pointingNowhere(backupReferences.first())))

            assertEquals(0, probe.asked, "a file that had already been refused was put into a database")
        }

    private fun withColor(change: (BackupColorRow) -> BackupColorRow): BackupData =
        aWholeBackup().let { whole -> whole.copy(colors = whole.colors.map(change)) }

    /**
     * The same backup with one reference pointing at something that is not there.
     *
     * Written out one case per reference. The alternative — reaching into a row
     * by field name — would need the records to be maps, and the point of this
     * test is that the format's own types are what gets broken.
     */
    private fun pointingNowhere(reference: BackupReference): BackupData {
        val whole = aWholeBackup()
        return when ("${reference.from}.${reference.field}") {
            "colorAliases.colorId" -> whole.copy(colorAliases = whole.colorAliases.map { it.copy(colorId = ABSENT) })
            "games.sourceImportBatchId" -> whole.copy(games = whole.games.map { it.copy(sourceImportBatchId = ABSENT) })
            "gameCells.gameId" -> whole.copy(gameCells = whole.gameCells.map { it.copy(gameId = ABSENT) })
            "rawImportBlocks.importBatchId" -> whole.copy(rawImportBlocks = whole.rawImportBlocks.map { it.copy(importBatchId = ABSENT) })
            "rawImportBlocks.completionTargetGameId" ->
                whole.copy(
                    rawImportBlocks =
                        whole.rawImportBlocks.map {
                            it.copy(gameCompletionHint = "ACCEPTED", completionTargetGameId = ABSENT)
                        },
                )
            "tasks.sourceRawImportBlockId" -> whole.copy(tasks = whole.tasks.map { it.copy(sourceRawImportBlockId = ABSENT) })
            "cellSegments.cellId" -> whole.copy(cellSegments = whole.cellSegments.map { it.copy(cellId = ABSENT) })
            "cellSegments.taskId" ->
                whole.copy(
                    cellSegments =
                        whole.cellSegments.dropLast(1) + whole.cellSegments.last().copy(taskId = ABSENT),
                )
            "taskColors.taskId" -> whole.copy(taskColors = whole.taskColors.map { it.copy(taskId = ABSENT) })
            "taskColors.colorId" -> whole.copy(taskColors = whole.taskColors.map { it.copy(colorId = ABSENT) })
            "taskStages.taskId" -> whole.copy(taskStages = whole.taskStages.map { it.copy(taskId = ABSENT) })
            "progressEvents.taskId" -> whole.copy(progressEvents = whole.progressEvents.map { it.copy(taskId = ABSENT) })
            "historyEvents.gameId" -> whole.copy(historyEvents = whole.historyEvents.map { it.copy(gameId = ABSENT) })
            "historyEvents.taskId" ->
                whole.copy(historyEvents = whole.historyEvents.map { if (it.taskId == null) it else it.copy(taskId = ABSENT) })
            "importBatchCells.importBatchId" ->
                whole.copy(importBatchCells = whole.importBatchCells.map { it.copy(importBatchId = ABSENT) })
            "importBatchCells.cellId" -> whole.copy(importBatchCells = whole.importBatchCells.map { it.copy(cellId = ABSENT) })
            "draftTasks.rawImportBlockId" -> whole.copy(draftTasks = whole.draftTasks.map { it.copy(rawImportBlockId = ABSENT) })
            "draftTasks.targetCellId" -> whole.copy(draftTasks = whole.draftTasks.map { it.copy(targetCellId = ABSENT) })
            "draftTasks.materializedTaskId" -> whole.copy(draftTasks = whole.draftTasks.map { it.copy(materializedTaskId = ABSENT) })
            "draftTaskColors.draftTaskId" -> whole.copy(draftTaskColors = whole.draftTaskColors.map { it.copy(draftTaskId = ABSENT) })
            "draftTaskColors.colorId" -> whole.copy(draftTaskColors = whole.draftTaskColors.map { it.copy(colorId = ABSENT) })
            else -> error("no case for the reference ${reference.from}.${reference.field}")
        }
    }

    private suspend fun refusalOf(data: BackupData): BackupProblem =
        (reader.read(fileOf(documentOf(data))) as BackupReadResult.Refused).rejection.problem

    private suspend fun placeOf(data: BackupData): BackupPlace =
        (reader.read(fileOf(documentOf(data))) as BackupReadResult.Refused).rejection.place
}
