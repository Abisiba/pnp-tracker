package dev.pnptracker.domain.colors

/** One of the tasks a colour is used by, named so the user can recognise it. */
data class ColorUsageSample(
    val taskName: String,
    val gameName: String?,
)

/**
 * What losing a colour would cost, in numbers the user can weigh.
 *
 * PLAN 5.9 requires the number of tasks before a colour can be removed. The rest
 * is here because the number alone does not say what changes: a colour used by
 * forty finished tasks and one used by forty unfinished ones are the same number
 * and not the same decision.
 *
 * [samples] is deliberately short. Listing every task would turn a confirmation
 * into a list to scroll, and the thing being confirmed cannot be undone.
 */
data class ColorUsage(
    val taskCount: Int,
    val unfinishedTaskCount: Int,
    val gameCount: Int,
    val tasksLosingTheirLastColor: Int,
    val samples: List<ColorUsageSample>,
) {
    val isUsed: Boolean get() = taskCount > 0

    /** How many tasks there are beyond the ones named. */
    val beyondTheSamples: Int get() = (taskCount - samples.size).coerceAtLeast(0)

    companion object {
        /** How many tasks a confirmation names before it starts counting instead. */
        const val SAMPLE_LIMIT = 5

        val UNUSED: ColorUsage =
            ColorUsage(
                taskCount = 0,
                unfinishedTaskCount = 0,
                gameCount = 0,
                tasksLosingTheirLastColor = 0,
                samples = emptyList(),
            )
    }
}
