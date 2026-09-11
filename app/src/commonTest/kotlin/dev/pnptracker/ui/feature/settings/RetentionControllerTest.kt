package dev.pnptracker.ui.feature.settings

import dev.pnptracker.domain.settings.AutomaticBackupSettings
import dev.pnptracker.domain.settings.SettingsNotSaved
import dev.pnptracker.domain.settings.SettingsProblem
import dev.pnptracker.domain.settings.SettingsStore
import dev.pnptracker.domain.settings.SettingsWriteFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A settings store with nothing behind it.
 *
 * [gate] is how a save is held open long enough for a second press to arrive,
 * which is the only way to test that the second one does nothing.
 */
private class FakeSettingsStore(
    private var stored: AutomaticBackupSettings = AutomaticBackupSettings(),
    private val refuseWith: SettingsWriteFailure? = null,
    private val gate: CompletableDeferred<Unit>? = null,
) : SettingsStore {
    val written = mutableListOf<Int>()
    var reads = 0
        private set

    override suspend fun read(): AutomaticBackupSettings {
        reads++
        return stored
    }

    override suspend fun write(automaticBackupCount: Int) {
        gate?.await()
        written += automaticBackupCount
        refuseWith?.let { throw SettingsNotSaved(it) }
        stored = AutomaticBackupSettings(automaticBackupCount)
    }
}

/**
 * Choosing how many automatic backups to keep.
 *
 * Almost everything here is about the difference between what is on screen and
 * what the application is actually keeping to. The two are allowed to differ
 * while somebody is typing, and they must stop differing the moment a save
 * either lands or fails — a screen that showed a number nothing had written
 * would be lying about how protected the user is.
 */
class RetentionControllerTest {
    @Test
    fun `with nothing saved the default is shown and nothing is written`() =
        runBlocking<Unit> {
            val store = FakeSettingsStore()
            val controller = RetentionController(store)

            controller.load()

            assertEquals(RetentionScreenState.Ready(7), controller.state)
            assertEquals("7", controller.draftText)
            assertEquals(emptyList(), store.written, "opening the screen wrote a setting")
        }

    @Test
    fun `what the file said is what the screen shows`() =
        runBlocking<Unit> {
            val controller = RetentionController(FakeSettingsStore(AutomaticBackupSettings(21)))

            controller.load()

            assertEquals(RetentionScreenState.Ready(21), controller.state)
            assertEquals("21", controller.draftText)
        }

    @Test
    fun `a file that could not be used is said so, beside the default`() =
        runBlocking<Unit> {
            SettingsProblem.entries.forEach { problem ->
                val controller = RetentionController(FakeSettingsStore(AutomaticBackupSettings(problem = problem)))

                controller.load()

                assertEquals(RetentionScreenState.Ready(7, problem), controller.state, "$problem")
            }
        }

    @Test
    fun `saving writes once and the screen then agrees with the file`() =
        runBlocking<Unit> {
            val store = FakeSettingsStore()
            val controller = RetentionController(store)
            controller.load()

            controller.type("12")
            assertTrue(controller.canSave)
            controller.save()

            assertEquals(listOf(12), store.written)
            assertEquals(RetentionScreenState.Ready(12, problem = null, justSaved = true), controller.state)
            assertEquals("12", controller.draftText)
        }

    @Test
    fun `saving clears a warning about a file that has now been replaced`() =
        runBlocking<Unit> {
            val store = FakeSettingsStore(AutomaticBackupSettings(problem = SettingsProblem.NOT_THE_EXPECTED_SHAPE))
            val controller = RetentionController(store)
            controller.load()
            assertEquals(SettingsProblem.NOT_THE_EXPECTED_SHAPE, (controller.state as RetentionScreenState.Ready).problem)

            controller.type("3")
            controller.save()

            assertNull((controller.state as RetentionScreenState.Ready).problem)
        }

    @Test
    fun `a number that may not be kept is shown, refused and never written`() =
        runBlocking<Unit> {
            val store = FakeSettingsStore()
            val controller = RetentionController(store)
            controller.load()

            listOf("0", "-1", "51", "1000", "yedi", "7.5", "").forEach { typed ->
                controller.type(typed)

                assertEquals(typed, controller.draftText, "the field was rewritten under the user")
                assertFalse(controller.canSave, typed)
                assertFalse(controller.draftIsUsable, typed)
                controller.save()
                assertEquals(emptyList(), store.written, typed)
            }
        }

    @Test
    fun `an empty box is not a refusal, and a bad number is`() =
        runBlocking<Unit> {
            val controller = RetentionController(FakeSettingsStore())
            controller.load()

            controller.type("")
            assertFalse(controller.draftIsRefused, "clearing the box is not asking for anything")

            controller.type("0")
            assertTrue(controller.draftIsRefused)

            controller.type("12")
            assertFalse(controller.draftIsRefused)
        }

    @Test
    fun `the steppers stop at the ends of the range`() =
        runBlocking<Unit> {
            val controller = RetentionController(FakeSettingsStore(AutomaticBackupSettings(1)))
            controller.load()

            controller.step(-1)
            assertEquals("1", controller.draftText)

            controller.type("50")
            controller.step(1)
            assertEquals("50", controller.draftText)

            controller.type("49")
            controller.step(1)
            assertEquals("50", controller.draftText)
        }

    @Test
    fun `a step taken from something that is not a number starts from what is kept`() =
        runBlocking<Unit> {
            val controller = RetentionController(FakeSettingsStore(AutomaticBackupSettings(20)))
            controller.load()

            controller.type("filanca")
            controller.step(1)

            assertEquals("21", controller.draftText)
        }

    @Test
    fun `saving the number already kept is not offered`() =
        runBlocking<Unit> {
            val controller = RetentionController(FakeSettingsStore(AutomaticBackupSettings(9)))
            controller.load()

            assertFalse(controller.canSave, "the saved number was offered for saving again")
            controller.type("9")
            assertFalse(controller.canSave)
            controller.type("10")
            assertTrue(controller.canSave)
        }

    @Test
    fun `a second press while a save is in flight writes nothing more`() =
        runBlocking<Unit> {
            val gate = CompletableDeferred<Unit>()
            val store = FakeSettingsStore(gate = gate)
            val controller = RetentionController(store)
            controller.load()
            controller.type("13")

            coroutineScope {
                val first = async { controller.save() }
                yield()
                assertEquals(RetentionScreenState.Saving, controller.state)

                repeat(3) { controller.save() }
                // Typing behind a save in flight changes nothing either.
                controller.type("44")
                controller.step(1)

                gate.complete(Unit)
                first.await()
            }

            assertEquals(listOf(13), store.written, "a second press started a second write")
            assertEquals(RetentionScreenState.Ready(13, problem = null, justSaved = true), controller.state)
        }

    @Test
    fun `a save that fails leaves the running number and the file alone`() =
        runBlocking<Unit> {
            SettingsWriteFailure.entries.forEach { failure ->
                val store = FakeSettingsStore(AutomaticBackupSettings(9), refuseWith = failure)
                val controller = RetentionController(store)
                controller.load()
                controller.type("30")

                controller.save()

                assertEquals(RetentionScreenState.Failed(9, failure), controller.state, "$failure")
                // The box goes back to what is really being kept, so nothing on
                // screen claims a protection the application is not giving.
                assertEquals("9", controller.draftText, "$failure")
                assertEquals(9, controller.state.countInUse, "$failure")
            }
        }

    @Test
    fun `a save can be tried again after one that failed`() =
        runBlocking<Unit> {
            val store = FakeSettingsStore(AutomaticBackupSettings(9), refuseWith = SettingsWriteFailure.COULD_NOT_WRITE)
            val controller = RetentionController(store)
            controller.load()
            controller.type("30")
            controller.save()
            assertTrue(controller.state is RetentionScreenState.Failed)

            controller.type("31")

            assertTrue(controller.canSave, "a failure left the screen unable to try again")
        }

    @Test
    fun `a success message does not outlive the value it was about`() =
        runBlocking<Unit> {
            val controller = RetentionController(FakeSettingsStore())
            controller.load()
            controller.type("12")
            controller.save()
            assertTrue((controller.state as RetentionScreenState.Ready).justSaved)

            controller.type("13")

            assertFalse((controller.state as RetentionScreenState.Ready).justSaved)
        }

    @Test
    fun `saving records a number and deletes nothing`() =
        runBlocking<Unit> {
            // PLAN 14.4.12: lowering the number is not a destructive act. This
            // controller has no way to remove a file and is given none — the
            // next automatic backup is what applies the change.
            val store = FakeSettingsStore(AutomaticBackupSettings(40))
            val controller = RetentionController(store)
            controller.load()

            controller.type("2")
            controller.save()

            assertEquals(listOf(2), store.written)
            assertEquals(2, controller.state.countInUse)
        }
}
