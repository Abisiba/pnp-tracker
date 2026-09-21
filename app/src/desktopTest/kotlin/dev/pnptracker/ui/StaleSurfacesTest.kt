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

    @Test
    fun `nothing is closed before a restore has happened`() =
        runBlocking<Unit> {
            val surface = Surface()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()))

            harness { CloseStaleSurfacesAfterRestore(controller.restoredTick, listOf(surface)) }.use { harness ->
                harness.renderAndSettle()

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
                harness.renderAndSettle()

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
                harness.renderAndSettle()

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
                harness.renderAndSettle()
                controller.startOver()
                controller.chooseBackup()
                controller.confirmRestore()
                harness.renderAndSettle()

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
                repeat(4) { harness.renderAndSettle() }

                assertEquals(1, surface.abandoned, "the panels were closed again on every frame")
            }
        }
}
