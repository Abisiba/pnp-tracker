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
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.CellTextSelection
import dev.pnptracker.domain.tasks.TaskDraft
import dev.pnptracker.domain.tasks.TaskEditException
import dev.pnptracker.domain.tasks.TaskEditFailure
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
 * One task made in several colours: what is written, and what is not.
 *
 * The whole difference from a batch is in the record. PLAN 5.10 and 12.7 give
 * this **one** task, **one** piece of the cell, one total, one counter and one
 * completion, with a colour relation per colour numbered from the user's own
 * order. So the name appears in the cell once — the colours are how it is drawn,
 * not something the document repeats — and every pool the task belongs to points
 * at the same identity.
 *
 * Everything here runs against a real database in a temporary directory of its
 * own, and the real application file is checked to be untouched afterwards.
 */
class MulticolorTaskTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: TaskFromTextStore
    private lateinit var editing: TaskEditStore
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

    /** A clock that refuses to be read, for the changes that must not write. */
    private class ForbiddenClock : kotlin.time.Clock {
        override fun now(): kotlin.time.Instant = throw AssertionError("the clock was read for a change that saves nothing")
    }

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = TaskFromTextStore(database.taskFromTextDao(), IdGenerator.Random, StoppedClock(updatedAt))
        editing = TaskEditStore(database.taskEditDao(), IdGenerator.Random, StoppedClock(updatedAt))
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

    private suspend fun draftOf(
        colorNames: List<String>,
        quantity: Int = 10,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        notes: String? = null,
    ) = TaskDraft(
        colorIds = colorNames.map { colorNamed(it).id },
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

    /** The whole `Yarasa` fixture: one task in red, yellow and black, ×10. */
    private suspend fun bat(
        text: String = "Basılacak: Yarasa, kutu ayrı.",
        columnType: CellColumnType = CellColumnType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
        colors: List<String> = listOf("Kırmızı", "Sarı", "Siyah"),
    ): Triple<GameEntity, GameCellEntity, EntityId> {
        val game = addGame()
        val cell = addCell(game.id, columnType)
        val segment = addText(cell.id, text)
        val taskId =
            store
                .createTasks(
                    selection = selectionOf(game, cell, segment, "Yarasa", text),
                    drafts = listOf(draftOf(colors, trackingMode = trackingMode)),
                ).single()
        return Triple(game, cell, taskId)
    }

    // ------------------------------------------------------- what is written

    @Test
    fun `one word in three colours makes exactly one task`() =
        runBlocking<Unit> {
            val (_, cell, taskId) = bat()

            val tasks = database.cellSegmentDao().segmentsOfCell(cell.id).mapNotNull { it.taskId }
            assertEquals(listOf(taskId), tasks, "three colours made more than one task")
            val task = assertNotNull(database.taskDao().activeTaskById(taskId))
            assertEquals("Yarasa", task.name)
        }

    @Test
    fun `one word in three colours makes exactly one piece of the cell`() =
        runBlocking<Unit> {
            val (_, cell, taskId) = bat()

            val pieces = database.cellSegmentDao().segmentsOfCell(cell.id)
            assertEquals(1, pieces.count { it.kind == SegmentKind.TASK }, "the cell holds more than one task piece")
            assertEquals(taskId, pieces.single { it.kind == SegmentKind.TASK }.taskId)
        }

    @Test
    fun `the three colours are three relations of the one task`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()

            val colors = database.taskColorDao().colorsOfTask(taskId)
            assertEquals(3, colors.size)
            assertEquals(listOf(taskId, taskId, taskId), colors.map { it.taskId })
        }

    @Test
    fun `the colours are numbered from zero in the order they were chosen`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()

            val colors = database.taskColorDao().colorsOfTask(taskId)
            assertEquals(listOf(0, 1, 2), colors.map { it.slotIndex })
            assertEquals(
                listOf(colorNamed("Kırmızı").id, colorNamed("Sarı").id, colorNamed("Siyah").id),
                colors.map { it.colorId },
            )
        }

    @Test
    fun `the quantity is on the task once and nowhere else`() =
        runBlocking<Unit> {
            val (_, cell, taskId) = bat()

            assertEquals(10, assertNotNull(database.taskDao().activeTaskById(taskId)).requiredQuantity)
            // The count is drawn beside the task and is never written into
            // anybody's words (PLAN 12.5).
            assertTrue("10" !in documentTextOf(cell.id), "the count was written into the cell's text")
        }

    @Test
    fun `the name is in the document once, however many colours there are`() =
        runBlocking<Unit> {
            val (_, cell, _) = bat()

            val document = documentTextOf(cell.id)
            assertEquals("Basılacak: Yarasa, kutu ayrı.", document)
            assertEquals(1, Regex("Yarasa").findAll(document).count(), "the name was repeated per colour")
        }

    @Test
    fun `the words on either side are kept exactly`() =
        runBlocking<Unit> {
            val (_, cell, _) = bat()

            val pieces = database.cellSegmentDao().segmentsOfCell(cell.id)
            assertEquals(
                listOf("Basılacak: ", null, ", kutu ayrı."),
                pieces.map { it.text },
            )
            assertEquals(listOf(0, 1, 2), pieces.map { it.orderIndex })
        }

    @Test
    fun `a several colour task can be made between tasks that are already there`() =
        runBlocking<Unit> {
            val text = "Token, Yarasa, kutu"
            val game = addGame()
            val cell = addCell(game.id)
            val segment = addText(cell.id, text)
            store.createSingleColorTask(
                selection = selectionOf(game, cell, segment, "Token", text),
                colorId = colorNamed("Gri").id,
                requiredQuantity = 4,
                trackingMode = TrackingMode.THREE_D_BATCH,
                notes = null,
            )
            val rest = database.cellSegmentDao().segmentsOfCell(cell.id).last()

            store.createTasks(
                selection = selectionOf(game, cell, rest, "Yarasa", rest.text.orEmpty()),
                drafts = listOf(draftOf(listOf("Kırmızı", "Sarı"))),
            )

            assertEquals(text, documentTextOf(cell.id))
            val pieces = database.cellSegmentDao().segmentsOfCell(cell.id)
            assertEquals(listOf(0, 1, 2, 3), pieces.map { it.orderIndex })
            assertEquals(2, pieces.count { it.kind == SegmentKind.TASK })
        }

    @Test
    fun `several colours are only ever asked of printing`() =
        runBlocking<Unit> {
            // PLAN 5.10 gives colours to three dimensional work alone, so a card
            // or a board piece made in several of them is not a thing to write —
            // and no screen offers it, which makes it a programming mistake.
            listOf(CellColumnType.CARD, CellColumnType.BOARD).forEach { columnType ->
                val game = addGame()
                val cell = addCell(game.id, columnType)
                val text = "Basılacak: Yarasa, kutu ayrı."
                val segment = addText(cell.id, text)

                assertFailsWith<IllegalArgumentException>("$columnType took three colours") {
                    store.createTasks(
                        selection = selectionOf(game, cell, segment, "Yarasa", text),
                        drafts = listOf(draftOf(listOf("Kırmızı", "Sarı", "Siyah"), trackingMode = TrackingMode.PIPELINE)),
                    )
                }

                assertEquals(listOf(SegmentKind.PLAIN_TEXT), database.cellSegmentDao().segmentsOfCell(cell.id).map { it.kind })
            }
        }

    @Test
    fun `the tracking mode and the note are stored once for the whole task`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Yarasa boyanacak"
            val segment = addText(cell.id, text)

            val taskId =
                store
                    .createTasks(
                        selection = selectionOf(game, cell, segment, "Yarasa", text),
                        drafts =
                            listOf(
                                draftOf(
                                    listOf("Kırmızı", "Sarı", "Siyah"),
                                    trackingMode = TrackingMode.THREE_D_BATCH,
                                    notes = "  iki kat  ",
                                ),
                            ),
                    ).single()

            val task = assertNotNull(database.taskDao().activeTaskById(taskId))
            assertEquals(TrackingMode.THREE_D_BATCH, task.trackingMode)
            assertEquals("  iki kat  ", task.notes, "the note was not stored as it was typed")
        }

    // ------------------------------------------------------------- refusals

    @Test
    fun `the same colour twice is refused and nothing is written`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Yarasa"
            val segment = addText(cell.id, text)
            val red = colorNamed("Kırmızı").id

            val refusal =
                assertFailsWith<TaskFromTextException> {
                    store.createTasks(
                        selection = selectionOf(game, cell, segment, "Yarasa", text),
                        drafts =
                            listOf(
                                TaskDraft(listOf(red, colorNamed("Sarı").id, red), 10, TrackingMode.THREE_D_BATCH, null),
                            ),
                    )
                }

            assertEquals(TaskFromTextFailure.DUPLICATE_COLOR, refusal.failure)
            // Both ends of the clash: the slot to change and the one it repeats.
            assertEquals(2, refusal.row, "the refusal does not say which slot to change")
            assertEquals(0, refusal.conflictsWith, "the refusal does not say what it clashes with")
            assertEquals(text, documentTextOf(cell.id))
            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task was left behind")
        }

    @Test
    fun `the same colour in two rows of a batch names both rows`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Token"
            val segment = addText(cell.id, text)
            val red = colorNamed("Kırmızı").id

            val refusal =
                assertFailsWith<TaskFromTextException> {
                    store.createTasks(
                        selection = selectionOf(game, cell, segment, "Token", text),
                        drafts =
                            listOf(
                                TaskDraft(listOf(red), 14, TrackingMode.THREE_D_BATCH, null),
                                TaskDraft(listOf(colorNamed("Sarı").id), 15, TrackingMode.THREE_D_BATCH, null),
                                TaskDraft(listOf(red), 8, TrackingMode.THREE_D_BATCH, null),
                            ),
                    )
                }

            assertEquals(TaskFromTextFailure.DUPLICATE_COLOR, refusal.failure)
            assertEquals(2, refusal.row)
            assertEquals(0, refusal.conflictsWith)
            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task was left behind")
        }

    @Test
    fun `a colour deleted before saving is refused and says which one it was`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Yarasa"
            val segment = addText(cell.id, text)
            val gone = colorNamed("Siyah")
            val before = database.cellSegmentDao().segmentsOfCell(cell.id)
            database.colorDao().deleteColorRow(gone.id)

            val refusal =
                assertFailsWith<TaskFromTextException> {
                    store.createTasks(
                        selection = selectionOf(game, cell, segment, "Yarasa", text),
                        drafts =
                            listOf(
                                TaskDraft(
                                    listOf(colorNamed("Kırmızı").id, colorNamed("Sarı").id, gone.id),
                                    10,
                                    TrackingMode.THREE_D_BATCH,
                                    null,
                                ),
                            ),
                    )
                }

            assertEquals(TaskFromTextFailure.COLOR_NOT_AVAILABLE, refusal.failure)
            assertEquals(2, refusal.row, "the refusal does not say which colour went away")
            assertEquals(before, database.cellSegmentDao().segmentsOfCell(cell.id))
            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task was left behind")
        }

    @Test
    fun `a quantity that is not greater than zero is refused`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Yarasa"
            val segment = addText(cell.id, text)

            listOf(0, -3).forEach { quantity ->
                val refusal =
                    assertFailsWith<TaskFromTextException> {
                        store.createTasks(
                            selection = selectionOf(game, cell, segment, "Yarasa", text),
                            drafts = listOf(draftOf(listOf("Kırmızı", "Sarı"), quantity = quantity)),
                        )
                    }

                assertEquals(TaskFromTextFailure.INVALID_REQUIRED_QUANTITY, refusal.failure)
                assertNull(refusal.row, "one task's quantity was blamed on a row of a batch")
            }
            assertEquals(text, documentTextOf(cell.id))
        }

    @Test
    fun `a selection made against text that has since changed is refused`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Yarasa"
            val segment = addText(cell.id, text)

            val refusal =
                assertFailsWith<TaskFromTextException> {
                    store.createTasks(
                        selection =
                            selectionOf(game, cell, segment, "Yarasa", text)
                                .copy(expectedText = "Basılacak: Yarasalar"),
                        drafts = listOf(draftOf(listOf("Kırmızı", "Sarı"))),
                    )
                }

            assertEquals(TaskFromTextFailure.STALE_TEXT_SELECTION, refusal.failure)
            assertTrue(database.taskColorDao().colorsOfTask(segment.id).isEmpty())
        }

    @Test
    fun `a selection made in a piece that is gone is refused`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Yarasa"
            val segment = addText(cell.id, text)
            val absent = segment.copy(id = IdGenerator.Random.newId())

            val refusal =
                assertFailsWith<TaskFromTextException> {
                    store.createTasks(
                        selection = selectionOf(game, cell, absent, "Yarasa", text),
                        drafts = listOf(draftOf(listOf("Kırmızı", "Sarı"))),
                    )
                }

            assertEquals(TaskFromTextFailure.SEGMENT_NOT_AVAILABLE, refusal.failure)
            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty())
        }

    @Test
    fun `an identity running out halfway leaves nothing behind`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Yarasa, kutu ayrı."
            val segment = addText(cell.id, text)
            val before = database.cellSegmentDao().segmentsOfCell(cell.id)

            // One identity: enough for the task, not for the pieces of the cell.
            val ids = LimitedIds(limit = 1)
            val limited = TaskFromTextStore(database.taskFromTextDao(), ids, StoppedClock(updatedAt))

            assertFailsWith<IllegalStateException> {
                limited.createTasks(
                    selection = selectionOf(game, cell, segment, "Yarasa", text),
                    drafts = listOf(draftOf(listOf("Kırmızı", "Sarı", "Siyah"))),
                )
            }

            assertEquals(before, database.cellSegmentDao().segmentsOfCell(cell.id), "the cell was left changed")
            assertTrue(database.taskDao().allTasksIncludingDeleted().isEmpty(), "a task was left behind")
            assertEquals(text, documentTextOf(cell.id))
        }

    // --------------------------------------------------------------- pools

    @Test
    fun `every colour's pool finds the same one task`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()

            listOf("Kırmızı", "Sarı", "Siyah").forEach { name ->
                assertEquals(
                    listOf(taskId),
                    database.taskColorDao().tasksUsingColor(colorNamed(name).id),
                    "the $name pool does not hold exactly this one task",
                )
            }
        }

    @Test
    fun `a colour's pool never holds the same task twice`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()

            listOf("Kırmızı", "Sarı", "Siyah").forEach { name ->
                val tasks = database.taskColorDao().tasksUsingColor(colorNamed(name).id)
                assertEquals(tasks.distinct(), tasks)
                assertEquals(1, database.taskColorDao().usageCountOfColor(colorNamed(name).id))
            }
            assertEquals(3, database.taskColorDao().colorCountOfTask(taskId))
        }

    @Test
    fun `finishing the task is one record, and it is the same one in every pool`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()

            database.taskProgressDao().completeTask(taskId, StoppedClock(updatedAt), IdGenerator.Random)

            val task = assertNotNull(database.taskDao().taskByIdIncludingDeleted(taskId))
            assertTrue(task.isCompleted)
            // The pools point at the task, so all three see it finished at once.
            listOf("Kırmızı", "Sarı", "Siyah").forEach { name ->
                assertEquals(listOf(taskId), database.taskColorDao().tasksUsingColor(colorNamed(name).id))
            }
            assertEquals(3, database.taskColorDao().colorsOfTask(taskId).size, "finishing changed the colours")
        }

    @Test
    fun `progress does not multiply with the colours`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()

            database.taskProgressDao().reportFailure(
                IdGenerator.Random.newId(),
                taskId,
                quantity = 2,
                clock = StoppedClock(updatedAt),
            )

            // One task, one counter, whatever it is printed in (PLAN 12.7).
            assertEquals(1, database.taskProgressDao().progressEventsOfTask(taskId).size, "an event was written per colour")
            assertEquals(2, assertNotNull(database.taskDao().activeTaskById(taskId)).currentMissingQuantity)
            assertTrue(database.taskProgressDao().stagesOfTask(taskId).isEmpty(), "printing was given a pipeline")
        }

    // ------------------------------------------------------------- editing

    @Test
    fun `reordering the colours keeps the task and its piece of the cell`() =
        runBlocking<Unit> {
            val (_, cell, taskId) = bat()
            val segmentBefore = database.cellSegmentDao().segmentsOfCell(cell.id).single { it.kind == SegmentKind.TASK }
            val reordered =
                listOf(colorNamed("Siyah").id, colorNamed("Kırmızı").id, colorNamed("Sarı").id)

            val changed = editing.editTask(taskId, "Yarasa", reordered, 10, null, TrackingMode.THREE_D_BATCH)

            assertTrue(changed, "reordering the colours was taken as no change at all")
            val colors = database.taskColorDao().colorsOfTask(taskId)
            assertEquals(reordered, colors.map { it.colorId })
            assertEquals(listOf(0, 1, 2), colors.map { it.slotIndex })
            assertEquals(taskId, assertNotNull(database.taskDao().activeTaskById(taskId)).id)
            assertEquals(
                segmentBefore.id,
                database
                    .cellSegmentDao()
                    .segmentsOfCell(cell.id)
                    .single { it.kind == SegmentKind.TASK }
                    .id,
                "the piece of the cell was replaced",
            )
        }

    @Test
    fun `taking a colour away closes the numbering up behind it`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()
            val kept = listOf(colorNamed("Kırmızı").id, colorNamed("Siyah").id)

            editing.editTask(taskId, "Yarasa", kept, 10, null, TrackingMode.THREE_D_BATCH)

            val colors = database.taskColorDao().colorsOfTask(taskId)
            assertEquals(kept, colors.map { it.colorId })
            assertEquals(listOf(0, 1), colors.map { it.slotIndex })
            // Only that colour's pool loses the task; the others still hold it.
            assertTrue(database.taskColorDao().tasksUsingColor(colorNamed("Sarı").id).isEmpty())
            assertEquals(listOf(taskId), database.taskColorDao().tasksUsingColor(colorNamed("Siyah").id))
        }

    @Test
    fun `adding a colour puts it last and leaves the others where they were`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()
            val grown = database.taskColorDao().colorsOfTask(taskId).map { it.colorId } + colorNamed("Beyaz").id

            editing.editTask(taskId, "Yarasa", grown, 10, null, TrackingMode.THREE_D_BATCH)

            val colors = database.taskColorDao().colorsOfTask(taskId)
            assertEquals(grown, colors.map { it.colorId })
            assertEquals(listOf(0, 1, 2, 3), colors.map { it.slotIndex })
            assertEquals(listOf(taskId), database.taskColorDao().tasksUsingColor(colorNamed("Beyaz").id))
        }

    @Test
    fun `a several colour task cannot be emptied down to one colour`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()
            val before = database.taskColorDao().colorsOfTask(taskId)

            val refusal =
                assertFailsWith<TaskEditException> {
                    editing.editTask(
                        taskId,
                        "Yarasa",
                        listOf(colorNamed("Kırmızı").id),
                        10,
                        null,
                        TrackingMode.THREE_D_BATCH,
                    )
                }

            assertEquals(TaskEditFailure.COLOR_COUNT_NOT_CHANGEABLE, refusal.failure)
            assertEquals(before, database.taskColorDao().colorsOfTask(taskId))
        }

    @Test
    fun `a single colour task cannot be given a second colour`() =
        runBlocking<Unit> {
            val game = addGame()
            val cell = addCell(game.id)
            val text = "Basılacak: Token"
            val segment = addText(cell.id, text)
            val taskId =
                store.createSingleColorTask(
                    selection = selectionOf(game, cell, segment, "Token", text),
                    colorId = colorNamed("Gri").id,
                    requiredQuantity = 4,
                    trackingMode = TrackingMode.THREE_D_BATCH,
                    notes = null,
                )

            val refusal =
                assertFailsWith<TaskEditException> {
                    editing.editTask(
                        taskId,
                        "Token",
                        listOf(colorNamed("Gri").id, colorNamed("Sarı").id),
                        4,
                        null,
                        TrackingMode.THREE_D_BATCH,
                    )
                }

            assertEquals(TaskEditFailure.COLOR_COUNT_NOT_CHANGEABLE, refusal.failure)
            assertEquals(1, database.taskColorDao().colorsOfTask(taskId).size)
        }

    @Test
    fun `the same colour twice in an edit is refused`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()
            val before = database.taskColorDao().colorsOfTask(taskId)
            val red = colorNamed("Kırmızı").id

            val refusal =
                assertFailsWith<TaskEditException> {
                    editing.editTask(taskId, "Yarasa", listOf(red, red), 10, null, TrackingMode.THREE_D_BATCH)
                }

            assertEquals(TaskEditFailure.DUPLICATE_COLOR, refusal.failure)
            assertEquals(1, refusal.row, "the refusal does not say which colour to change")
            assertEquals(0, refusal.conflictsWith, "the refusal does not say what it clashes with")
            assertEquals(before, database.taskColorDao().colorsOfTask(taskId))
        }

    @Test
    fun `a colour deleted while the panel was open takes the whole edit back`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()
            database.taskProgressDao().reportFailure(
                IdGenerator.Random.newId(),
                taskId,
                quantity = 4,
                clock = StoppedClock(updatedAt),
            )
            val colorsBefore = database.taskColorDao().colorsOfTask(taskId)
            val taskBefore = assertNotNull(database.taskDao().activeTaskById(taskId))
            val eventsBefore = database.taskProgressDao().progressEventsOfTask(taskId)
            val gone = colorNamed("Beyaz")
            database.colorDao().deleteColorRow(gone.id)

            val refusal =
                assertFailsWith<TaskEditException> {
                    editing.editTask(
                        taskId,
                        "Yarasa Kanadı",
                        colorsBefore.map { it.colorId } + gone.id,
                        20,
                        "iki kat",
                        TrackingMode.THREE_D_BATCH,
                    )
                }

            assertEquals(TaskEditFailure.COLOR_NOT_AVAILABLE, refusal.failure)
            assertEquals(3, refusal.row, "the refusal does not say which entry went away")
            assertEquals(colorsBefore, database.taskColorDao().colorsOfTask(taskId), "the colour list was disturbed")
            assertEquals(taskBefore, assertNotNull(database.taskDao().activeTaskById(taskId)), "the task was changed")
            assertEquals(eventsBefore, database.taskProgressDao().progressEventsOfTask(taskId), "the history was disturbed")
        }

    @Test
    fun `saving the same colours in the same order changes nothing and reads no clock`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()
            val colors = database.taskColorDao().colorsOfTask(taskId).map { it.colorId }
            val quiet = TaskEditStore(database.taskEditDao(), IdGenerator.Random, ForbiddenClock())

            val changed = quiet.editTask(taskId, "Yarasa", colors, 10, null, TrackingMode.THREE_D_BATCH)

            assertTrue(!changed, "saving the same answers was taken as a change")
            assertEquals(
                listOf(0, 1, 2),
                database.taskColorDao().colorsOfTask(taskId).map { it.slotIndex },
            )
        }

    @Test
    fun `the history of a several colour task survives its colours being changed`() =
        runBlocking<Unit> {
            val (_, _, taskId) = bat()
            database.taskProgressDao().reportFailure(
                IdGenerator.Random.newId(),
                taskId,
                quantity = 3,
                clock = StoppedClock(updatedAt),
            )
            val eventsBefore = database.taskProgressDao().progressEventsOfTask(taskId)

            editing.editTask(
                taskId,
                "Yarasa",
                listOf(colorNamed("Siyah").id, colorNamed("Sarı").id, colorNamed("Kırmızı").id),
                10,
                null,
                TrackingMode.THREE_D_BATCH,
            )

            assertEquals(eventsBefore, database.taskProgressDao().progressEventsOfTask(taskId))
            assertEquals(3, assertNotNull(database.taskDao().activeTaskById(taskId)).currentMissingQuantity)
        }
}
