package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.domain.importhealth.DraftHealth
import dev.pnptracker.domain.importremoval.DraftRemovalOutcome
import dev.pnptracker.domain.importremoval.DraftRemovalRefusal
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportBatchStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

private val FIRST = IdGenerator.Random.newId()
private val SECOND = IdGenerator.Random.newId()
private val THIRD = IdGenerator.Random.newId()

/**
 * Driving the list of unfinished imports without a database.
 *
 * The rules themselves — which draft contradicts itself, whether a removal may
 * go ahead — are proved against a real database elsewhere. What is proved here
 * is that the controller decides none of them again, opens nothing on the
 * strength of a stale list, and never lets a second press start a second check
 * or a second transaction.
 */
class UnfinishedImportsControllerTest {
    private fun rows() =
        listOf(
            anUnfinishedImport(FIRST, "bir.csv"),
            anUnfinishedImport(SECOND, "iki.xlsx", isContradicting = true, mayBeHeldByRecords = true),
            anUnfinishedImport(THIRD, "üç.csv"),
        )

    /** Runs [body] with the list being collected, the way the section collects it. */
    private fun observing(
        fake: FakeUnfinishedImports = FakeUnfinishedImports(rows()),
        body: suspend CoroutineScope.(UnfinishedImportsController, FakeUnfinishedImports) -> Unit,
    ) = runBlocking {
        val controller = UnfinishedImportsController(fake)
        val job = launch(Dispatchers.Unconfined) { controller.observeUnfinishedImports() }
        yield()
        body(controller, fake)
        job.cancelAndJoin()
    }

    @Test
    fun `the list is split into sound and contradicting drafts in its own order`() =
        observing { controller, _ ->
            val ready = assertIs<UnfinishedImportsState.Ready>(controller.list)
            assertEquals(listOf(FIRST, THIRD), ready.sound.map { it.batchId })
            assertEquals(listOf(SECOND), ready.contradicting.map { it.batchId })
        }

    @Test
    fun `a list storage would not read is its own state and not an empty list`() {
        val fake = FakeUnfinishedImports(rows()).apply { listUnreadable = true }
        observing(fake) { controller, _ -> assertEquals(UnfinishedImportsState.Unreadable, controller.list) }
    }

    @Test
    fun `a sound draft is opened only after its fresh check says so`() =
        observing { controller, fake ->
            controller.open(FIRST)

            assertEquals(1, fake.healthCalls)
            assertEquals(FIRST, controller.readyToOpen)
            assertEquals(OpenAttempt.Idle, controller.opening)
            controller.openHonoured()
            assertNull(controller.readyToOpen, "the token could fire twice")
        }

    @Test
    fun `a stale list does not get a draft into the review screen`() {
        val answers =
            mapOf(
                FakeUnfinishedImports.contradicting(FIRST) to OpenRefusal.RECORDS_CONTRADICT,
                DraftHealth.NotFound(FIRST) to OpenRefusal.NO_LONGER_THERE,
                DraftHealth.NotADraft(FIRST, ImportBatchStatus.CONFIRMED) to OpenRefusal.NO_LONGER_A_DRAFT,
            )
        answers.forEach { (health, refusal) ->
            val fake = FakeUnfinishedImports(rows()).apply { this.health[FIRST] = health }
            observing(fake) { controller, _ ->
                controller.open(FIRST)

                assertNull(controller.readyToOpen, "$health reached the review screen")
                assertEquals(OpenAttempt.Refused(rows()[0], refusal), controller.opening)
            }
        }
        val unreadable = FakeUnfinishedImports(rows()).apply { healthUnreadable = true }
        observing(unreadable) { controller, _ ->
            controller.open(FIRST)
            assertNull(controller.readyToOpen)
            assertEquals(OpenAttempt.Refused(rows()[0], OpenRefusal.COULD_NOT_READ), controller.opening)
        }
    }

    @Test
    fun `a defect while checking is not turned into a refusal`() {
        val fake = FakeUnfinishedImports(rows()).apply { healthThrows = IllegalStateException("a defect") }
        observing(fake) { controller, _ ->
            assertFailsWith<IllegalStateException> { controller.open(FIRST) }
            assertNull(controller.readyToOpen)
        }
    }

    @Test
    fun `a second open while the first is being checked asks nothing`() =
        observing { controller, fake ->
            val gate = CompletableDeferred<Unit>()
            fake.healthGate = gate
            val first = launch(Dispatchers.Unconfined) { controller.open(FIRST) }
            assertIs<OpenAttempt.Checking>(controller.opening)

            controller.open(THIRD)
            controller.askToRemove(THIRD)

            assertEquals(1, fake.healthCalls, "a second check started while the first was running")
            assertEquals(RemovalFlowState.Closed, controller.removal, "a question opened over a running check")
            gate.complete(Unit)
            first.join()
            assertEquals(FIRST, controller.readyToOpen)
        }

    @Test
    fun `one question at a time, and one transaction however many presses`() =
        observing { controller, fake ->
            controller.askToRemove(FIRST)
            controller.askToRemove(THIRD)
            assertEquals(RemovalFlowState.Offered(rows()[0]), controller.removal)

            val gate = CompletableDeferred<Unit>()
            fake.removeGate = gate
            val first = launch(Dispatchers.Unconfined) { controller.remove() }
            assertIs<RemovalFlowState.Removing>(controller.removal)
            controller.remove()
            controller.close()
            controller.open(THIRD)

            assertEquals(1, fake.removeCalls, "a second press started a second transaction")
            assertIs<RemovalFlowState.Removing>(controller.removal, "the running removal could be closed")
            assertEquals(0, fake.healthCalls, "a draft was checked for opening while a removal ran")
            gate.complete(Unit)
            first.join()
            assertIs<RemovalFlowState.Removed>(controller.removal)
        }

    @Test
    fun `whatever the engine answers is what is shown`() {
        DraftRemovalRefusal.entries.forEach { refusal ->
            val fake = FakeUnfinishedImports(rows()).apply { outcome = { DraftRemovalOutcome.Refused(it, refusal) } }
            observing(fake) { controller, _ ->
                controller.askToRemove(SECOND)
                controller.remove()
                assertEquals(RemovalFlowState.Refused(rows()[1], refusal), controller.removal)
            }
        }
    }

    @Test
    fun `a defect in the removal is not turned into an answer`() {
        val fake = FakeUnfinishedImports(rows()).apply { removeThrows = IllegalStateException("postcondition") }
        observing(fake) { controller, _ ->
            controller.askToRemove(FIRST)
            assertFailsWith<IllegalStateException> { controller.remove() }
        }
    }

    @Test
    fun `cancelling puts the keyboard back on the row's own removal button`() =
        observing { controller, fake ->
            controller.askToRemove(THIRD)
            controller.close()

            assertEquals(RemovalFlowState.Closed, controller.removal)
            assertEquals(RowFocus(THIRD, RowFocusTarget.REMOVE), controller.focus)
            assertEquals(0, fake.removeCalls, "closing the question removed something")
        }

    @Test
    fun `after a removal the keyboard goes to the next row, or the one before the last`() {
        observing { controller, _ ->
            controller.askToRemove(FIRST)
            controller.remove()
            controller.close()
            // Drawn order is sound rows first: FIRST, THIRD, then SECOND.
            assertEquals(RowFocus(THIRD, RowFocusTarget.FIRST), controller.focus)
        }
        observing { controller, _ ->
            controller.askToRemove(SECOND)
            controller.remove()
            controller.close()
            assertEquals(RowFocus(THIRD, RowFocusTarget.FIRST), controller.focus)
        }
        observing(FakeUnfinishedImports(listOf(anUnfinishedImport(FIRST)))) { controller, _ ->
            controller.askToRemove(FIRST)
            controller.remove()
            controller.close()
            assertNull(controller.focus, "there is no row left to go to")
        }
    }

    @Test
    fun `the list moving underneath an open question does not decide it`() =
        observing { controller, fake ->
            controller.askToRemove(FIRST)
            fake.list.value = rows().drop(1)
            yield()

            // Still the question the user is reading; the engine will answer it.
            assertEquals(RemovalFlowState.Offered(rows()[0]), controller.removal)
            fake.outcome = { DraftRemovalOutcome.Refused(it, DraftRemovalRefusal.ALREADY_REMOVED) }
            controller.remove()
            assertEquals(RemovalFlowState.Refused(rows()[0], DraftRemovalRefusal.ALREADY_REMOVED), controller.removal)
        }

    @Test
    fun `a restore lets go of everything that was open`() =
        observing { controller, _ ->
            controller.askToRemove(FIRST)
            controller.abandonOpenWork()

            assertEquals(RemovalFlowState.Closed, controller.removal)
            assertEquals(OpenAttempt.Idle, controller.opening)
            assertNull(controller.focus)
        }
}
