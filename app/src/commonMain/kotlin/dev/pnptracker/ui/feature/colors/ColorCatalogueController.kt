package dev.pnptracker.ui.feature.colors

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.WheelNudge
import dev.pnptracker.domain.colors.WheelPoint
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.collect

/**
 * The global colour catalogue, and the one form that adds to it.
 *
 * The list is read as a stream, so a colour that is created shows up without
 * anything here having to remember to reload. Nothing else is read: the picker
 * works from the catalogue already in hand, so turning the wheel costs no query
 * however long the user turns it.
 *
 * Nothing on this path goes near a task. A colour exists in its own right; what a
 * task is produced in is decided elsewhere, and this section never writes a
 * colour onto one.
 */
class ColorCatalogueController(
    private val catalogue: ColorCatalogue,
) {
    var state: ColorCatalogueScreenState by mutableStateOf(ColorCatalogueScreenState())
        private set

    /** True while a colour is on its way to the database. */
    var isSaving: Boolean by mutableStateOf(false)
        private set

    /** Bumped whenever the keyboard has to be handed back to the open form. */
    var focusRecall: Int by mutableStateOf(0)
        private set

    /** Collects the catalogue until cancelled. */
    suspend fun observeColors() {
        catalogue.observeColors().collect { colors ->
            state = state.copy(catalogue = ColorCatalogueState.Content(colors))
        }
    }

    /** Every colour the section is showing, or nothing while it is still loading. */
    private fun colors(): List<ColorSummary> = (state.catalogue as? ColorCatalogueState.Content)?.colors.orEmpty()

    /**
     * Opens the picker on the first base colour the catalogue still has.
     *
     * Somewhere rather than nowhere: a wheel opened on no colour would have to
     * draw a preview of nothing, and the first square is the one the user's eye
     * is already on. It is read from the catalogue as it stands, so a base
     * colour the user has changed opens at what they made it.
     */
    fun startComposer() {
        val start = baseColorsIn(colors()).firstOrNull()?.hex
        state = state.copy(composer = ColorComposer.startingFrom(start), failure = null)
    }

    fun editName(name: String) = onComposer { it.copy(name = name) }

    /** Takes the whole colour from one of the squares. */
    fun chooseBaseColor(colorId: EntityId) {
        val hex = colors().firstOrNull { it.id == colorId }?.hex ?: return
        onComposer { it.setTo(hex) }
    }

    /** Moves the colour to where the pointer is; the brightness does not move. */
    fun moveOnWheel(
        point: WheelPoint,
        radius: Float,
    ) = onComposer { it.movedTo(point, radius) }

    /** The same, one arrow key press at a time. */
    fun nudgeWheel(nudge: WheelNudge) = onComposer { it.nudgedBy(nudge) }

    /** Only the brightness moves; the place on the wheel stays. */
    fun setBrightness(brightness: Float) = onComposer { it.brightenedTo(brightness) }

    private fun onComposer(change: (ColorComposer) -> ColorComposer) {
        val composer = state.composer ?: return
        if (isSaving) return
        state = state.copy(composer = change(composer), failure = null)
    }

    /**
     * Changes nothing anywhere; the colour was never written.
     *
     * A refusal belongs to the form that earned it, so it goes when the form
     * goes. Left behind, it would sit above a form that is not there any more and
     * describe an attempt the user had already abandoned.
     */
    fun cancelComposer() {
        if (isSaving) return
        state = state.copy(composer = null, failure = null)
        focusRecall += 1
    }

    /**
     * Saves the colour being made, if it has the name PLAN 5.7 requires.
     *
     * Sharing a value with another colour does not stand in the way: it was a
     * remark, and the user has seen it.
     *
     * A second call while the first is still on its way does nothing, so one
     * insistent click cannot turn into two colours. The guard is set before
     * anything suspends, which is the only place it works: set after the first
     * suspension point, two clicks in the same frame would both get past it.
     * A colour that does not save leaves the form open with the name, the place
     * on the wheel and the brightness exactly as they were.
     */
    suspend fun save() {
        val composer = state.composer ?: return
        if (!composer.canSave || isSaving) return
        isSaving = true
        try {
            catalogue.createColor(canonicalName = composer.cleanName, hex = composer.hex)
            state = state.copy(composer = null, failure = null)
        } catch (failure: ColorSetupException) {
            state = state.copy(failure = failure.failure)
        } finally {
            isSaving = false
            focusRecall += 1
        }
    }
}
