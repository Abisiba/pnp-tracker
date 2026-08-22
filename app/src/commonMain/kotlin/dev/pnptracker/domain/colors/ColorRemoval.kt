package dev.pnptracker.domain.colors

/**
 * What deleting a colour actually took with it.
 *
 * Reported back so the screen can tell the user what happened rather than
 * leaving them to notice. [tasksLeftWithoutAColor] is the number that now wait
 * for a colour to be picked again; none of them was deleted.
 */
data class ColorRemoval(
    val taskCount: Int,
    val removedRelationCount: Int,
    val removedAliasCount: Int,
    val tasksLeftWithoutAColor: Int,
)
