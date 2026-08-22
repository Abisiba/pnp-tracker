package dev.pnptracker.domain.colors

import dev.pnptracker.domain.model.EntityId

/**
 * A colour as the catalogue lists it.
 *
 * [hex] travels with the name on purpose: PLAN 17 says a colour may never be the
 * only carrier of meaning, so every swatch a screen draws has the written name
 * beside it and neither can be shown without the other.
 *
 * [sortOrder] is the place the user gave the colour, which is what PLAN 13 orders
 * the colour groups by. It is carried rather than recomputed so the list cannot
 * quietly re-sort itself.
 */
data class ColorSummary(
    val id: EntityId,
    val canonicalName: String,
    val hex: String,
    val sortOrder: Int,
)
