package dev.pnptracker.ui.feature.colors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.FULL_TURN
import dev.pnptracker.domain.colors.HsbColor
import dev.pnptracker.domain.colors.WheelNudge
import dev.pnptracker.domain.colors.WheelPoint
import dev.pnptracker.domain.colors.wheelPoint
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.theme.opaqueColorOf
import dev.pnptracker.ui.theme.readableInkOn
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

private val WheelSize = 132.dp
private val SquareSize = 26.dp
private val SquareTarget = 34.dp
private val SquareShape = RoundedCornerShape(6.dp)
private val PreviewSize = 30.dp

/** How many colours the wheel's sweep is built from; every 15 degrees. */
private const val SWEEP_STOPS = 24

/**
 * The small colour picker PLAN 5.7 asks for: twelve squares, a wheel and one
 * brightness control.
 *
 * Deliberately no more than that. There is no standing hex field, no channel
 * boxes, no alpha and no history — PLAN 5.7 names those as the thing this is
 * not. The brightness control is the one extra it does allow, and it earns its
 * place: a hue-and-saturation wheel alone cannot reach a dark colour at all, so
 * without it half the catalogue would be unmakeable.
 *
 * Every part writes to one colour and reads back from it, so the squares, the
 * wheel, the slider and the preview can never disagree about what is being made.
 */
@Composable
fun ColorPicker(
    composer: ColorComposer,
    catalogue: List<ColorSummary>,
    enabled: Boolean,
    onChooseBase: (dev.pnptracker.domain.model.EntityId) -> Unit,
    onMoveWheel: (WheelPoint, Float) -> Unit,
    onNudgeWheel: (WheelNudge) -> Unit,
    onBrightness: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BaseColorSquares(
            catalogue = catalogue,
            chosenHex = composer.hex,
            enabled = enabled,
            onChoose = onChooseBase,
        )
        ColorWheel(
            color = composer.color,
            enabled = enabled,
            onMove = onMoveWheel,
            onNudge = onNudgeWheel,
        )
        BrightnessControl(color = composer.color, enabled = enabled, onChange = onBrightness)
        ColorPreview(composer = composer)
    }
}

/**
 * The twelve base colours, as squares with their written names.
 *
 * The name is beside the square and not only in a tooltip: PLAN 17 does not
 * allow a colour to be the only thing carrying a meaning, so a reader who cannot
 * tell two squares apart still reads two names. Which one is chosen is said in
 * words as well as drawn, for the same reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BaseColorSquares(
    catalogue: List<ColorSummary>,
    chosenHex: String,
    enabled: Boolean,
    onChoose: (dev.pnptracker.domain.model.EntityId) -> Unit,
) {
    val squares = baseColorsIn(catalogue)
    Text(
        text = stringResource(Strings.Colors.baseLabel),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (squares.isEmpty()) {
        Text(
            text = stringResource(Strings.Colors.baseNone),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        squares.forEach { color ->
            val fill = opaqueColorOf(color.hex)
            val isChosen = color.hex.equals(chosenHex, ignoreCase = true)
            val stateText =
                stringResource(
                    if (isChosen) Strings.Accessibility.selected else Strings.Accessibility.notSelected,
                )
            val description = stringResource(Strings.Colors.swatch, color.canonicalName)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(SquareTarget)
                        .selectable(selected = isChosen, enabled = enabled, onClick = { onChoose(color.id) })
                        .semantics {
                            contentDescription = description
                            stateDescription = stateText
                        },
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(SquareSize)
                            .clip(SquareShape)
                            .background(fill)
                            .border(
                                width = if (isChosen) 2.dp else 1.dp,
                                // Drawn in ink that reads on the colour itself,
                                // so the mark on white and the mark on black are
                                // both there to be seen in either theme.
                                color = if (isChosen) readableInkOn(fill) else MaterialTheme.colorScheme.outlineVariant,
                                shape = SquareShape,
                            ),
                )
            }
        }
    }
}

/**
 * The wheel: hue around it, saturation out from the middle.
 *
 * One focusable control rather than a picture that only answers to a pointer.
 * PLAN 17 asks for the main actions to be reachable from the keyboard, so the
 * arrow keys move it and what it is showing is said in words: a reader who never
 * sees the wheel still knows which colour they are on.
 */
@Composable
private fun ColorWheel(
    color: HsbColor,
    enabled: Boolean,
    onMove: (WheelPoint, Float) -> Unit,
    onNudge: (WheelNudge) -> Unit,
) {
    val label = stringResource(Strings.Colors.wheelLabel)
    val stateText =
        stringResource(
            Strings.Colors.wheelState,
            color.hue.roundToInt().toString(),
            (color.saturation * 100).roundToInt().toString(),
        )
    // The sweep is a run of colour conversions, and a drag would rebuild it on
    // every pointer move for no change at all: only the brightness alters it.
    val sweep =
        remember(color.brightness) {
            // Compose sweeps clockwise from three o'clock; the wheel is read the
            // way an angle is, so the hues are laid out the other way round.
            List(SWEEP_STOPS + 1) { stop ->
                val hue = FULL_TURN - (FULL_TURN * stop / SWEEP_STOPS)
                opaqueColorOf(HsbColor.of(hue, 1f, color.brightness).toHex())
            }
        }
    val middle = remember(color.brightness) { opaqueColorOf(HsbColor.of(0f, 0f, color.brightness).toHex()) }

    Canvas(
        modifier =
            Modifier
                .size(WheelSize)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .semantics {
                    contentDescription = label
                    stateDescription = stateText
                }.focusable(enabled = enabled)
                .onPreviewKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val nudge =
                        when (event.key) {
                            Key.DirectionRight -> WheelNudge.HUE_FORWARD
                            Key.DirectionLeft -> WheelNudge.HUE_BACK
                            Key.DirectionUp -> WheelNudge.SATURATION_OUT
                            Key.DirectionDown -> WheelNudge.SATURATION_IN
                            else -> return@onPreviewKeyEvent false
                        }
                    onNudge(nudge)
                    true
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { position -> onMove(position.asWheelPoint(size.width, size.height), radiusOf()) }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { position -> onMove(position.asWheelPoint(size.width, size.height), radiusOf()) },
                    ) { change, _ ->
                        onMove(change.position.asWheelPoint(size.width, size.height), radiusOf())
                    }
                },
    ) {
        val radius = min(size.width, size.height) / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(brush = Brush.sweepGradient(sweep, center = centre), radius = radius, center = centre)
        drawCircle(
            brush = Brush.radialGradient(listOf(middle, middle.copy(alpha = 0f)), center = centre, radius = radius),
            radius = radius,
            center = centre,
        )
        val mark = color.wheelPoint(radius)
        val at = Offset(centre.x + mark.x, centre.y + mark.y)
        // Two rings rather than one: whichever of them the colour underneath
        // swallows, the other one is still there. A single mark disappears on
        // exactly the two colours people reach for first.
        drawCircle(color = Color.White, radius = 7f, center = at, style = Stroke(width = 3f))
        drawCircle(color = Color.Black, radius = 7f, center = at, style = Stroke(width = 1.5f))
    }
    Text(
        text = stringResource(Strings.Colors.wheelHint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Where a point on the canvas is, measured from the middle of the wheel. */
private fun Offset.asWheelPoint(
    width: Int,
    height: Int,
): WheelPoint = WheelPoint(x = x - width / 2f, y = y - height / 2f)

private fun androidx.compose.ui.input.pointer.PointerInputScope.radiusOf(): Float = min(size.width, size.height) / 2f

/**
 * The one brightness control PLAN 5.7 allows.
 *
 * An ordinary slider, so it is adjustable from the keyboard without anything
 * being invented for it, and it moves nothing but the brightness: the place on
 * the wheel is exactly where it was before and after.
 */
@Composable
private fun BrightnessControl(
    color: HsbColor,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val label = stringResource(Strings.Colors.brightnessLabel)
    val stateText = stringResource(Strings.Colors.brightnessState, (color.brightness * 100).roundToInt().toString())
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = color.brightness,
        onValueChange = onChange,
        enabled = enabled,
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = stateText
            },
    )
}

/**
 * What is about to be saved: the colour, and the name it will carry.
 *
 * The two are described together, because separately neither is the answer to
 * "what am I making". A name that has not been typed yet is said to be missing
 * rather than left silent.
 */
@Composable
private fun ColorPreview(composer: ColorComposer) {
    val fill = opaqueColorOf(composer.hex)
    val shown = composer.cleanName.ifEmpty { stringResource(Strings.Colors.previewUnnamed) }
    val description = stringResource(Strings.Colors.previewOf, shown, composer.hex)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    ) {
        Box(
            modifier =
                Modifier
                    .size(PreviewSize)
                    .clip(SquareShape)
                    .background(fill)
                    .border(1.dp, readableInkOn(fill), SquareShape),
        )
        Text(
            text = shown,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}
