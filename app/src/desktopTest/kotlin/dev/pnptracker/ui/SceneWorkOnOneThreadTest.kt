package dev.pnptracker.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The discipline the application really runs under: one thread per screen.
 *
 * In the application the composition, the effects a composable launches, the
 * flows a screen collects and every controller call a control makes all happen
 * on the one UI thread, so no two of them are ever part way through at the same
 * time. Controllers are written for that: they read their state, copy it and
 * write the copy back, which is safe when nothing else can write in between.
 *
 * A test harness that ran effects on whatever thread happened to emit broke it
 * and cost two red release runs, so this stands guard over the harness itself:
 * whatever a screen launches must come back to the thread the test is driving
 * from, even when it was woken by another thread entirely.
 */
class SceneWorkOnOneThreadTest {
    @Test
    fun `an effect a screen launches runs on the thread the test drives`() {
        var ran: Thread? = null
        ComposeSceneHarness(width = 200, height = 100) {
            LaunchedEffect(Unit) { ran = Thread.currentThread() }
            Text("something to compose")
        }.use { screen ->
            screen.render()

            assertEquals(Thread.currentThread(), assertNotNull(ran, "the effect never ran"))
        }
    }

    @Test
    fun `a value pushed from another thread is collected on the test's own thread`() {
        val pushes = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        var collectedOn: Thread? = null
        var seen by mutableStateOf(0)
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            ComposeSceneHarness(width = 200, height = 100) {
                LaunchedEffect(Unit) {
                    pushes.collect { value ->
                        collectedOn = Thread.currentThread()
                        seen = value
                    }
                }
                Text("value $seen")
            }.use { screen ->
                // Pushed from a thread of its own, the way a database hands a
                // reading to a screen that is waiting for one.
                elsewhere.submit { runBlocking { pushes.emit(7) } }.get()
                screen.render()

                assertEquals(7, seen, "the value never reached the screen")
                assertEquals(
                    Thread.currentThread(),
                    assertNotNull(collectedOn, "nothing was collected"),
                    "the screen collected on the thread that pushed, beside whatever the test is doing",
                )
            }
        } finally {
            elsewhere.shutdownNow()
        }
    }
}
