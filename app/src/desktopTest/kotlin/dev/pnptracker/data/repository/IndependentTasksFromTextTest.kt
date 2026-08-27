package dev.pnptracker.data.repository

import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.entity.CellSegmentEntity
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.database.entity.GameCellEntity
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.database.insertSegmentDirectly
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskFromTextException
import dev.pnptracker.domain.tasks.TaskFromTextFailure
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Making several tasks out of one stretch of the user's own words.
 *
 * The rule these are all about is PLAN 12.7's: what comes out is N tasks that
 * have nothing to do with one another. They start with the same name because the
 * user typed it once, and that is the entire relationship — no group, no parent,
 * no shared counter, nothing in the database that could later make a change to
 * one of them reach another.
 *
 * The other half is that the cell still says what it said. Three tasks take the
 * place of one word without a character being added between them: the room the
 * table shows between the tasks is drawn beside their counts and is no part of
 * anybody's note.
 */
class IndependentTasksFromTextTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: TaskFromTextStore
    private var realDatabaseExistedBefore = false

    /** Hands out identities until it is asked once too often. */
    private class LimitedIds(
        private val limit: Int,
    ) : IdGenerator {
        var reads: Int = 0
            private set

        override fun newId(): EntityId {
            reads++
            check(reads <= limit) { "no more identities" }
            return IdGenerator.Random.newId()
        }
    }

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = TaskFromTextStore(database.taskFromTextDao(), IdGenerator.Random, StoppedClock(updatedAt))
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    // ------------------------------------------------------------- fixtures

    private suspend fun addGame(name: String = "Harmonies"): GameEntity {
        val game = aGame(name = name)
        database.gameDao().insert(game)
        return game
    }

    private suspend fun addCell(
        gameId: EntityId,
        columnType: CellColumnType = CellColumnType.THREE_D,
    ): GameCellEntity {
        val cell = aCell(gameId = gameId, columnType = columnType)
        database.gameCellDao().insert(cell)
        return cell
    }

    private suspend fun addText(
        cellId: EntityId,
        text: String,
        orderIndex: Int = 0,
    ): CellSegmentEntity {
        val segment =
            CellSegmentEntity.plainText(
                id = IdGenerator.Random.newId(),
                cellId = cellId,
                orderIndex = orderIndex,
                text = text,
                moment = createdAt,
            )
        insertSegmentDirectly(database, segment)
        return segment
    }

    private suspend fun colorNamed(name: String): ColorEntity =
        assertNotNull(
            database.colorDao().allColors().firstOrNull { it.canonicalName == name },
            "the catalogue has no colour called $name",
        )

    private fun selectionOf(
        game: GameEntity,
        cell: GameCellEntity,
        segment: CellSegmentEntity,
        word: String,
        text: String = segment.text.orEmpty(),
    ) = CellTextSelection(
        gameId = game.id,
        cellId = cell.id,
        segmentId = segment.id,
        expectedText = text,
        startOffset = text.indexOf(word),
        endOffset = text.indexOf(word) + word.length,
    )

    private suspend fun draft(
        colorName: String,
        quantity: Int,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        notes: String? = null,
    ) = TaskDraft(
        colorIds = listOf(colorNamed(colorName).id),
        requiredQuantity = quantity,
        trackingMode = trackingMode,
        notes = notes,
    )

    /** What the cell reads as: its pieces in order, joined by nothing at all. */
    private suspend fun documentTextOf(cellId: EntityId): String {
        val words =
            database.cellSegmentDao().segmentsOfCell(cellId).map { piece ->
                piece.text ?: assertNotNull(database.taskDao().taskByIdIncludingDeleted(piece.taskId!!)).name
            }
        return words.joinToString(separator = "")
    }

    private suspend fun segmentsOf(cellId: EntityId) = database.cellSegmentDao().segmentsOfCell(cellId)

    private suspend fun colorNameOf(taskId: EntityId): String {
        val colorId =
            database
                .colorDao()
                .colorsOfTask(taskId)
                .single()
                .colorId
        return assertNotNull(database.colorDao().allColors().firstOrNull { it.id == colorId }).canonicalName
    }

    /** The three-task fixture PLAN 12.7 is written around. */
    private suspend fun threeTokens(
        text: String = "Basılacak: Token, kutu ayrı.",
        columnType: CellColumnType = CellColumnType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
    ): Triple<GameCellEntity, List<EntityId>, String> {
        val game = addGame()
        val cell = addCell(game.id, columnType)
        val segment = addText(cell.id, text)
        val ids =
            store.createTasks(
                selection = selectionOf(game, cell, segment, "Token"),
                drafts =
                    listOf(
                        draft("Siyah", 14, trackingMode),
                        draft("Beyaz", 15, trackingMode),
                        draft("Sarı", 8, trackingMode),
                    ),
            )
        return Triple(cell, ids, text)
    }

    // --------------------------------------------- what a batch creates

    @Test
    fun `one selection with three colours becomes three tasks`() {
        runBlocking {
            val (_, ids, _) = threeTokens()

            assertEquals(3, ids.size)
            val tasks = ids.map { assertNotNull(database.taskDao().activeTaskById(it)) }
            assertEquals(listOf("Token", "Token", "Token"), tasks.map { it.name })
            assertEquals(listOf(14, 15, 8), tasks.map { it.requiredQuantity })
            assertEquals(listOf("Siyah", "Beyaz", "Sarı"), ids.map { colorNameOf(it) })
        }
    }

    @Test
    fun `the three tasks are three identities`() {
        runBlocking {
            val (_, ids, _) = threeTokens()

            assertEquals(3, ids.toSet().size, "two of the tasks are the same record")
        }
    }

    @Test
    fun `the three tasks are three pieces of the cell`() {
        runBlocking {
            val (cell, ids, _) = threeTokens()

            val taskPieces = segmentsOf(cell.id).filter { it.kind == SegmentKind.TASK }
            assertEquals(3, taskPieces.map { it.id }.toSet().size, "two tasks share a piece")
            assertEquals(ids, taskPieces.map { it.taskId }, "the pieces do not name the tasks in order")
        }
    }

    @Test
    fun `each task carries its own single colour`() {
        runBlocking {
            val (_, ids, _) = threeTokens()

            ids.forEach { taskId ->
                val colors = database.colorDao().colorsOfTask(taskId)
                assertEquals(1, colors.size, "a task of a batch carries more than its own colour")
            }
            assertEquals(3, ids.map { colorNameOf(it) }.toSet().size, "two tasks were given the same colour")
        }
    }

    @Test
    fun `nothing is written that ties the three tasks together`() {
        runBlocking {
            // The strongest form of PLAN 12.7 available to a test: every column
            // the three rows have is compared, and the only ones they are allowed
            // to agree on are the ones that describe them separately. A groupId
            // or a parentTaskId could not be added to the schema without this
            // failing, because it would be a column they all share a value in.
            val (cell, ids, _) = threeTokens()
            val rows = ids.map { assertNotNull(database.taskDao().activeTaskById(it)) }

            assertEquals(3, rows.map { it.id }.toSet().size)
            assertEquals(3, rows.map { it.requiredQuantity }.toSet().size)
            rows.forEach { row ->
                assertEquals(0, row.currentMissingQuantity, "a batch wrote progress nobody made")
                assertEquals(false, row.isCompleted)
                assertNull(row.completedAt)
                assertNull(row.sourceRawImportBlockId, "a task chosen out of the user's own text claims an import")
            }
            // And nothing outside the task rows either: the pieces are separate
            // rows of the cell, each naming exactly one task.
            val taskPieces = segmentsOf(cell.id).filter { it.kind == SegmentKind.TASK }
            assertEquals(taskPieces.size, taskPieces.mapNotNull { it.taskId }.toSet().size)
        }
    }

    @Test
    fun `the pieces sit in the order the rows were given`() {
        runBlocking {
            val (cell, ids, _) = threeTokens()

            val pieces = segmentsOf(cell.id)
            assertEquals(listOf(0, 1, 2, 3, 4), pieces.map { it.orderIndex }, "the reading order has a gap")
            assertEquals(
                listOf(
                    SegmentKind.PLAIN_TEXT,
                    SegmentKind.TASK,
                    SegmentKind.TASK,
                    SegmentKind.TASK,
                    SegmentKind.PLAIN_TEXT,
                ),
                pieces.map { it.kind },
            )
            assertEquals(ids, pieces.filter { it.kind == SegmentKind.TASK }.map { it.taskId })
        }
    }

    // ------------------------------------------ what the cell still says

    @Test
    fun `the selected word becomes the three names, with nothing between them`() {
        runBlocking {
            val (cell, _, _) = threeTokens()

            // Three tasks stand where one word did, so the cell is three names
            // long there — but not one character is invented between them. The
            // room the table shows there is drawn beside the counts (PLAN 12.7)
            // and is no part of anybody's note.
            assertEquals("Basılacak: TokenTokenToken, kutu ayrı.", documentTextOf(cell.id))
        }
    }

    @Test
    fun `the selected text is consumed once and no more`() {
        runBlocking {
            val (cell, ids, _) = threeTokens()

            // The word is gone from the plain text exactly once: three tasks
            // were made out of one selection, not one out of each.
            val plainText = segmentsOf(cell.id).mapNotNull { it.text }.joinToString("")
            assertEquals("Basılacak: , kutu ayrı.", plainText)
            assertEquals(3, ids.size)
        }
    }

    @Test
    fun `the words on either side keep every character`() {
        runBlocking {
            val (cell, _, _) = threeTokens()

            assertEquals(
                listOf("Basılacak: ", null, null, null, ", kutu ayrı."),
                segmentsOf(cell.id).map { it.text },
            )
        }
    }

    @Test
    fun `line endings, doubled spaces and indentation come through untouched`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "İlk satır\r\n\tGirintili  Token  ×14\n\nSon satır"
            val segment = addText(cell.id, text)

            store.createTasks(
                selection = selectionOf(game, cell, segment, "Token"),
                drafts = listOf(draft("Siyah", 14), draft("Beyaz", 15)),
            )

            assertEquals("İlk satır\r\n\tGirintili  TokenToken  ×14\n\nSon satır", documentTextOf(cell.id))
            assertEquals("İlk satır\r\n\tGirintili  ", segmentsOf(cell.id).first().text)
            assertEquals("  ×14\n\nSon satır", segmentsOf(cell.id).last().text)
        }
    }

    @Test
    fun `a batch made in a cell that already holds tasks disturbs neither of them`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Knight, Token ve Meeple"
            val segment = addText(cell.id, text)
            // A task before the selection and a task after it, made one at a time.
            val knight =
                store
                    .createTasks(
                        selectionOf(game, cell, segment, "Knight"),
                        listOf(draft("Gri", 3)),
                    ).single()
            val afterKnight = assertNotNull(segmentsOf(cell.id).lastOrNull { it.kind == SegmentKind.PLAIN_TEXT })
            val meeple =
                store
                    .createTasks(
                        selectionOf(game, cell, afterKnight, "Meeple", afterKnight.text!!),
                        listOf(draft("Yeşil", 4)),
                    ).single()
            val middle =
                assertNotNull(
                    segmentsOf(cell.id).firstOrNull {
                        it.kind == SegmentKind.PLAIN_TEXT && it.text!!.contains("Token")
                    },
                )
            val knightBefore = assertNotNull(database.taskDao().activeTaskById(knight))
            val meepleBefore = assertNotNull(database.taskDao().activeTaskById(meeple))

            val tokens =
                store.createTasks(
                    selection = selectionOf(game, cell, middle, "Token", middle.text!!),
                    drafts = listOf(draft("Siyah", 14), draft("Beyaz", 15), draft("Sarı", 8)),
                )

            assertEquals("Knight, TokenTokenToken ve Meeple", documentTextOf(cell.id))
            assertEquals(knightBefore, database.taskDao().activeTaskById(knight), "the task before the batch changed")
            assertEquals(meepleBefore, database.taskDao().activeTaskById(meeple), "the task after the batch changed")
            assertEquals(
                listOf(knight) + tokens + listOf(meeple),
                segmentsOf(cell.id).filter { it.kind == SegmentKind.TASK }.map { it.taskId },
                "the existing tasks lost their places among the new ones",
            )
            assertEquals(List(7) { it }, segmentsOf(cell.id).map { it.orderIndex })
        }
    }

    // ------------------------------------------------------ the pipelines

    @Test
    fun `every card task of a batch gets its own three stages`() {
        runBlocking {
            val (_, ids, _) =
                threeTokens(
                    text = "60 Token kart basılacak",
                    columnType = CellColumnType.CARD,
                    trackingMode = TrackingMode.PIPELINE,
                )

            ids.forEach { taskId ->
                assertEquals(
                    listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT),
                    database.taskProgressDao().stagesOfTask(taskId).map { it.stage },
                )
                assertTrue(
                    database.taskProgressDao().stagesOfTask(taskId).all { it.completedQuantity == 0 },
                    "a stage of a new task claims work already done",
                )
            }
            // Every stage row belongs to exactly one of them: no pipeline is
            // shared, so progress on one can never show up on another.
            assertEquals(
                9,
                ids
                    .flatMap { database.taskProgressDao().stagesOfTask(it) }
                    .map { it.taskId to it.stage }
                    .toSet()
                    .size,
            )
        }
    }

    @Test
    fun `every board task of a batch gets its own three stages`() {
        runBlocking {
            val (_, ids, _) =
                threeTokens(
                    text = "Token mukavva parçaları",
                    columnType = CellColumnType.BOARD,
                    trackingMode = TrackingMode.PIPELINE,
                )

            ids.forEach { taskId ->
                assertEquals(
                    listOf(ProductionStage.PRINT, ProductionStage.GLUE, ProductionStage.CUT),
                    database.taskProgressDao().stagesOfTask(taskId).map { it.stage },
                )
            }
        }
    }

    @Test
    fun `a special cell keeps each task's own tracking mode`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id, CellColumnType.SPECIAL)
            val segment = addText(cell.id, "Token ve diğerleri")

            val ids =
                store.createTasks(
                    selection = selectionOf(game, cell, segment, "Token"),
                    drafts =
                        listOf(
                            draft("Siyah", 14, TrackingMode.CHECKLIST),
                            draft("Beyaz", 15, TrackingMode.COUNTED),
                        ),
                )

            assertEquals(
                listOf(TrackingMode.CHECKLIST, TrackingMode.COUNTED),
                ids.map { assertNotNull(database.taskDao().activeTaskById(it)).trackingMode },
                "two tasks made together were given the same tracking",
            )
        }
    }

    @Test
    fun `each task keeps its own note, exactly as it was typed`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "Token")

            val ids =
                store.createTasks(
                    selection = selectionOf(game, cell, segment, "Token"),
                    drafts =
                        listOf(
                            draft("Siyah", 14, notes = "  iki yedek  "),
                            draft("Beyaz", 15, notes = null),
                        ),
                )

            assertEquals(
                listOf("  iki yedek  ", null),
                ids.map { assertNotNull(database.taskDao().activeTaskById(it)).notes },
            )
        }
    }

    // ---------------------------------------------------------- refusals

    private suspend fun assertRefuses(
        expected: TaskFromTextFailure,
        cellId: EntityId,
        document: String,
        attempt: suspend () -> Unit,
    ) {
        val before = segmentsOf(cellId)
        val refusal = assertFailsWith<TaskFromTextException> { attempt() }

        assertEquals(expected, refusal.failure)
        assertEquals(document, documentTextOf(cellId), "the cell did not come back")
        assertEquals(before, segmentsOf(cellId), "the pieces did not come back")
        assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task was left behind")
    }

    @Test
    fun `two rows naming the same colour are refused rather than folded into one`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "Token")

            assertRefuses(TaskFromTextFailure.DUPLICATE_COLOR, cell.id, "Token") {
                store.createTasks(
                    selection = selectionOf(game, cell, segment, "Token"),
                    drafts = listOf(draft("Siyah", 14), draft("Beyaz", 15), draft("Siyah", 8)),
                )
            }
        }
    }

    @Test
    fun `a quantity of zero or less anywhere in the batch refuses all of it`() {
        runBlocking {
            listOf(0, -3).forEach { bad ->
                val game = addGame()
                val cell = addCell(game.id)
                val segment = addText(cell.id, "Token")

                assertRefuses(TaskFromTextFailure.INVALID_REQUIRED_QUANTITY, cell.id, "Token") {
                    store.createTasks(
                        selection = selectionOf(game, cell, segment, "Token"),
                        drafts = listOf(draft("Siyah", 14), draft("Beyaz", bad)),
                    )
                }
            }
        }
    }

    @Test
    fun `a colour that has been deleted anywhere in the batch refuses all of it`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "Token")

            assertRefuses(TaskFromTextFailure.COLOR_NOT_AVAILABLE, cell.id, "Token") {
                store.createTasks(
                    selection = selectionOf(game, cell, segment, "Token"),
                    drafts =
                        listOf(
                            draft("Siyah", 14),
                            TaskDraft(listOf(IdGenerator.Random.newId()), 15, TrackingMode.THREE_D_BATCH, null),
                        ),
                )
            }
        }
    }

    @Test
    fun `a batch with no rows at all is refused`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "Token")

            assertRefuses(TaskFromTextFailure.NO_TASK_DESCRIBED, cell.id, "Token") {
                store.createTasks(selectionOf(game, cell, segment, "Token"), emptyList())
            }
        }
    }

    @Test
    fun `a selection made before the text changed is refused`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "Token ve kutu")
            // What the panel believed the piece said. The offsets it carries
            // would land somewhere the user never pointed at.
            val stale = selectionOf(game, cell, segment, "Token").copy(expectedText = "Meeple ve kutu")

            assertRefuses(TaskFromTextFailure.STALE_TEXT_SELECTION, cell.id, "Token ve kutu") {
                store.createTasks(stale, listOf(draft("Siyah", 14), draft("Beyaz", 15)))
            }
        }
    }

    @Test
    fun `a selection that reaches a task piece is refused`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "Token")
            store.createTasks(selectionOf(game, cell, segment, "Token"), listOf(draft("Siyah", 14)))
            val taskPiece = assertNotNull(segmentsOf(cell.id).firstOrNull { it.kind == SegmentKind.TASK })

            val refusal =
                assertFailsWith<TaskFromTextException> {
                    store.createTasks(
                        CellTextSelection(game.id, cell.id, taskPiece.id, "Token", 0, 5),
                        listOf(draft("Beyaz", 15), draft("Sarı", 8)),
                    )
                }

            assertEquals(TaskFromTextFailure.SEGMENT_IS_NOT_PLAIN_TEXT, refusal.failure)
            assertEquals(1, database.taskDao().allTasksIncludingDeleted().size)
        }
    }

    @Test
    fun `a selection cutting a character in half is refused`() {
        runBlocking {
            val game = addGame()
            val cell = addCell(game.id)
            // An emoji is two UTF-16 units; between them is not a place.
            val text = "Token 🎲 kutusu"
            val segment = addText(cell.id, text)
            val half = text.indexOf('\uD83C') + 1

            assertRefuses(TaskFromTextFailure.INVALID_SELECTION, cell.id, text) {
                store.createTasks(
                    CellTextSelection(game.id, cell.id, segment.id, text, 0, half),
                    listOf(draft("Siyah", 14), draft("Beyaz", 15)),
                )
            }
        }
    }

    // ---------------------------------------------------------- rollback

    private suspend fun assertRollsBackWith(limit: Int) {
        val game = addGame()
        val cell = addCell(game.id)
        val text = "Basılacak: Token, kutu ayrı."
        val segment = addText(cell.id, text)
        val before = segmentsOf(cell.id)
        val starved =
            TaskFromTextStore(database.taskFromTextDao(), LimitedIds(limit), StoppedClock(updatedAt))

        assertFailsWith<IllegalStateException> {
            starved.createTasks(
                selection = selectionOf(game, cell, segment, "Token"),
                drafts = listOf(draft("Siyah", 14), draft("Beyaz", 15), draft("Sarı", 8)),
            )
        }

        assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task survived the rollback")
        assertEquals(0, database.taskProgressDao().stagesOfTask(IdGenerator.Random.newId()).size)
        assertEquals(text, documentTextOf(cell.id), "the text did not come back")
        assertEquals(before, segmentsOf(cell.id), "the pieces did not come back")
    }

    @Test
    fun `running out of identities after the first task leaves nothing behind`() {
        runBlocking { assertRollsBackWith(limit = 1) }
    }

    @Test
    fun `running out of identities after every task leaves nothing behind`() {
        runBlocking { assertRollsBackWith(limit = 3) }
    }

    @Test
    fun `running out of identities while writing the pieces leaves nothing behind`() {
        runBlocking {
            // Three for the tasks, then one for each piece of the cell. Stopping
            // in the middle of the pieces is the case where rows for the tasks
            // are already written and the cell is halfway renumbered.
            assertRollsBackWith(limit = 5)
        }
    }

    @Test
    fun `a batch is all of the tasks or none of them`() {
        runBlocking {
            // The refusal comes from the last row, after the first two have been
            // checked: nothing may have been written by then.
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, "Token")

            assertRefuses(TaskFromTextFailure.INVALID_REQUIRED_QUANTITY, cell.id, "Token") {
                store.createTasks(
                    selection = selectionOf(game, cell, segment, "Token"),
                    drafts = listOf(draft("Siyah", 14), draft("Beyaz", 15), draft("Sarı", 0)),
                )
            }
            assertEquals(0, database.colorDao().colorsOfTask(IdGenerator.Random.newId()).size)
        }
    }

    // -------------------------------------------- independence afterwards

    /** Everything the three tasks are, so a change can be seen against it. */
    private suspend fun snapshotOf(ids: List<EntityId>) =
        ids.map { taskId ->
            Triple(
                database.taskDao().activeTaskById(taskId),
                database.colorDao().colorsOfTask(taskId),
                database.taskProgressDao().stagesOfTask(taskId),
            )
        }

    private fun editing() = TaskEditStore(database.taskEditDao(), IdGenerator.Random, StoppedClock(updatedAt))

    @Test
    fun `renaming one of the three renames one of the three`() {
        runBlocking {
            val (cell, ids, _) = threeTokens()
            val others = snapshotOf(ids.drop(1))

            assertTrue(
                editing().editTask(
                    taskId = ids.first(),
                    name = "Siyah token",
                    colorId = null,
                    requiredQuantity = 14,
                    notes = null,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                ),
            )

            assertEquals("Siyah token", assertNotNull(database.taskDao().activeTaskById(ids.first())).name)
            assertEquals(others, snapshotOf(ids.drop(1)), "renaming one task changed another")
            // The cell says the new name in that one place and the old one in
            // the other two: the names were never one thing.
            assertEquals("Basılacak: Siyah tokenTokenToken, kutu ayrı.", documentTextOf(cell.id))
        }
    }

    @Test
    fun `changing one quantity changes one quantity`() {
        runBlocking {
            val (_, ids, _) = threeTokens()
            val others = snapshotOf(ids.drop(1))

            editing().editTask(ids.first(), "Token", null, 40, null, TrackingMode.THREE_D_BATCH)

            assertEquals(40, assertNotNull(database.taskDao().activeTaskById(ids.first())).requiredQuantity)
            assertEquals(others, snapshotOf(ids.drop(1)), "changing one quantity changed another task")
            assertEquals(listOf(15, 8), ids.drop(1).map { assertNotNull(database.taskDao().activeTaskById(it)).requiredQuantity })
        }
    }

    @Test
    fun `changing one colour changes one colour`() {
        runBlocking {
            val (_, ids, _) = threeTokens()
            val others = snapshotOf(ids.drop(1))

            editing().editTask(ids.first(), "Token", colorNamed("Yeşil").id, 14, null, TrackingMode.THREE_D_BATCH)

            assertEquals("Yeşil", colorNameOf(ids.first()))
            assertEquals(others, snapshotOf(ids.drop(1)), "changing one colour changed another task")
            assertEquals(listOf("Beyaz", "Sarı"), ids.drop(1).map { colorNameOf(it) })
        }
    }

    @Test
    fun `turning one of the three back into text leaves the other two standing`() {
        runBlocking {
            val (cell, ids, _) = threeTokens()
            val others = snapshotOf(ids.drop(1))

            assertTrue(editing().convertTaskToText(ids.first()))

            assertNull(database.taskDao().taskByIdIncludingDeleted(ids.first()), "the task record survived")
            assertEquals(others, snapshotOf(ids.drop(1)), "converting one task changed another")
            // The words stay where they were and the other two are still tasks.
            assertEquals("Basılacak: TokenTokenToken, kutu ayrı.", documentTextOf(cell.id))
            assertEquals(
                ids.drop(1),
                segmentsOf(cell.id).filter { it.kind == SegmentKind.TASK }.map { it.taskId },
            )
            assertEquals(List(4) { it }, segmentsOf(cell.id).map { it.orderIndex })
        }
    }

    @Test
    fun `progress recorded on one of the three is on one of the three`() {
        runBlocking {
            val (_, ids, _) =
                threeTokens(
                    text = "60 Token kart basılacak",
                    columnType = CellColumnType.CARD,
                    trackingMode = TrackingMode.PIPELINE,
                )

            database.taskProgressDao().setStageQuantity(ids.first(), ProductionStage.PRINT, 9, StoppedClock(updatedAt))

            assertEquals(
                listOf(9, 0, 0),
                database.taskProgressDao().stagesOfTask(ids.first()).map { it.completedQuantity },
            )
            ids.drop(1).forEach { taskId ->
                assertTrue(
                    database.taskProgressDao().stagesOfTask(taskId).all { it.completedQuantity == 0 },
                    "work done on one task showed up on another",
                )
                assertTrue(database.taskProgressDao().progressEventsOfTask(taskId).isEmpty())
            }
        }
    }

    @Test
    fun `finishing one of the three finishes one of the three`() {
        runBlocking {
            val (_, ids, _) = threeTokens()

            assertTrue(database.taskProgressDao().completeTask(ids.first(), StoppedClock(updatedAt), IdGenerator.Random))

            assertTrue(assertNotNull(database.taskDao().activeTaskById(ids.first())).isCompleted)
            ids.drop(1).forEach { taskId ->
                val task = assertNotNull(database.taskDao().activeTaskById(taskId))
                assertEquals(false, task.isCompleted, "finishing one task finished another")
                assertNull(task.completedAt)
            }
        }
    }

    @Test
    fun `each of the three is reachable by its own colour alone`() {
        runBlocking {
            // What a pool screen will ask later: give me the tasks of this
            // colour. Each of the three answers for itself and for no other.
            val (_, ids, _) = threeTokens()

            listOf("Siyah" to 14, "Beyaz" to 15, "Sarı" to 8).forEach { (name, quantity) ->
                val colorId = colorNamed(name).id
                val matching = ids.filter { database.colorDao().colorsOfTask(it).any { row -> row.colorId == colorId } }
                assertEquals(1, matching.size, "the colour $name reaches more or fewer than one task")
                assertEquals(quantity, assertNotNull(database.taskDao().activeTaskById(matching.single())).requiredQuantity)
            }
        }
    }
}
