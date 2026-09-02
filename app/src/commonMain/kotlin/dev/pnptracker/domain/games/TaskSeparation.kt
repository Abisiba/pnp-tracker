package dev.pnptracker.domain.games

/**
 * The one character an import puts between two tasks it writes into a cell.
 *
 * A cell document is its pieces laid end to end with nothing between them (PLAN
 * 5.5), which is right for text the user typed and wrong for tasks an import
 * appends: two tasks written one after the other read as `Kırmızı evMavi ev` —
 * one word to a reader, one word to a screen reader, and one word again when the
 * cell is copied out. The boundary between two tasks has to be *in* the
 * document, not drawn around it, or it is only true of the screen it was drawn
 * on.
 *
 * A single ordinary space, and never more than one. Anything wider would be a
 * layout decision written into the user's own text, and would still be there
 * when the cell was pasted somewhere that has no idea why.
 */
const val TASK_SEPARATOR: String = " "

/**
 * Whether a task appended to a document that currently reads [text] needs a
 * separator in front of it.
 *
 * Nothing is needed when there is nothing to be separated from — an empty cell
 * takes its first task flush against the start — and nothing is needed when the
 * writing already ends in a space, a tab or a line ending, because the boundary
 * the user typed is a boundary already. Adding one there would be a second space
 * they never asked for.
 *
 * This never trims, rewrites or inspects anything but the last character, so the
 * text it is asked about comes back out of the document exactly as it went in.
 */
fun taskNeedsSeparatorAfter(text: String): Boolean = text.isNotEmpty() && !text.last().isWhitespace()
