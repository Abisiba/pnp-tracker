package dev.pnptracker.domain.colors

import dev.pnptracker.domain.model.EntityId

/**
 * One of the colours every catalogue starts with.
 *
 * The identity is a fixed constant rather than a generated one, so a colour is
 * the same colour on every machine and in every backup: a task written on one
 * database still points at the same colour in another.
 */
data class BaseColor(
    val id: EntityId,
    val canonicalName: String,
    val hex: String,
    val sortOrder: Int,
)

/**
 * The twelve colours a new catalogue arrives with, taken from the spreadsheet
 * the first import will come from.
 *
 * The single definition of what those colours are. The database seeds itself
 * from this, and the colour picker offers the same twelve as its ready made
 * squares (PLAN 5.7), so the two can never come to disagree about which colours
 * are the base ones.
 *
 * They are ordinary catalogue records and nothing here makes them special:
 * PLAN 5.7 says a base colour can be renamed, changed and removed like any
 * other. What is fixed is only which identities they are — so a picker that
 * offers "the base colours" can say which ones it means without guessing from
 * names the user is free to change. A colour that has been removed is simply not
 * offered; putting it back is PLAN 12.14's restore, which belongs to a later
 * step.
 */
val baseColors: List<BaseColor> =
    listOf(
        baseColor("0f108d69-9651-4e0b-9703-35e2ea1f7b8f", "Beyaz", "#FFFFFF", 0),
        baseColor("958cfe95-8b11-4c33-8967-c2a3816e0e2e", "Siyah", "#111111", 1),
        baseColor("60caf71d-6dcb-424b-8a15-d52d3e3d097a", "Gri", "#808080", 2),
        baseColor("1891dc8a-9d0b-44e2-ad93-f652d732fa32", "Kahverengi", "#795548", 3),
        baseColor("b0e8b346-91bb-49e0-b690-0c599a363c7c", "Kırmızı", "#E53935", 4),
        baseColor("0e3eb90b-510a-4271-850b-395e0a6d73e0", "Sarı", "#FDD835", 5),
        baseColor("45cfc24e-9b01-4d07-a31e-950298a23c8c", "Yeşil", "#43A047", 6),
        baseColor("c003990d-7991-4ecd-870f-be5c854d22bb", "Mavi", "#1E88E5", 7),
        baseColor("58926e12-b3ab-4784-87d7-6c21cc0e2662", "Açık Mavi", "#4FC3F7", 8),
        baseColor("d6065f36-f89e-4980-9ac9-5d4d955a6fd0", "Turuncu", "#FB8C00", 9),
        baseColor("4d66df5f-8864-42a1-bcc1-93f636f3f0fe", "Mor", "#8E24AA", 10),
        baseColor("572a8bdd-5c61-4b63-ab53-6a50c38ad4d1", "Pembe", "#EC407A", 11),
    )

/** The identities of [baseColors], for picking them out of a catalogue. */
val baseColorIds: Set<EntityId> = baseColors.map { it.id }.toSet()

private fun baseColor(
    id: String,
    canonicalName: String,
    hex: String,
    sortOrder: Int,
): BaseColor =
    BaseColor(
        id = EntityId.parse(id),
        canonicalName = canonicalName,
        hex = hex,
        sortOrder = sortOrder,
    )
