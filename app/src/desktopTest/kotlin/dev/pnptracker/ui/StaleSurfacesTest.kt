package dev.pnptracker.ui

import androidx.compose.runtime.Composable
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.ui.feature.settings.FakeSafetyWriter
import dev.pnptracker.ui.feature.settings.FakeSourceGateway
import dev.pnptracker.ui.feature.settings.RestoreController
import dev.pnptracker.ui.feature.settings.aRealBackupFile
import dev.pnptracker.ui.feature.settings.aRestoreController
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** Somewhere with something open, that counts being asked to let go of it. */
private class Surface : StaleSurfaces {
    var abandoned = 0
        private set

    override fun abandonOpenWork() {
        abandoned++
    }
}

/**
 * What happens to an open panel when the rows underneath it are replaced.
 *
 * A restore refreshes every screen on its own, because they all read through a
 * database flow. What it cannot refresh is a form somebody left open over a task
 * that no longer exists, and the next press on it would write to nothing. So the
 * application watches for a restore and asks each holder of one to let go — and
 * this is the watching, composed for real, with a real controller doing a real
 * restore behind it.
 */
class StaleSurfacesTest {
    private fun harness(content: @Composable () -> Unit) = ComposeSceneHarness(width = 400, height = 300, content = content)

    /**
     * Renders until [done] holds, so nothing here waits on a number of frames.
     *
     * A restore raises a tick, the composition notices it, and the effect that
     * noticed asks the surfaces to let go. How many frames that takes is the
     * machine's business: one is usually enough and was what these tests used,
     * and on a slow runner it was not. Waiting on the thing itself has no such
     * assumption in it — and it also keeps the two ticks of a second restore
     * from being collapsed into one by a test that raced ahead.
     */
    private suspend fun ComposeSceneHarness.settleUntil(
        what: String,
        done: () -> Boolean,
    ) {
        repeat(FRAME_LIMIT) {
            renderAndSettle()
            if (done()) return
        }
        fail("never happened: $what")
    }

    @Test
    fun `nothing is closed before a restore has happened`() =
        runBlocking<Unit> {
            val surface = Surface()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()))

            harness { CloseStaleSurfacesAfterRestore(controller.restoredTick, listOf(surface)) }.use { harness ->
                // Several frames rather than one: the claim is that nothing
                // happens, and one frame is a weak place to make it from.
                repeat(SETTLED_FRAMES) { harness.renderAndSettle() }

                // A freshly started application has not restored anything, and must
                // not close what the user has open just because it started.
                assertEquals(0, surface.abandoned)
            }
        }

    @Test
    fun `every open surface is asked to let go when a restore lands`() =
        runBlocking<Unit> {
            val surfaces = List(3) { Surface() }
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()))

            harness { CloseStaleSurfacesAfterRestore(controller.restoredTick, surfaces) }.use { harness ->
                controller.chooseBackup()
                controller.confirmRestore()
                harness.settleUntil("every surface was asked to let go") { surfaces.all { it.abandoned == 1 } }

                surfaces.forEach { assertEquals(1, it.abandoned, "a surface was left open over data that had gone") }
            }
        }

    @Test
    fun `a restore that did not happen closes nothing`() =
        runBlocking<Unit> {
            val surface = Surface()
            val controller =
                aRestoreController(
                    FakeSourceGateway(aRealBackupFile()),
                    safety = FakeSafetyWriter(refuse = BackupFailure.NOT_WRITABLE),
                )

            harness { CloseStaleSurfacesAfterRestore(controller.restoredTick, listOf(surface)) }.use { harness ->
                controller.chooseBackup()
                controller.confirmRestore()
                repeat(SETTLED_FRAMES) { harness.renderAndSettle() }

                // The data is exactly as it was, so what is open over it still
                // means what it meant.
                assertEquals(0, surface.abandoned)
            }
        }

    @Test
    fun `a second restore asks again`() =
        runBlocking<Unit> {
            val surface = Surface()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()))

            harness { CloseStaleSurfacesAfterRestore(controller.restoredTick, listOf(surface)) }.use { harness ->
                controller.chooseBackup()
                controller.confirmRestore()
                // Waited for on purpose: if the second restore raised its tick
                // before the composition had seen the first, the effect would
                // run once for both and the surface would be asked to let go a
                // single time — for a reason that has nothing to do with the
                // rule being measured.
                harness.settleUntil("the first restore closed the surface") { surface.abandoned == 1 }
                controller.startOver()
                controller.chooseBackup()
                controller.confirmRestore()
                harness.settleUntil("the second restore closed it again") { surface.abandoned == 2 }

                assertEquals(2, surface.abandoned)
            }
        }

    @Test
    fun `a redraw that is not a restore does not close anything again`() =
        runBlocking<Unit> {
            val surface = Surface()
            val controller: RestoreController = aRestoreController(FakeSourceGateway(aRealBackupFile()))

            harness { CloseStaleSurfacesAfterRestore(controller.restoredTick, listOf(surface)) }.use { harness ->
                controller.chooseBackup()
                controller.confirmRestore()
                harness.settleUntil("the restore closed the surface") { surface.abandoned == 1 }
                // And then keeps drawing: a redraw is not a restore, so the
                // count must stay where it is however many frames follow.
                repeat(SETTLED_FRAMES) { harness.renderAndSettle() }

                assertEquals(1, surface.abandoned, "the panels were closed again on every frame")
            }
        }

    private companion object {
        /** Long enough that only a real failure runs out of frames. */
        const val FRAME_LIMIT = 200

        /** Enough frames to say "and then nothing else happened" with a straight face. */
        const val SETTLED_FRAMES = 8
    }
}
