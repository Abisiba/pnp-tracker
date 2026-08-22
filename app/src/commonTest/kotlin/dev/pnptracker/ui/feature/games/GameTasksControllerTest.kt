package dev.pnptracker.ui.feature.games

import dev.pnptracker.data.repository.TaskSetup
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.tasks.TaskSetupException
import dev.pnptracker.domain.tasks.TaskSetupFailure
import dev.pnptracker.domain.tasks.TaskSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a task the fake was asked to create looked like. */
private data class CreatedTask(
    val itemId: EntityId,
    val name: String,
    val poolType: PoolType,
    val trackingMode: TrackingMode,
    val requiredQuantity: Int?,
    val notes: String?,
)

/** Storage a test can push new lists through, so the controller can be watched. */
private class FakeTaskSetup : TaskSetup {
    val tasksByGame = mutableMapOf<EntityId, MutableStateFlow<List<TaskSummary>>>()
    val created = mutableListOf<CreatedTask>()
    var failWith: TaskSetupFailure? = null
    var breakWith: Throwable? = null

    /** Held open so a test can look at the controller while a save is in flight. */
    var heldSave: CompletableDeferred<Unit>? = null

    fun tasksFlow(gameId: EntityId) = tasksByGame.getOrPut(gameId) { MutableStateFlow(emptyList()) }

    override fun observeTasks(gameId: EntityId): Flow<List<TaskSummary>> = tasksFlow(gameId)

    override suspend fun createTask(
        itemId: EntityId,
        name: String,
        poolType: PoolType,
        trackingMode: TrackingMode,
        requiredQuantity: Int?,
        notes: String?,
    ): EntityId {
        heldSave?.await()
        breakWith?.let { throw it }
        failWith?.let { throw TaskSetupException(it) }
        created += CreatedTask(itemId, name, poolType, trackingMode, requiredQuantity, notes)
        return IdGenerator.Random.newId()
    }
}

class GameTasksControllerTest {
    private val gameId: EntityId = IdGenerator.Random.newId()
    private val tokens: EntityId = IdGenerator.Random.newId()
    private val cards: EntityId = IdGenerator.Random.newId()

    private fun aTask(
        name: String,
        poolType: PoolType = PoolType.THREE_D,
        cellId: EntityId = tokens,
        columnType: CellColumnType = CellColumnType.THREE_D,
        trackingMode: TrackingMode = TrackingMode.THREE_D_BATCH,
    ) = TaskSummary(
        id = IdGenerator.Random.newId(),
        cellId = cellId,
        columnType = columnType,
        poolType = poolType,
        trackingMode = trackingMode,
        name = name,
    )

    private fun withTasks(
        setup: FakeTaskSetup,
        body: suspend CoroutineScope.(GameTasksController) -> Unit,
    ) = runBlocking {
        val controller = GameTasksController(setup)
        val job = launch { controller.observeTasks(gameId) }
        yield()
        try {
            body(controller)
        } finally {
            job.cancelAndJoin()
        }
    }

    /** Fills in everything the form needs, so a test can change one thing at a time. */
    private fun GameTasksController.fillIn(
        name: String = "Gri token",
        itemId: EntityId? = tokens,
        poolType: PoolType = PoolType.THREE_D,
    ) {
        startComposer(itemId)
        editName(name)
        choosePool(poolType)
    }

    @Test
    fun `it starts out loading`() {
        assertIs<GameTasksState.Loading>(GameTasksController(FakeTaskSetup()).state.tasks)
    }

    @Test
    fun `a game with no tasks is its own state`() {
        withTasks(FakeTaskSetup()) { controller ->
            assertIs<GameTasksState.Empty>(controller.state.tasks)
        }
    }

    @Test
    fun `tasks arrive split into pool sections`() {
        val setup = FakeTaskSetup()
        setup.tasksFlow(gameId).value =
            listOf(
                aTask("Ayraç", PoolType.SPECIAL, trackingMode = TrackingMode.CHECKLIST),
                aTask("Olay kartı", PoolType.CARD, cellId = cards, columnType = CellColumnType.CARD, trackingMode = TrackingMode.PIPELINE),
                aTask("Gri token"),
            )
        withTasks(setup) { controller ->
            val content = assertIs<GameTasksState.Content>(controller.state.tasks)

            assertEquals(
                listOf(PoolType.THREE_D, PoolType.CARD, PoolType.SPECIAL),
                content.groups.map { it.poolType },
                "the sections are not in the order the pools are listed in",
            )
            assertEquals(
                listOf("Gri token"),
                content.groups
                    .first()
                    .tasks
                    .map { it.name },
            )
        }
    }

    @Test
    fun `a section is only there when the pool has something in it`() {
        val setup = FakeTaskSetup()
        setup.tasksFlow(gameId).value = listOf(aTask("Gri token"))
        withTasks(setup) { controller ->
            val content = assertIs<GameTasksState.Content>(controller.state.tasks)

            assertEquals(listOf(PoolType.THREE_D), content.groups.map { it.poolType })
        }
    }

    @Test
    fun `every row says which column it is written in`() {
        val setup = FakeTaskSetup()
        setup.tasksFlow(gameId).value =
            listOf(
                aTask("Gri token"),
                aTask("Olay kartı", PoolType.CARD, cellId = cards, columnType = CellColumnType.CARD, trackingMode = TrackingMode.PIPELINE),
            )
        withTasks(setup) { controller ->
            val content = assertIs<GameTasksState.Content>(controller.state.tasks)
            val byName = content.groups.flatMap { it.tasks }.associateBy { it.name }

            assertEquals(CellColumnType.THREE_D, assertNotNull(byName["Gri token"]).columnType)
            assertEquals(CellColumnType.CARD, assertNotNull(byName["Olay kartı"]).columnType)
        }
    }

    @Test
    fun `opening another game does not leave the last one's tasks behind`() {
        val setup = FakeTaskSetup()
        setup.tasksFlow(gameId).value = listOf(aTask("Gri token"))
        withTasks(setup) { controller ->
            assertIs<GameTasksState.Content>(controller.state.tasks)

            controller.forget()

            assertIs<GameTasksState.Loading>(controller.state.tasks)
        }
    }

    @Test
    fun `the form starts closed`() {
        withTasks(FakeTaskSetup()) { controller ->
            assertNull(controller.state.composer)
        }
    }

    @Test
    fun `the form opens on the cell the user came from`() {
        withTasks(FakeTaskSetup()) { controller ->
            controller.startComposer(tokens)

            assertEquals(tokens, assertNotNull(controller.state.composer).cellId)
        }
    }

    @Test
    fun `the user can aim the form at another cell of the same game`() {
        withTasks(FakeTaskSetup()) { controller ->
            controller.startComposer(tokens)

            controller.chooseCell(cards)

            assertEquals(cards, assertNotNull(controller.state.composer).cellId)
        }
    }

    @Test
    fun `giving up on the form writes nothing`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.fillIn()

            controller.cancelComposer()

            assertNull(controller.state.composer)
            assertEquals(0, setup.created.size, "a task was written by giving up on the form")
        }
    }

    @Test
    fun `a blank name cannot be saved and writes nothing`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.fillIn(name = "   ")

            assertTrue(!assertNotNull(controller.state.composer).canSave)
            controller.save()

            assertEquals(0, setup.created.size)
            assertNotNull(controller.state.composer, "the form closed on a name that was never saved")
        }
    }

    @Test
    fun `a task cannot be saved before a cell is chosen`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.fillIn(itemId = null)

            assertTrue(!assertNotNull(controller.state.composer).canSave)
            controller.save()

            assertEquals(0, setup.created.size)
        }
    }

    @Test
    fun `a task cannot be saved before a pool is chosen`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.startComposer(tokens)
            controller.editName("Gri token")

            assertTrue(!assertNotNull(controller.state.composer).canSave)
            controller.save()

            assertEquals(0, setup.created.size)
        }
    }

    @Test
    fun `a pool that allows one tracking mode has it chosen outright`() {
        withTasks(FakeTaskSetup()) { controller ->
            controller.startComposer(tokens)

            controller.choosePool(PoolType.CARD)

            val composer = assertNotNull(controller.state.composer)
            assertEquals(TrackingMode.PIPELINE, composer.trackingMode)
            assertTrue(!composer.offersTrackingChoice, "a pool with one mode should not be asking")
        }
    }

    @Test
    fun `a pool that allows two tracking modes waits for the user to say`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.startComposer(tokens)
            controller.editName("Ayraç")

            controller.choosePool(PoolType.SPECIAL)

            val composer = assertNotNull(controller.state.composer)
            assertNull(composer.trackingMode, "a mode was guessed for a pool that allows two")
            assertTrue(composer.offersTrackingChoice)
            assertTrue(!composer.canSave, "the task could be saved without a tracking mode")
            controller.save()
            assertEquals(0, setup.created.size)
        }
    }

    @Test
    fun `choosing one of the two modes finishes the special pool form`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.startComposer(tokens)
            controller.editName("Ayraç")
            controller.choosePool(PoolType.SPECIAL)

            controller.chooseTracking(TrackingMode.COUNTED)

            assertTrue(assertNotNull(controller.state.composer).canSave)
            controller.save()
            assertEquals(TrackingMode.COUNTED, setup.created.single().trackingMode)
        }
    }

    @Test
    fun `a tracking mode the pool does not allow is not taken`() {
        withTasks(FakeTaskSetup()) { controller ->
            controller.startComposer(tokens)
            controller.choosePool(PoolType.THREE_D)

            controller.chooseTracking(TrackingMode.CHECKLIST)

            assertEquals(TrackingMode.THREE_D_BATCH, assertNotNull(controller.state.composer).trackingMode)
        }
    }

    @Test
    fun `changing the pool drops a mode the new pool would not allow`() {
        withTasks(FakeTaskSetup()) { controller ->
            controller.startComposer(tokens)
            controller.choosePool(PoolType.SPECIAL)
            controller.chooseTracking(TrackingMode.COUNTED)

            controller.choosePool(PoolType.CARD)

            assertEquals(TrackingMode.PIPELINE, assertNotNull(controller.state.composer).trackingMode)
        }
    }

    @Test
    fun `a quantity left empty means the amount is unknown`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.fillIn()

            controller.save()

            assertNull(setup.created.single().requiredQuantity, "an empty box became a number")
        }
    }

    @Test
    fun `a quantity that was typed reaches storage as a number`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.fillIn()
            controller.editQuantity(" 14 ")

            controller.save()

            assertEquals(14, setup.created.single().requiredQuantity)
        }
    }

    @Test
    fun `a quantity of zero cannot be saved`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.fillIn()
            controller.editQuantity("0")

            val composer = assertNotNull(controller.state.composer)
            assertTrue(composer.hasUnusableQuantity)
            assertTrue(!composer.canSave)
            controller.save()
            assertEquals(0, setup.created.size)
        }
    }

    @Test
    fun `a quantity that is not a number at all cannot be saved`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.fillIn()
            controller.editQuantity("birkaç")

            assertTrue(assertNotNull(controller.state.composer).hasUnusableQuantity)
            controller.save()
            assertEquals(0, setup.created.size)
        }
    }

    @Test
    fun `everything the user typed reaches storage`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.startComposer(tokens)
            controller.editName("Gri token")
            controller.choosePool(PoolType.THREE_D)
            controller.editQuantity("14")
            controller.editNotes("Destek gerekmiyor")

            controller.save()

            assertEquals(
                CreatedTask(tokens, "Gri token", PoolType.THREE_D, TrackingMode.THREE_D_BATCH, 14, "Destek gerekmiyor"),
                setup.created.single(),
            )
        }
    }

    @Test
    fun `a saved task closes the form`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.fillIn()

            controller.save()

            assertNull(controller.state.composer)
            assertNull(controller.state.failure)
        }
    }

    @Test
    fun `a task that was saved turns up in the list without being asked for`() {
        val setup = FakeTaskSetup()
        withTasks(setup) { controller ->
            controller.fillIn()
            controller.save()

            // What the database would push back down the stream afterwards.
            setup.tasksFlow(gameId).value = listOf(aTask("Gri token"))
            yield()

            val content = assertIs<GameTasksState.Content>(controller.state.tasks)
            assertEquals(
                listOf("Gri token"),
                content.groups
                    .single()
                    .tasks
                    .map { it.name },
            )
        }
    }

    @Test
    fun `a second click while the first save is still going writes only one task`() =
        runBlocking {
            val setup = FakeTaskSetup()
            val held = CompletableDeferred<Unit>()
            setup.heldSave = held
            val controller = GameTasksController(setup)
            controller.fillIn()

            // Unconfined, so the save really has started by the time it suspends.
            val first = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.save() }
            assertTrue(controller.isSaving, "the first save is not in flight")

            controller.save()

            held.complete(Unit)
            first.join()
            assertEquals(1, setup.created.size, "one insistent click became two tasks")
            assertTrue(!controller.isSaving)
        }

    @Test
    fun `a task that did not save leaves the form open with what was typed in it`() {
        val setup = FakeTaskSetup()
        setup.failWith = TaskSetupFailure.COULD_NOT_SAVE
        withTasks(setup) { controller ->
            controller.fillIn()

            controller.save()

            assertEquals(TaskSetupFailure.COULD_NOT_SAVE, controller.state.failure)
            assertEquals("Gri token", assertNotNull(controller.state.composer).name)
            assertTrue(!controller.isSaving)
        }
    }

    @Test
    fun `a cell that is gone is reported as itself rather than as a saving problem`() {
        val setup = FakeTaskSetup()
        setup.failWith = TaskSetupFailure.CELL_NOT_AVAILABLE
        withTasks(setup) { controller ->
            controller.fillIn()

            controller.save()

            assertEquals(TaskSetupFailure.CELL_NOT_AVAILABLE, controller.state.failure)
        }
    }

    @Test
    fun `a saved task clears the error the last attempt left`() {
        val setup = FakeTaskSetup()
        setup.failWith = TaskSetupFailure.COULD_NOT_SAVE
        withTasks(setup) { controller ->
            controller.fillIn()
            controller.save()
            assertNotNull(controller.state.failure)

            setup.failWith = null
            controller.save()

            assertNull(controller.state.failure)
        }
    }

    @Test
    fun `an error nobody expected is not dressed up as a saving problem`() {
        val setup = FakeTaskSetup()
        setup.breakWith = IllegalStateException("a rule the code got wrong")
        withTasks(setup) { controller ->
            controller.fillIn()

            assertFailsWith<IllegalStateException> { controller.save() }

            assertNull(controller.state.failure, "a programming fault was shown to the user as a saving problem")
            assertTrue(!controller.isSaving, "the saving flag was left stuck on")
        }
    }
}
