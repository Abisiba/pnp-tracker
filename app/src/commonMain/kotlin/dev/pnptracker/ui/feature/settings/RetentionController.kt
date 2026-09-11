package dev.pnptracker.ui.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.domain.backup.retention.DEFAULT_AUTOMATIC_BACKUPS
import dev.pnptracker.domain.backup.retention.MAXIMUM_AUTOMATIC_BACKUPS
import dev.pnptracker.domain.backup.retention.MINIMUM_AUTOMATIC_BACKUPS
import dev.pnptracker.domain.settings.SettingsNotSaved
import dev.pnptracker.domain.settings.SettingsStore
import dev.pnptracker.domain.settings.isKeepableCount

/**
 * Drives the one setting this application has: how many automatic backups to keep.
 *
 * Three things are worth knowing about it.
 *
 * **Saving is the only thing that writes.** Reading does not create the file and
 * neither does typing; PLAN 14.4.12 has the settings file appear when somebody
 * presses save and at no other moment, so a machine that has never chosen a
 * number goes on having no settings file however much the screen is looked at.
 *
 * **A number that is not allowed never leaves the screen.** [save] refuses it
 * and the store is never asked, so the file cannot come to hold one and the
 * running value cannot become one. Zero is refused like any other number out of
 * range: PLAN 14.4.12 makes it invalid rather than "off", because keeping none
 * of something is not a retention policy.
 *
 * **A failed save changes nothing.** The number in use stays what it was, which
 * is what the application is really keeping to, and the screen says so instead
 * of showing a value that never reached the disk.
 */
class RetentionController(
    private val settings: SettingsStore,
) {
    var state: RetentionScreenState by mutableStateOf(RetentionScreenState.Loading)
        private set

    /**
     * Exactly what is in the box, as text.
     *
     * Text rather than a number, because what somebody has typed is not always a
     * number and the moments when it is not are the ones worth showing. `0`, `-3`
     * and `99` are all things a person can type, and each has to stay on screen
     * long enough to be told why it will not be taken; a field that silently
     * rewrote them would look broken.
     */
    var draftText: String by mutableStateOf(DEFAULT_AUTOMATIC_BACKUPS.toString())
        private set

    /** Raised each time a result lands, so the keyboard goes back to the control. */
    var focusRecall: Int by mutableStateOf(0)
        private set

    /** The number in the box, or null when what is there is not one. */
    val draftCount: Int?
        get() = draftText.trim().toIntOrNull()

    /** True when the box holds a number that may be kept. */
    val draftIsUsable: Boolean
        get() = draftCount?.let(::isKeepableCount) == true

    /**
     * True when there is something in the box and it may not be kept.
     *
     * What the refusal under the field is shown for. An empty box is not a
     * refusal: somebody who cleared it in order to type something else has not
     * asked for anything yet.
     */
    val draftIsRefused: Boolean
        get() = draftText.isNotBlank() && !draftIsUsable

    /** True when the draft may be saved and is not already what is kept. */
    val canSave: Boolean
        get() = !state.isBusy && draftIsUsable && draftCount != state.countInUse

    /** Reads the file, or finds there is none. Creates nothing either way. */
    suspend fun load() {
        val read = settings.read()
        draftText = read.automaticBackupCount.toString()
        state = RetentionScreenState.Ready(read.automaticBackupCount, read.problem)
    }

    /**
     * Records what the user typed, whatever it is. Nothing is written.
     *
     * Kept as typed rather than corrected. Showing the refusal is the screen's
     * job and enforcing it is [save]'s, and neither is served by a field that
     * changes under somebody's hands.
     */
    fun type(text: String) {
        if (state.isBusy) return
        draftText = text
        // A success message that outlived the value it was about would be
        // telling the user something that is no longer true.
        val ready = state as? RetentionScreenState.Ready ?: return
        if (ready.justSaved) state = ready.copy(justSaved = false)
    }

    /**
     * Moves the number by one, for the two buttons beside the field.
     *
     * Clamped rather than refused: a button that stops at the end of the range
     * is what somebody expects, and a step taken from something that is not a
     * number at all starts from what is actually being kept.
     */
    fun step(by: Int) {
        if (state.isBusy) return
        val from = draftCount ?: state.countInUse ?: DEFAULT_AUTOMATIC_BACKUPS
        type((from + by).coerceIn(MINIMUM_AUTOMATIC_BACKUPS, MAXIMUM_AUTOMATIC_BACKUPS).toString())
    }

    /**
     * Writes the chosen number, once.
     *
     * Refuses while a save is in flight, so a second press cannot start a second
     * write; the store serialises writes as well, so the two guards are
     * independent and the file can never hold the halves of two documents.
     *
     * Saving does not delete anything. PLAN 14.4.12 is explicit that lowering
     * the number is not a destructive act: the value is recorded, and the next
     * automatic backup is what applies it.
     */
    suspend fun save() {
        if (state.isBusy) return
        val wanted = draftCount ?: return
        if (!isKeepableCount(wanted)) return
        val inUse = state.countInUse ?: return

        state = RetentionScreenState.Saving
        try {
            settings.write(wanted)
        } catch (notSaved: SettingsNotSaved) {
            // The file that was there is byte for byte as it was, and so is the
            // number this application is keeping to.
            state = RetentionScreenState.Failed(inUse, notSaved.failure)
            draftText = inUse.toString()
            focusRecall++
            return
        }
        // A broken invariant is deliberately not caught. It is a defect rather
        // than a saved-or-not, and the screen is not the place to hide one.

        // Whatever the file said before, it now says this — so a warning about
        // a file that could not be read no longer applies to it.
        draftText = wanted.toString()
        state = RetentionScreenState.Ready(wanted, problem = null, justSaved = true)
        focusRecall++
    }
}
