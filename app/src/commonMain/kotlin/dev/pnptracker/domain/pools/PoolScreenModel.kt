package dev.pnptracker.domain.pools

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType

/** Left over when `İ` is lower cased; carries no meaning in a name. */
private const val COMBINING_DOT_ABOVE = '̇'

/**
 * Folds a game or task name into the key names are ordered by.
 *
 * Case only. `Yarasa` and `yarasa` belong next to one another, and lower casing
 * `İ` can leave a combining dot behind which would otherwise sort it away from
 * `i`.
 *
 * Deliberately not the colour normaliser: that one also folds `ı` into `i` so
 * that a colour typed on either keyboard is found, and doing the same here would
 * file `Kılıç` and `Kilit` as though the letters were the same. A name is the
 * user's own word and is only ever compared with itself.
 *
 * The name shown on screen is never this; only the key it is placed by.
 */
internal fun poolSortKey(text: String): String =
    buildString(text.length) {
        text.forEach { character ->
            if (character != COMBINING_DOT_ABOVE) append(character.lowercaseChar())
        }
    }

/**
 * The order tasks are listed in inside any one group.
 *
 * PLAN 13 puts what has gone wrong first — a shortage or a reported failure is
 * work that has already been paid for once — and then falls back to the game and
 * the task by name. Identity comes last at both levels so that two games or two
 * tasks sharing a name never swap places between two reads of the same data.
 */
private val byAttentionThenName =
    compareByDescending<PoolTask> { it.needsAttention }
        .thenBy { poolSortKey(it.gameName) }
        .thenBy { it.gameId.toString() }
        .thenBy { poolSortKey(it.name) }
        .thenBy { it.taskId.toString() }

/**
 * The order pipeline tasks are listed in.
 *
 * PLAN 13 asks for the work that can be picked up next rather than the work
 * furthest along, so a task with something still to do comes before one with
 * nothing, and among those the earliest unfinished stage comes first: printing
 * before laminating or gluing, and either before cutting. PLAN does not order
 * two different applicable stages against one another in so many words, so the
 * pipeline's own order is used, which is the only order the domain already has.
 */
private val byStageThenAttentionThenName =
    compareByDescending<PoolTask> { it.firstUnfinishedStage != null }
        .thenBy { it.stageRank }
        .thenByDescending { it.needsAttention }
        .thenBy { poolSortKey(it.gameName) }
        .thenBy { it.gameId.toString() }
        .thenBy { poolSortKey(it.name) }
        .thenBy { it.taskId.toString() }

/** What one colour group of the 3D pool holds, and what it adds up to. */
data class PoolColorGroup(
    val color: PoolColor,
    val tasks: List<PoolTask>,
) {
    val taskCount: Int get() = tasks.size
    val requiredTotal: Int get() = tasks.sumOf { it.requiredQuantity ?: 0 }
    val missingTotal: Int get() = tasks.sumOf { it.currentMissingQuantity }
    val failureTotal: Int get() = tasks.sumOf { it.failureTotal }
}

/**
 * The tasks of the 3D pool that have no colour yet.
 *
 * A section rather than a group, because there is no colour to head it with.
 * PLAN 5.10 makes having none a real state a task may sit in — from the start,
 * or after the last colour it used was deleted — and PLAN 12.10 puts it at the
 * top of the pool where it can be dealt with.
 */
data class PoolAwaitingColorSection(
    val tasks: List<PoolTask>,
) {
    val taskCount: Int get() = tasks.size
    val requiredTotal: Int get() = tasks.sumOf { it.requiredQuantity ?: 0 }
    val missingTotal: Int get() = tasks.sumOf { it.currentMissingQuantity }
    val failureTotal: Int get() = tasks.sumOf { it.failureTotal }
    val isEmpty: Boolean get() = tasks.isEmpty()
}

/**
 * How the 3D pool is laid out.
 *
 * Three sections in PLAN 12.10's order, and a task belongs to exactly one of
 * them: none of its colours, one of them, or several. Which one is decided by
 * counting its colours and by nothing else, so a task cannot be listed twice
 * over as both a single-colour task and a multi-colour one.
 *
 * Inside the last section a task does appear once per colour it is made in —
 * that is what PLAN asks for and what makes the pool useful to somebody about to
 * print in one colour — but every appearance is the same [PoolTask] and carries
 * the same identity.
 */
data class ThreeDPoolModel(
    val awaitingColor: PoolAwaitingColorSection,
    val singleColorGroups: List<PoolColorGroup>,
    val multicolorGroups: List<PoolColorGroup>,
) {
    val isEmpty: Boolean
        get() = awaitingColor.isEmpty && singleColorGroups.isEmpty() && multicolorGroups.isEmpty()
}

/** What one pool screen shows. */
sealed interface PoolModel {
    val poolType: PoolType
    val isEmpty: Boolean

    /** The 3D pool, which is the one PLAN 12.10 groups by colour. */
    data class ThreeD(
        val sections: ThreeDPoolModel,
    ) : PoolModel {
        override val poolType: PoolType get() = PoolType.THREE_D
        override val isEmpty: Boolean get() = sections.isEmpty
    }

    /**
     * A pool that is one list.
     *
     * Cards, board pieces and special work are not grouped by colour: PLAN 12.11
     * to 12.13 describe them by their stages and their kind, and none of them
     * carries colours at all. Grouping them by colour would put every one of
     * them under `Renk seçilecek`, which would say something untrue about work
     * that was never going to have a colour.
     */
    data class Flat(
        override val poolType: PoolType,
        val tasks: List<PoolTask>,
    ) : PoolModel {
        override val isEmpty: Boolean get() = tasks.isEmpty()
    }
}

/**
 * Lays one pool out for the screen.
 *
 * Pure, and the only place the layout is decided, so what a test measures is
 * what the screen draws. Nothing here reads or writes anything.
 */
fun poolModelOf(snapshot: PoolSnapshot): PoolModel =
    when (snapshot.poolType) {
        PoolType.THREE_D -> PoolModel.ThreeD(threeDSectionsOf(snapshot.tasks))
        PoolType.CARD, PoolType.BOARD ->
            PoolModel.Flat(snapshot.poolType, snapshot.tasks.sortedWith(byStageThenAttentionThenName))

        PoolType.SPECIAL -> PoolModel.Flat(snapshot.poolType, snapshot.tasks.sortedWith(byAttentionThenName))
    }

private fun threeDSectionsOf(tasks: List<PoolTask>): ThreeDPoolModel {
    val awaiting = tasks.filter { it.colors.isEmpty() }
    val single = tasks.filter { it.colors.size == 1 }
    val several = tasks.filter { it.colors.size > 1 }
    return ThreeDPoolModel(
        awaitingColor = PoolAwaitingColorSection(awaiting.sortedWith(byAttentionThenName)),
        singleColorGroups = colorGroupsOf(single),
        multicolorGroups = colorGroupsOf(several),
    )
}

/**
 * Gathers tasks under each colour they are made in.
 *
 * Grouped by the colour's identity and by nothing else. Two colours written in
 * the same hex are two colours and keep their own groups; a colour the user has
 * renamed keeps its group and simply arrives under the new name. Grouping by
 * name or by value would merge them, and PLAN 5.7 lets both be changed freely.
 *
 * A task lands in each of its groups once. It cannot land in one twice: a task
 * holds a given colour at most once, and the same task is never added to the
 * same group from two rows.
 *
 * The groups come out in the user's own colour order (PLAN 5.10), with identity
 * settling two colours placed at the same spot so the list never reshuffles.
 */
private fun colorGroupsOf(tasks: List<PoolTask>): List<PoolColorGroup> {
    val gathered = LinkedHashMap<EntityId, MutableList<PoolTask>>()
    val colors = LinkedHashMap<EntityId, PoolColor>()
    tasks.forEach { task ->
        task.colors.forEach { color ->
            colors.getOrPut(color.colorId) { color }
            gathered.getOrPut(color.colorId) { mutableListOf() } += task
        }
    }
    return gathered
        .map { (colorId, held) -> PoolColorGroup(colors.getValue(colorId), held.sortedWith(byAttentionThenName)) }
        .sortedWith(compareBy({ it.color.sortOrder }, { it.color.colorId.toString() }))
}
