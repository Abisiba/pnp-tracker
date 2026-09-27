package dev.pnptracker.ui.theme

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/** The window system's own east-west resize pointer. */
actual val ColumnBoundaryCursor: PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/** The window system's own north-south resize pointer. */
actual val RowBoundaryCursor: PointerIcon = PointerIcon(Cursor(Cursor.S_RESIZE_CURSOR))
