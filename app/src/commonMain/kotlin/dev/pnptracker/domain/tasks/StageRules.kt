package dev.pnptracker.domain.tasks

/**
 * What the counters of a card or board pipeline mean, as rules rather than as a
 * step in one write path.
 *
 * Each counter is a number of pieces standing at that step — printed and waiting,
 * laminated or glued and waiting, cut — so one piece is counted once, at the
 * step it has reached. The counters together can therefore never hold more
 * pieces than the task needs: with five to make, `5 + 5 + 5` describes fifteen
 * pieces and is refused, and the most they can add up to is five.
 *
 * A task is finished when all of its pieces have reached the last step. Records
 * written under the older reading, where every counter ran up to the total, are
 * left exactly as they are: their last step already stands at the total, so
 * they still read as finished, and the user corrects the counters themselves
 * when they next open them.
 */
object StageRules {
    /** Whether [counts] describe no more pieces than [total]. */
    fun fitsTotal(
        counts: Collection<Int>,
        total: Int,
    ): Boolean = counts.sumOf { it.toLong() } <= total

    /** Whether a pipeline with [countsInOrder] has every piece through its last step. */
    fun isFinished(
        countsInOrder: List<Int>,
        total: Int,
    ): Boolean = countsInOrder.isNotEmpty() && countsInOrder.last() >= total

    /**
     * How many pieces have got at least as far as the step at [index]: the ones
     * standing there and the ones already further on.
     */
    fun passedThrough(
        countsInOrder: List<Int>,
        index: Int,
    ): Long = countsInOrder.drop(index).sumOf { it.toLong() }

    /** The counters of a finished pipeline of [steps] steps: every piece at the last one. */
    fun finishedCounts(
        steps: Int,
        total: Int,
    ): List<Int> = List(steps) { index -> if (index == steps - 1) total else 0 }
}
