package dev.pnptracker.platform.xlsx

import org.apache.poi.xssf.model.ThemesTable
import org.apache.poi.xssf.usermodel.XSSFColor

/**
 * Turns a spreadsheet colour into `AARRGGBB`, or into nothing.
 *
 * A colour that cannot be resolved comes back as null. Guessing black would be
 * worse than admitting ignorance: black is a colour a user could have chosen on
 * purpose, so a wrong guess is indistinguishable from a real answer.
 *
 * Four ways of writing a colour have to be understood, because a real file uses
 * all of them: a direct RGB value, an indexed palette entry, a reference into
 * the workbook theme, and a theme reference darkened or lightened by a tint.
 */
internal fun resolveArgb(
    color: XSSFColor?,
    themes: ThemesTable?,
): String? {
    if (color == null || color.isAuto) return null

    // The library resolves a plain theme reference on its own but ignores the
    // tint that goes with it, so a tinted colour has to be recomposed here or a
    // dark green would be reported as the undarkened theme green.
    if (color.hasTint()) {
        val tinted = color.rgbWithTint ?: return null
        return argbOf(alpha = "FF", rgb = tinted)
    }

    val hex = color.argbHex ?: themedFallback(color, themes) ?: return null
    return normalizeArgb(hex)
}

private fun themedFallback(
    color: XSSFColor,
    themes: ThemesTable?,
): String? {
    if (!color.isThemed || themes == null) return null
    return themes.getThemeColor(color.theme)?.argbHex
}

private fun argbOf(
    alpha: String,
    rgb: ByteArray,
): String? {
    if (rgb.size != 3) return null
    return alpha + rgb.joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }.uppercase()
}

/** Accepts the six and eight character forms the library may return. */
private fun normalizeArgb(hex: String): String? {
    val upper = hex.uppercase()
    if (!upper.all { it in "0123456789ABCDEF" }) return null
    return when (upper.length) {
        8 -> upper
        6 -> "FF$upper"
        else -> null
    }
}
