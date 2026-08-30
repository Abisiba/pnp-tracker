package dev.pnptracker.ui.feature.colors

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.WheelNudge
import dev.pnptracker.domain.colors.WheelPoint
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.flow.collect

/**
 * The global colour catalogue, and everything the user can do to it.
 *
 * The list is read as a stream, so a colour that is created, changed or removed
 * shows up without anything here having to remember to reload. The stream only
 * ever replaces the list: an open form is held apart from it, so a colour
 * arriving cannot wipe out something half typed.
 *
 * Nothing on this path goes near a task, with one exception that is not an
 * exception: removing a colour takes the task relations that pointed at it,
 * because a relation to a colour that is gone is not a relation. No task, stage
 * or event is written.
 */
class ColorCatalogueController(
    private val catalogue: ColorCatalogue,
) {
    var state: ColorCatalogueScreenState by mutableStateOf(ColorCatalogueScreenState())
        private set

    /** True while something is on its way to the database. */
    val isSaving: Boolean get() = state.isSaving

    /** Bumped whenever the keyboard has to be handed back to a particular place. */
    var focusRecall: Int by mutableStateOf(0)
        private set

    /** The colour a closing surface should hand the keyboard back to, if it is a row. */
    var focusTarget: EntityId? by mutableStateOf(null)
        private set

    /**
     * True when the closing surface belongs to the restore action rather than to
     * a row.
     *
     * The two are set together and never both, because they are one decision:
     * where the keyboard goes when what is open closes. Left to drift apart, a
     * row named by a surface the user gave up on would still be holding the
     * keyboard when the next surface closed, and the restore action — which no
     * row can stand in for — would never get it back at all.
     */
    var focusesTheRestore: Boolean by mutableStateOf(false)
        private set

    /** Says where the keyboard goes next, and unsays wherever it was going before. */
    private fun handTheKeyboardBackTo(
        colorId: EntityId?,
        restore: Boolean = false,
    ) {
        focusTarget = colorId
        focusesTheRestore = restore
    }

    /** Collects the catalogue until cancelled. */
    suspend fun observeColors() {
        catalogue.observeColors().collect { colors ->
            state = state.copy(catalogue = ColorCatalogueState.Content(colors))
        }
    }

    /** Every colour the section is showing, or nothing while it is still loading. */
    private fun colors(): List<ColorSummary> = (state.catalogue as? ColorCatalogueState.Content)?.colors.orEmpty()

    /** Opens a surface, replacing whatever the last one left behind. */
    private fun open(work: ColorWork) {
        if (isSaving) return
        state = state.copy(work = work, notice = null)
        focusRecall += 1
    }

    // ---------------------------------------------------------------- creating

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
        handTheKeyboardBackTo(colorId = null)
        open(ColorWork.Creating(ColorComposer.startingFrom(start)))
    }

    // ----------------------------------------------------------------- editing

    /**
     * Opens the same picker on a colour that already exists.
     *
     * PLAN 5.7 makes no distinction between the twelve and the rest, so nothing
     * here asks whether the colour is a base one.
     */
    fun startEditing(colorId: EntityId) {
        val color = colors().firstOrNull { it.id == colorId } ?: return
        handTheKeyboardBackTo(colorId)
        open(ColorWork.Editing(colorId = colorId, composer = ColorComposer.editingOf(color)))
    }

    // ------------------------------------------------------------- the picker

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
        val work = state.work ?: return
        if (work.isSaving) return
        state =
            state.copy(
                work =
                    when (work) {
                        is ColorWork.Creating -> work.copy(composer = change(work.composer), failure = null)
                        is ColorWork.Editing -> work.copy(composer = change(work.composer), failure = null)
                        else -> return
                    },
            )
    }

    /**
     * Closes whatever is open, changing nothing anywhere.
     *
     * A refusal belongs to the form that earned it, so it goes when the form
     * goes. Left behind, it would sit above a form that is not there any more and
     * describe an attempt the user had already abandoned.
     */
    fun cancel() {
        if (isSaving) return
        state = state.copy(work = null)
        focusRecall += 1
    }

    /** Puts away a message the user has read. */
    fun acknowledgeNotice() {
        state = state.copy(notice = null)
    }

    /**
     * Saves whatever is open, if it is a form with something to save.
     *
     * Sharing a value with another colour does not stand in the way: it was a
     * remark, and the user has seen it.
     *
     * A second call while the first is still on its way does nothing, so one
     * insistent click cannot turn into two colours or two deletions. The guard
     * is set before anything suspends, which is the only place it works: set
     * after the first suspension point, two clicks in the same frame would both
     * get past it. Anything that does not save leaves the form open with the
     * name, the place on the wheel and the brightness exactly as they were.
     */
    suspend fun save() {
        when (val work = state.work) {
            is ColorWork.Creating -> saveCreated(work)
            is ColorWork.Editing -> saveEdited(work)
            is ColorWork.Deleting -> deleteConfirmed(work)
            is ColorWork.Restoring -> restoreConfirmed(work)
            null -> Unit
        }
    }

    private suspend fun saveCreated(work: ColorWork.Creating) {
        if (!work.composer.canSave || work.isSaving) return
        val armed = work.copy(isSaving = true, failure = null)
        state = state.copy(work = armed)
        try {
            catalogue.createColor(canonicalName = armed.composer.cleanName, hex = armed.composer.hex)
            state = state.copy(work = null)
        } catch (failure: ColorSetupException) {
            state = state.copy(work = armed.copy(isSaving = false, failure = failure.failure))
        } finally {
            releaseIfStillArmed(armed)
        }
    }

    private suspend fun saveEdited(work: ColorWork.Editing) {
        if (!work.composer.canSave || work.isSaving) return
        // Nothing to record, so nothing is written: PLAN has no reason to touch a
        // row that would come back out saying what it already says.
        if (work.composer.isNoOp) {
            state = state.copy(work = null)
            focusRecall += 1
            return
        }
        val armed = work.copy(isSaving = true, failure = null)
        state = state.copy(work = armed)
        try {
            catalogue.editColor(
                id = armed.colorId,
                expectedName = armed.composer.startedName,
                expectedHex = armed.composer.startedHex.orEmpty(),
                canonicalName = armed.composer.cleanName,
                hex = armed.composer.hex,
            )
            state = state.copy(work = null)
        } catch (failure: ColorSetupException) {
            state = state.copy(work = armed.copy(isSaving = false, failure = failure.failure))
        } finally {
            releaseIfStillArmed(armed)
        }
    }

    // ---------------------------------------------------------------- deleting

    /**
     * Asks about losing a colour, with the numbers PLAN 5.9 requires.
     *
     * Even a colour nothing uses is asked about: the removal cannot be undone,
     * and PLAN 17 does not make an exception for the cheap case.
     */
    suspend fun startDeleting(colorId: EntityId) {
        // Nothing is asked of the database while a surface is up: the rows offer
        // no buttons then, and an answer arriving late would open a question the
        // user never put.
        if (isSaving || state.work != null) return
        val color = colors().firstOrNull { it.id == colorId } ?: return
        handTheKeyboardBackTo(colorId)
        val usage = catalogue.usageOf(colorId)
        // Read while nothing was open, so an answer that arrives after the user
        // moved on is dropped rather than opening a surface they did not ask for.
        if (state.work != null) return
        open(ColorWork.Deleting(color = color, usage = usage))
    }

    private suspend fun deleteConfirmed(work: ColorWork.Deleting) {
        if (work.isSaving) return
        val armed = work.copy(isSaving = true, failure = null)
        state = state.copy(work = armed)
        try {
            val removal = catalogue.deleteColor(armed.color.id)
            handTheKeyboardBackTo(colorAfter(armed.color.id))
            state = state.copy(work = null, notice = ColorNotice.Removed(armed.color.canonicalName, removal))
        } catch (failure: ColorSetupException) {
            state = state.copy(work = armed.copy(isSaving = false, failure = failure.failure))
        } finally {
            releaseIfStillArmed(armed)
        }
    }

    /** The row the keyboard goes to once one is gone: the next, else the one before. */
    private fun colorAfter(removed: EntityId): EntityId? {
        val listed = colors()
        val place = listed.indexOfFirst { it.id == removed }
        if (place < 0) return null
        return listed.getOrNull(place + 1)?.id ?: listed.getOrNull(place - 1)?.id
    }

    // --------------------------------------------------------------- restoring

    /**
     * Shows which of the twelve are missing before putting any of them back.
     *
     * Missing means the fixed identity is not in the catalogue and nothing else.
     * A base colour the user has renamed and recoloured is still there, so it is
     * not offered, not counted and not touched.
     */
    suspend fun startRestoring() {
        if (isSaving || state.work != null) return
        handTheKeyboardBackTo(colorId = null, restore = true)
        val plan = catalogue.previewBaseColorRestore()
        if (state.work != null) return
        if (plan.isNothingMissing) {
            state = state.copy(notice = ColorNotice.NothingMissing)
            focusRecall += 1
            return
        }
        open(ColorWork.Restoring(plan))
    }

    private suspend fun restoreConfirmed(work: ColorWork.Restoring) {
        if (work.isSaving || !work.plan.canRestore) return
        val armed = work.copy(isSaving = true, failure = null)
        state = state.copy(work = armed)
        try {
            state =
                when (val outcome = catalogue.restoreMissingBaseColors()) {
                    BaseColorRestore.NothingMissing ->
                        state.copy(work = null, notice = ColorNotice.NothingMissing)

                    is BaseColorRestore.Restored ->
                        state.copy(work = null, notice = ColorNotice.Restored(outcome.canonicalNames))

                    is BaseColorRestore.Blocked ->
                        // Still open, showing what stopped it: the user has to
                        // change something before this can go anywhere.
                        state.copy(
                            work =
                                armed.copy(
                                    isSaving = false,
                                    plan = armed.plan.copy(blocked = outcome.blocked),
                                ),
                        )
                }
        } catch (failure: ColorSetupException) {
            state = state.copy(work = armed.copy(isSaving = false, failure = failure.failure))
        } finally {
            releaseIfStillArmed(armed)
        }
    }

    /**
     * Puts the surface back within reach whatever happened.
     *
     * An error nobody planned for still leaves the form usable rather than frozen
     * behind a flag that never came down. The surface is only touched if it is
     * still the one this save armed.
     */
    private fun releaseIfStillArmed(armed: ColorWork) {
        if (state.work === armed) state = state.copy(work = withoutSavingFlag(armed))
        focusRecall += 1
    }

    private fun withoutSavingFlag(work: ColorWork): ColorWork =
        when (work) {
            is ColorWork.Creating -> work.copy(isSaving = false)
            is ColorWork.Editing -> work.copy(isSaving = false)
            is ColorWork.Deleting -> work.copy(isSaving = false)
            is ColorWork.Restoring -> work.copy(isSaving = false)
        }
}
