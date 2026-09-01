package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.model.ProductionStage

/**
 * A pipeline as it stood when somebody opened it to be changed.
 *
 * Handed back with the save so a panel left open while the work moved on is
 * refused rather than allowed to put back the picture it was opened with. It is
 * the whole of what the target was described against, which is why the total
 * travels with the counts rather than beside them: a target of `15/10/5` means
 * one thing against a task of twenty and another against a task of twelve, and a
 * save checked only against the counts would let the second through as though it
 * were the first.
 *
 * Nothing else about the task is in here. Renaming a task, noting something on
 * it or giving it another colour does not change what its steps are counted
 * against, so none of those may refuse a save that is otherwise still true.
 */
data class StageSnapshot(
    /** What every step counts up to, or null when the task was given no total. */
    val requiredQuantity: Int?,
    /** What each step stood at, by the step it belongs to. */
    val stages: Map<ProductionStage, Int>,
)
