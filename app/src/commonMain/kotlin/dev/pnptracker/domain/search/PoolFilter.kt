package dev.pnptracker.domain.search

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.pools.PoolTask

/**
 * Which of the three kinds of task a pool is showing.
 *
 * One choice and not three switches, because a task is exactly one of these:
 * PLAN 13 asks for an `Aktif/tamamlandı/bilgi eksik` filter, and a task that is
 * both finished and still missing information is not a state the application
 * has. The order below is the order they are offered in.
 *
 * There is deliberately no separate `NEEDS_CLASSIFICATION` filter, and it is
 * deliberately *not* folded into `Bilgi eksik` either. The two marks are not the
 * same kind of thing:
 *
 * - PLAN 11.7 lists what `NEEDS_INFO` is for — an unknown colour, an unknown
 *   count, `Sayısına bakılacak` — and says in the same breath that such records
 *   may be kept out of the active pool, `varsayılan davranış engellemektir`.
 * - PLAN 10 says an unclassifiable task `hiçbir üretim havuzuna sessizce
 *   eklenmez`: it never reaches a pool at all. A task that *is* in a pool has
 *   been classified, by the user, and the mark records only that the pool was
 *   their decision rather than the column's. It is ordinary work.
 *
 * The pool's own active count agrees — it counts `is_completed = 0 AND
 * needs_info = 0` and has never looked at classification — so folding the mark
 * in here would make the list on screen and the number in the sidebar disagree
 * about the same tasks. The mark is still shown on every card that carries it,
 * which is where a note about provenance belongs.
 */
enum class TaskStateFilter {
    /** Work still to do: described well enough to start, and not finished. */
    ACTIVE,

    /** Finished, and kept: PLAN 5.6 leaves it in its cell and its colour groups. */
    COMPLETED,

    /** `needsInfo` or `needsClassification`; something about it is still unknown. */
    NEEDS_INFO,
    ;

    /** Which of the three this task is, decided from the task alone. */
    companion object {
        fun of(task: PoolTask): TaskStateFilter =
            when {
                // Asked first: a task nobody can start yet is not active work,
                // and it is not finished work either.
                task.needsInfo -> NEEDS_INFO
                task.isCompleted -> COMPLETED
                else -> ACTIVE
            }
    }
}

/**
 * One of the two marks the import carries onto a task (PLAN 10).
 *
 * These are notes about the work and not about its progress: `MISSING` came from
 * the `Eksik` column of somebody's spreadsheet and says a piece is not in the
 * box, which is a different fact from a print that failed and is owed again.
 * Filtering by them must never be confused with the shortage counter, and the
 * card says which one it is in words for the same reason.
 */
enum class TaskFlagFilter {
    MISSING,
    BORROWED,
    ;

    fun isOn(task: PoolTask): Boolean =
        when (this) {
            MISSING -> task.isMissing
            BORROWED -> task.isBorrowed
        }
}

/**
 * What one pool screen is being asked to show.
 *
 * Every field is a narrowing, and they narrow together: a task has to pass all
 * of them. Inside one field several choices widen instead — two colours mean
 * either colour, both flags mean either flag — because that is what choosing two
 * of something means to the person choosing them.
 *
 * Held as values rather than as a pile of booleans on the screen state, so
 * "nothing is filtered" is one comparison and the summary the user reads is
 * derived rather than tracked. Nothing here is stored: PLAN asks for filters
 * that are easy to move between, not for a setting, so they last as long as the
 * window does and cost no column and no file.
 */
data class PoolFilter(
    val query: SearchQuery = SearchQuery.NONE,
    /** Colours a task may be made in; empty means any. */
    val colorIds: Set<EntityId> = emptySet(),
    /** Whether tasks with no colour at all are wanted (PLAN 12.10's own section). */
    val awaitingColor: Boolean = false,
    val state: TaskStateFilter = TaskStateFilter.ACTIVE,
    /** Import marks a task may carry; empty means any. */
    val flags: Set<TaskFlagFilter> = emptySet(),
    /** First unfinished stages a pipeline task may be at; empty means any. */
    val stages: Set<ProductionStage> = emptySet(),
    /**
     * Whether what is owed comes first.
     *
     * An ordering and not a narrowing: PLAN 13 asks for tasks with a failed print
     * to be brought forward, not for the rest to be hidden. Kept here anyway
     * because it is one of the things the toolbar offers and one of the things
     * the summary has to say.
     */
    val shortagesFirst: Boolean = false,
) {
    /** True when this is asking for anything other than the ordinary view. */
    val isNarrowed: Boolean get() = this != NONE

    /**
     * How many choices the user has made, for the count on the filter button.
     *
     * The state counts only when it is not the one the screen opens on: showing
     * `1` before anybody has touched anything would be telling them they had
     * filtered something.
     */
    val chosenCount: Int
        get() =
            colorIds.size +
                (if (awaitingColor) 1 else 0) +
                (if (state != TaskStateFilter.ACTIVE) 1 else 0) +
                flags.size +
                stages.size +
                (if (shortagesFirst) 1 else 0)

    /** Whether one task is one of the ones being asked for. */
    fun matches(task: PoolTask): Boolean =
        matchesQuery(task) && matchesColor(task) && matchesState(task) && matchesFlags(task) && matchesStage(task)

    private fun matchesQuery(task: PoolTask): Boolean = query.matchesAny(listOf(task.name, task.gameName))

    /**
     * A task passes if it is made in **any** of the chosen colours.
     *
     * Compared by the colour's identity, never by its name or its value: PLAN
     * 5.7 lets both be changed, and two colours written in the same hex are two
     * colours. `Renk seçilecek` sits beside the real colours rather than under
     * them, and choosing it as well as one widens the answer.
     */
    private fun matchesColor(task: PoolTask): Boolean {
        if (colorIds.isEmpty() && !awaitingColor) return true
        if (awaitingColor && task.colors.isEmpty()) return true
        return task.colors.any { it.colorId in colorIds }
    }

    private fun matchesState(task: PoolTask): Boolean = TaskStateFilter.of(task) == state

    private fun matchesFlags(task: PoolTask): Boolean = flags.isEmpty() || flags.any { it.isOn(task) }

    /**
     * A pipeline task passes if the step it is waiting at is one of the chosen.
     *
     * The *first unfinished* step and nothing else, which is the one PLAN 12.11
     * puts on the badge and the one the work is actually waiting at. A task whose
     * whole pipeline is done has no such step, so it cannot match a stage that
     * was asked for — it is still reachable through the state filter, where
     * finished work belongs.
     */
    private fun matchesStage(task: PoolTask): Boolean = stages.isEmpty() || task.firstUnfinishedStage in stages

    companion object {
        /** The ordinary view: everything active, in the pool's own order. */
        val NONE = PoolFilter()
    }
}

/**
 * The tasks of one pool this filter is asking for, in the order they arrived.
 *
 * There is deliberately no shortcut for [PoolFilter.NONE]. The default is not
 * "everything": it is the active work, which is what a pool screen opens on, and
 * a fast path that returned the whole list would put finished tasks and tasks
 * waiting on information onto it.
 */
fun filterPoolTasks(
    tasks: List<PoolTask>,
    filter: PoolFilter,
): List<PoolTask> = tasks.filter(filter::matches)
