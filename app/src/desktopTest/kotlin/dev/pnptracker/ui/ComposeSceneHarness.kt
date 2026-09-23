package dev.pnptracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield

/**
 * A real Compose composition, off screen, that real key strokes can be sent to.
 *
 * The point is that nothing here is a stand-in. A test that asks a controller
 * whether it *would* open a panel proves the controller and nothing else; the
 * defect this exists for lived entirely in the modifier chain, where the
 * controller was never reached at all. So the composable is really composed,
 * really laid out, and the key really travels the same path a key from the
 * window travels — down the focus chain, through every preview handler on the
 * way — and what is asserted afterwards is the real semantics tree.
 *
 * [ImageComposeScene] comes with Compose Desktop itself, so this costs no
 * dependency; it is the same scene the screenshot tooling renders through.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ComposeSceneHarness(
    width: Int = 900,
    height: Int = 600,
    density: Density = Density(1f),
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val scene =
        ImageComposeScene(
            width = width,
            height = height,
            density = density,
            // Unconfined, so an effect a composable launches — the screen
            // collecting its own table, a string resource being read — runs on
            // the thread that rendered the frame rather than waiting for a
            // dispatcher nothing in a test is turning.
            coroutineContext = Dispatchers.Unconfined,
            content = content,
        )

    private var clock = 0L

    init {
        // Twice: the first pass composes and lays out, and anything that only
        // settles once a size is known — the boxes a wrapped name occupies, for
        // one — is not there until the second.
        render()
        render()
    }

    /** Advances a frame, which is what makes a change actually take effect. */
    fun render() {
        clock += FRAME_NANOSECONDS
        scene.render(clock).close()
    }

    /**
     * Advances a frame and lets the effects that frame launched actually run.
     *
     * [Dispatchers.Unconfined] runs a launch on the launching thread, but only
     * when that thread is not already inside an event loop. Under `runBlocking`
     * it is: the launch is queued on the loop instead, and it gets its turn the
     * next time the calling coroutine yields. A test that renders and asserts in
     * the same breath is therefore racing its own `LaunchedEffect`, and wins or
     * loses depending on how the machine happened to schedule it.
     *
     * So anything that asserts on what an effect did renders through here.
     */
    suspend fun renderAndSettle() {
        render()
        yield()
    }

    /**
     * Sends one whole key stroke, press and release, and settles the frame.
     *
     * Both halves, because that is what a keyboard sends and because one of the
     * things worth proving is that the release does *not* count as a second
     * press.
     */
    fun press(key: Key) {
        down(key)
        up(key)
    }

    /** The press half on its own, for the tests that separate them. */
    fun down(key: Key): Boolean = send(key, KeyEventType.KeyDown)

    /** The release half on its own. */
    fun up(key: Key): Boolean = send(key, KeyEventType.KeyUp)

    /** Moves the keyboard on by one stop, the way the user's Tab key does. */
    fun tab() = press(Key.Tab)

    /** Moves the keyboard back one stop, the way Shift+Tab does. */
    fun shiftTab() = press(Key.Tab, shift = true)

    /**
     * One whole stroke with modifiers held, press and release.
     *
     * The modifiers travel on the event itself, which is how a real window sends
     * them: a handler asking `isCtrlPressed` sees exactly what it would see from
     * the keyboard.
     */
    fun press(
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ) {
        send(key, KeyEventType.KeyDown, ctrl, shift, alt)
        send(key, KeyEventType.KeyUp, ctrl, shift, alt)
    }

    /**
     * Drags the pointer from one place to another, the way a mouse selects text.
     *
     * Press, a move part way, and release: the move matters, because a text
     * field decides what is selected from where the pointer travelled and not
     * from where it was let go.
     */
    fun dragFrom(
        from: Offset,
        to: Offset,
    ) {
        scene.sendPointerEvent(PointerEventType.Press, from)
        render()
        scene.sendPointerEvent(PointerEventType.Move, Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f))
        render()
        scene.sendPointerEvent(PointerEventType.Move, to)
        render()
        scene.sendPointerEvent(PointerEventType.Release, to)
        render()
    }

    /**
     * Presses and releases the mouse at one place, the way a person clicks.
     *
     * Not the same thing as [click], and the difference is the point. [click]
     * invokes a node's action directly, so it can never see what the press
     * itself does to the screen before the release arrives — and a press moves
     * focus, which can change what is drawn under the pointer. A control that
     * disappears between the two halves of a click is only caught this way.
     */
    fun mouseClick(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        render()
        scene.sendPointerEvent(PointerEventType.Press, at)
        render()
        scene.sendPointerEvent(PointerEventType.Release, at)
        render()
    }

    /**
     * Two clicks close together in one place, the way a person opens something.
     *
     * Not two calls to [mouseClick]: Compose ignores a second tap that arrives
     * sooner than its own minimum, and treats one that arrives later as two
     * separate taps. So the gap between them is made on purpose — several frames
     * and a real pause — and both halves of both clicks are sent.
     */
    fun mouseDoubleClick(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        render()
        scene.sendPointerEvent(PointerEventType.Press, at)
        render()
        scene.sendPointerEvent(PointerEventType.Release, at)
        render()
        repeat(4) { render() }
        Thread.sleep(DOUBLE_CLICK_GAP_MILLISECONDS)
        scene.sendPointerEvent(PointerEventType.Press, at)
        render()
        scene.sendPointerEvent(PointerEventType.Release, at)
        render()
        render()
    }

    /** Where a node a reader would name is drawn, or null when it is not there. */
    fun boundsOf(description: String): Rect? = spokenNodes().firstOrNull { description in it.contentDescriptions() }?.boundsInRoot

    /**
     * Puts one event into the scene the way the window does.
     *
     * Built through Compose's own public constructor rather than assembled from
     * parts: what matters is that it goes in at [ImageComposeScene.sendKeyEvent]
     * — the same door a stroke from a real window comes through — and travels
     * the real focus chain from there, which is where the defect these exist for
     * lived.
     */
    private fun send(
        key: Key,
        type: KeyEventType,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): Boolean {
        val handled =
            scene.sendKeyEvent(
                KeyEvent(
                    key = key,
                    type = type,
                    isCtrlPressed = ctrl,
                    isShiftPressed = shift,
                    isAltPressed = alt,
                ),
            )
        render()
        return handled
    }

    /** Every semantics node the scene really has, merged as a reader sees them. */
    fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.flattened() }

    private fun SemanticsNode.flattened(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flattened() }

    /** The nodes a screen reader would announce, in tree order. */
    fun spokenNodes(): List<SemanticsNode> = nodes().filter { it.contentDescriptions().isNotEmpty() }

    /**
     * Every word actually written on the screen, in tree order.
     *
     * A plain `Text` carries its words as semantics text rather than as a
     * content description, and a reader announces those too. A test that only
     * looked at descriptions would think an ordinary sentence was not there.
     */
    fun writtenText(): List<String> = nodes().flatMap { node -> node.reads(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** Every node that can take the keyboard, which is what a Tab stop is. */
    fun focusableNodes(): List<SemanticsNode> = nodes().filter { it.config.contains(SemanticsProperties.Focused) }

    /** The node holding the keyboard right now, if any. */
    fun focusedNode(): SemanticsNode? = nodes().firstOrNull { it.reads(SemanticsProperties.Focused) == true }

    /**
     * Whether one more Tab leaves the named control altogether.
     *
     * The honest test of "one word, one stop". A control built out of a
     * `focusable` and a `clickable` side by side looks like one word, carries
     * one description — only one of the two halves holds it — and answers Tab
     * twice, with the keys landing on whichever half the user happened to
     * reach. Counting semantics nodes cannot see that, because the second half
     * has nothing to say and merges away; pressing Tab can, because the
     * keyboard really stops there.
     *
     * The keyboard must already be on [description] when this is called.
     */
    fun tabLeaves(description: String): Boolean {
        val word = spokenNodes().firstOrNull { description in it.contentDescriptions() } ?: return false
        val bounds = word.boundsInRoot
        tab()
        val landed = focusedNode()?.boundsInRoot ?: return true
        val inside =
            bounds.left <= landed.left &&
                bounds.top <= landed.top &&
                bounds.right >= landed.right &&
                bounds.bottom >= landed.bottom
        return !inside
    }

    /** Presses Tab until the wanted node has the keyboard, and says whether it got there. */
    fun tabTo(
        description: String,
        limit: Int = 24,
    ): Boolean {
        repeat(limit) {
            if (focusedNode()?.contentDescriptions()?.contains(description) == true) return true
            tab()
        }
        return focusedNode()?.contentDescriptions()?.contains(description) == true
    }

    /** Clicks a node the way a pointer would, through its own click action. */
    fun click(description: String): Boolean {
        val node = spokenNodes().firstOrNull { description in it.contentDescriptions() } ?: return false
        val action = node.reads(SemanticsActions.OnClick) ?: return false
        val done = action.action?.invoke() == true
        render()
        return done
    }

    override fun close() = scene.close()

    private companion object {
        const val FRAME_NANOSECONDS = 16_000_000L

        /** Longer than Compose's own minimum between two taps, shorter than its timeout. */
        const val DOUBLE_CLICK_GAP_MILLISECONDS = 60L
    }
}

/** One property of a node, or null when the node does not carry it. */
fun <T> SemanticsNode.reads(key: SemanticsPropertyKey<T>): T? = if (config.contains(key)) config[key] else null

/** What a reader would say about this node, if anything. */
fun SemanticsNode.contentDescriptions(): List<String> = reads(SemanticsProperties.ContentDescription).orEmpty()

/** What a reader would say about this node's state, if anything. */
fun SemanticsNode.stateDescriptionOrNull(): String? = reads(SemanticsProperties.StateDescription)
