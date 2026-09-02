package dev.pnptracker.domain.importhint

/**
 * Reads the count a cell opens with, such as the `15` of `15 KIRMIZI**`.
 *
 * Only a run of digits at the very front of the text, after any spacing, counts.
 * The reference file writes the amount first and the thing second, so a number
 * anywhere else in the line is part of what the thing is called — `Ticket to
 * Ride 1910` is not an order for nineteen hundred of anything — and reading one
 * would put a made up total on somebody's task.
 *
 * A count of zero is not a count: PLAN 5.6 keeps a total either unknown or above
 * nothing, and `0` in the source is somebody's note rather than an amount. A run
 * too long to fit in an [Int] is likewise not read, because the answer would be
 * a different number from the one written.
 *
 * The answer is always a hint, never a decision: nothing here writes it anywhere
 * and the user is shown it in a field they can change or empty.
 */
fun detectLeadingQuantity(rawText: String): ImportHint.Quantity? {
    var start = 0
    while (start < rawText.length && rawText[start].isWhitespace()) start++
    var end = start
    while (end < rawText.length && rawText[end].isDigit()) end++
    if (end == start) return null
    val value = rawText.substring(start, end).toIntOrNull() ?: return null
    if (value <= 0) return null
    return ImportHint.Quantity(evidence = TextRange(start, end), value = value)
}
