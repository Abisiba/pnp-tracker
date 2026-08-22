package dev.pnptracker.ui.feature.colors

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.rules.isValidColorHex
import kotlinx.coroutines.flow.collect

/**
 * The global colour catalogue, and the one form that adds to it.
 *
 * The list is read as a stream, so a colour that is created shows up without
 * anything here having to remember to reload.
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

    /** Collects the catalogue until cancelled. */
    suspend fun observeColors() {
        catalogue.observeColors().collect { colors ->
            state = state.copy(catalogue = ColorCatalogueState.Content(colors))
        }
    }

    fun startComposer() {
        state = state.copy(composer = ColorComposer(), failure = null)
    }

    fun editName(name: String) {
        state = state.copy(composer = state.composer?.copy(name = name))
    }

    /**
     * Takes the value being typed and, once it is a whole one, finds out who else
     * carries it.
     *
     * The earlier answer is dropped the moment the text changes, so a remark can
     * never be left standing next to a value it was not about. A reply that
     * arrives after the user has typed on is discarded for the same reason.
     */
    suspend fun editHex(hex: String) {
        state = state.copy(composer = state.composer?.copy(hex = hex, colorsSharingHex = emptyList()))
        val trimmed = hex.trim()
        if (!isValidColorHex(trimmed)) return
        val sharing = catalogue.colorsUsingHex(trimmed)
        val composer = state.composer ?: return
        if (composer.hex != hex) return
        state = state.copy(composer = composer.copy(colorsSharingHex = sharing))
    }

    /** Changes nothing anywhere; the colour was never written. */
    fun cancelComposer() {
        state = state.copy(composer = null)
    }

    /**
     * Saves the colour being typed, if it is complete enough to save.
     *
     * Sharing a value with another colour does not stand in the way: it was a
     * remark, and the user has seen it.
     *
     * A second call while the first is still on its way does nothing, so one
     * insistent click cannot turn into two colours. A colour that does not save
     * leaves the form open with what was typed in it.
     */
    suspend fun save() {
        val composer = state.composer ?: return
        if (!composer.canSave || isSaving) return
        isSaving = true
        try {
            catalogue.createColor(canonicalName = composer.name, hex = composer.hex.trim())
            state = state.copy(composer = null, failure = null)
        } catch (failure: ColorSetupException) {
            state = state.copy(failure = failure.failure)
        } finally {
            isSaving = false
        }
    }
}
