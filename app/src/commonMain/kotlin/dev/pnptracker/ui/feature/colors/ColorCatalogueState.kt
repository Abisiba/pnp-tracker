package dev.pnptracker.ui.feature.colors

import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.HsbColor
import dev.pnptracker.domain.colors.WheelNudge
import dev.pnptracker.domain.colors.WheelPoint
import dev.pnptracker.domain.colors.atWheelPoint
import dev.pnptracker.domain.colors.baseColorIds
import dev.pnptracker.domain.colors.hsbOfHex
import dev.pnptracker.domain.colors.nudged

/**
 * Where the colour catalogue is.
 *
 * An empty catalogue is [Content] with nothing in it rather than a case of its
 * own. A new database arrives with the colours the plan starts everyone off
 * with, but every one of them can be removed, so the screen has to be able to
 * show a catalogue with nothing left in it.
 */
sealed interface ColorCatalogueState {
    data object Loading : ColorCatalogueState

    data class Content(
        val colors: List<ColorSummary>,
    ) : ColorCatalogueState
}

/**
 * A colour the user is making, before anything is written.
 *
 * The colour is held as a place on the wheel rather than as `#RRGGBB` text.
 * PLAN 5.7 asks for a wheel and one compact brightness control and refuses a
 * standing hex field, so text is not what is being edited here — it is only what
 * gets written at the end. Keeping the wheel's own numbers also means a drag is
 * a run of small changes to two of them rather than a run of conversions to
 * eight-bit channels and back, so the colour cannot drift while the user holds
 * still.
 *
 * [startedAt] is where the wheel was opened, and is what tells a form nobody has
 * touched from one with an answer in it. Without it, closing an untouched
 * picker would have to ask whether to throw something away.
 *
 * There is no `isSaved` and no draft colour: PLAN 5.7 says there is no
 * one-shot, unnamed or unsaved colour, so this is a form and not a colour.
 */
data class ColorComposer(
    val name: String = "",
    val color: HsbColor,
    val startedAt: HsbColor,
) {
    /** The value this would be written as: `#RRGGBB`, upper case, opaque. */
    val hex: String get() = color.toHex()

    /** PLAN 5.7: a colour without a name cannot be saved at all. */
    val isNameUsable: Boolean get() = name.isNotBlank()

    val canSave: Boolean get() = isNameUsable

    /** True once there is something here that closing would throw away. */
    val isTouched: Boolean get() = name.isNotEmpty() || color != startedAt

    /** The name as it would be stored, with the spaces at its ends left behind. */
    val cleanName: String get() = name.trim()

    /** The same colour somewhere else on the wheel; the brightness stays. */
    fun movedTo(
        point: WheelPoint,
        radius: Float,
    ): ColorComposer = copy(color = color.atWheelPoint(point, radius))

    /** The same colour one arrow key press away. */
    fun nudgedBy(nudge: WheelNudge): ColorComposer = copy(color = color.nudged(nudge))

    /** Only the brightness moves; the place on the wheel does not. */
    fun brightenedTo(brightness: Float): ColorComposer = copy(color = color.withBrightness(brightness))

    /** The whole colour set to what a catalogue colour is now. */
    fun setTo(hex: String): ColorComposer = copy(color = hsbOfHex(hex))

    /**
     * The colours already written in this value.
     *
     * Worked out from the catalogue the screen is already holding rather than
     * asked of the database: this is read again on every step of a wheel drag,
     * and a query there would be hundreds of them for a remark. It is a remark —
     * PLAN 5.7 allows the same value under different names — so it never stands
     * in the way of a save.
     */
    fun sharedWith(catalogue: List<ColorSummary>): List<ColorSummary> = catalogue.filter { it.hex.equals(hex, ignoreCase = true) }

    companion object {
        /**
         * Where the wheel starts when there is nothing to start it from.
         *
         * A mid grey: it sits at the centre of the wheel, claims no hue the user
         * did not choose, and is the same colour every time. Only reached with
         * an empty catalogue, which is a state PLAN 5.7 allows because every
         * colour, the base ones included, can be removed.
         */
        const val FALLBACK_START = "#808080"

        /**
         * A form opened on [startHex], or on the fallback when there is none.
         *
         * The name always starts empty, whatever the colour started as. PLAN 5.7
         * makes the name a decision the user has to make, and offering the name
         * of the colour they are working from would be making it for them.
         */
        fun startingFrom(startHex: String?): ColorComposer {
            val start = hsbOfHex(startHex ?: FALLBACK_START)
            return ColorComposer(name = "", color = start, startedAt = start)
        }
    }
}

/**
 * The twelve squares the picker offers, as the catalogue has them now.
 *
 * Picked out by identity rather than by name or value, because PLAN 5.7 lets the
 * user rename a base colour and change what it is: the square then shows what
 * they made it, and is still the same colour. One that has been removed is not
 * offered — putting it back is PLAN 12.14's restore, which is a later step's
 * work — and the order is the catalogue's own.
 */
fun baseColorsIn(catalogue: List<ColorSummary>): List<ColorSummary> = catalogue.filter { it.id in baseColorIds }

/** What the section is showing, what is being made, and what did not save. */
data class ColorCatalogueScreenState(
    val catalogue: ColorCatalogueState = ColorCatalogueState.Loading,
    val composer: ColorComposer? = null,
    val failure: ColorSetupFailure? = null,
)
