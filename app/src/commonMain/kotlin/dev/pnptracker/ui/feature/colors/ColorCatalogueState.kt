package dev.pnptracker.ui.feature.colors

import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.rules.isValidColorHex

/**
 * Where the colour catalogue is.
 *
 * There is no "nothing here yet" case, and that is not an omission: a database is
 * created with the colours the plan starts everyone off with, so an empty
 * catalogue would be a state the application cannot produce.
 */
sealed interface ColorCatalogueState {
    data object Loading : ColorCatalogueState

    data class Content(
        val colors: List<ColorSummary>,
    ) : ColorCatalogueState
}

/**
 * A colour the user is typing, before anything is written.
 *
 * The value is held as the text that was typed rather than as a parsed colour, so
 * the form can tell a half typed `#1A2` from something that is not a colour value
 * at all, and can stop drawing a preview instead of drawing a wrong one.
 */
data class ColorComposer(
    val name: String = "",
    val hex: String = "",
    /**
     * The colours already written in this value, archived ones included.
     *
     * A remark rather than a refusal: two colours are allowed to share a value,
     * so this never stops a save.
     */
    val colorsSharingHex: List<ColorSummary> = emptyList(),
) {
    val isNameUsable: Boolean get() = name.isNotBlank()

    val isHexUsable: Boolean get() = isValidColorHex(hex.trim())

    /** The value the live preview draws, or null while what is typed is not one. */
    val previewHex: String? get() = hex.trim().takeIf { isValidColorHex(it) }

    /** True when the value is one another colour already carries. */
    val sharesHexWithAnotherColor: Boolean get() = colorsSharingHex.isNotEmpty()

    val canSave: Boolean get() = isNameUsable && isHexUsable
}

/** What the section is showing, what is being typed, and what did not save. */
data class ColorCatalogueScreenState(
    val catalogue: ColorCatalogueState = ColorCatalogueState.Loading,
    val composer: ColorComposer? = null,
    val failure: ColorSetupFailure? = null,
)
