package dev.pnptracker.domain.importprep

/**
 * Moves a fill colour between the two shapes it has to live in.
 *
 * The reader hands colours over as eight upper case hex characters, because that
 * is how a spreadsheet writes them. The stored raw block keeps the same value
 * packed into an `Int`, which is the ordinary way to carry ARGB and is what the
 * schema already reserves. The conversion is exact in both directions, including
 * for colours whose alpha puts them above `Int.MAX_VALUE`.
 */
fun packArgb(hex: String?): Int? {
    if (hex == null) return null
    if (hex.length != 8) return null
    val value = hex.toLongOrNull(radix = 16) ?: return null
    return value.toInt()
}

/** The inverse of [packArgb]; used when showing a stored colour again. */
fun unpackArgb(packed: Int?): String? {
    if (packed == null) return null
    return packed
        .toUInt()
        .toString(radix = 16)
        .uppercase()
        .padStart(8, '0')
}
