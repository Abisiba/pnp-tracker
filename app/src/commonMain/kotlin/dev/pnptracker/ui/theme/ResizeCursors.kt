package dev.pnptracker.ui.theme

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * The pointer shown over a boundary that can be dragged (PLAN 12.17).
 *
 * Not in the pointer icons every platform shares, and a hand would say "this can
 * be pressed" rather than "this can be pulled", so each platform names its own.
 */
expect val ColumnBoundaryCursor: PointerIcon

/** The pointer shown over a row's bottom edge. */
expect val RowBoundaryCursor: PointerIcon
