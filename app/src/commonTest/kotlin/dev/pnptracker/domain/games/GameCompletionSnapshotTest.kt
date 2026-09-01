package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The picture a confirmation is answered against, on its own.
 *
 * Two questions, and both of them are decisions rather than storage: how much
 * work the user is being asked to declare finished, and whether the game they
 * are answering about is still the game they were shown. Neither needs a
 * database to be wrong, so neither is tested through one.
 */
class GameCompletionSnapshotTest {
    private fun task(
        taskId: EntityId = IdGenerator.Random.newId(),
        isCompleted: Boolean = false,
        missing: Int = 0,
        required: Int? = 20,
    ) = GameTaskSnapshot(taskId, isCompleted, missing, required)

    private fun game(
        vararg tasks: GameTaskSnapshot,
        isCompleted: Boolean = false,
    ) = GameCompletionSnapshot(isCompleted, tasks.toList())

    @Test
    fun `a game with nothing in it has nothing to be asked about`() {
        val empty = game()

        assertEquals(0, empty.unfinishedCount)
        assertFalse(empty.needsConfirmation, "a game with no work in it was going to be asked about")
    }

    @Test
    fun `a game whose work is all done has nothing to be asked about`() {
        val done = game(task(isCompleted = true), task(isCompleted = true))

        assertEquals(0, done.unfinishedCount)
        assertFalse(done.needsConfirmation)
    }

    @Test
    fun `only the unfinished tasks are counted`() {
        val mixed = game(task(), task(isCompleted = true), task())

        assertEquals(2, mixed.unfinishedCount)
        assertTrue(mixed.needsConfirmation)
    }

    @Test
    fun `two readings of one unchanged game match however they are ordered`() {
        val tasks = List(4) { task() }
        val read = game(*tasks.toTypedArray())
        val readAgain = game(*tasks.reversed().toTypedArray())

        assertTrue(read.matches(readAgain), "the same game read in another order looked like a different one")
    }

    @Test
    fun `a task finished since does not match`() {
        val first = task()
        val second = task()

        assertFalse(game(first, second).matches(game(first, second.copy(isCompleted = true))))
    }

    @Test
    fun `a debt taken on since does not match`() {
        val task = task()

        assertFalse(game(task).matches(game(task.copy(currentMissingQuantity = 3))))
    }

    @Test
    fun `a total changed since does not match`() {
        // The stages of a task are counted up to its total, so a total that
        // moved is a different amount of work being agreed to.
        val task = task(required = 20)

        assertFalse(game(task).matches(game(task.copy(requiredQuantity = 40))))
    }

    @Test
    fun `a task added or taken away since does not match`() {
        val kept = task()

        assertFalse(game(kept).matches(game(kept, task())), "a task added since was agreed to unseen")
        assertFalse(game(kept, task()).matches(game(kept)), "a task that has gone still counted")
    }

    @Test
    fun `a game finished by somebody else since does not match`() {
        val task = task()

        assertFalse(game(task).matches(game(task, isCompleted = true)))
    }
}
