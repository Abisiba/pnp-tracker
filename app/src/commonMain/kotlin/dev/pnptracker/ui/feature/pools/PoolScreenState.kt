package dev.pnptracker.ui.feature.pools

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.tasks.StageSnapshot
import dev.pnptracker.domain.tasks.TaskProgressFailure
import dev.pnptracker.ui.feature.games.TaskEditor

/** Where a pool is. */
sealed interface PoolContentState {
    data object Loading : PoolContentState

    data class Content(
        val model: PoolModel,
    ) : PoolContentState

    /**
     * The pool could not be read.
     *
     * Kept apart from an empty one, because they call for different things: an
     * empty pool is good news and a broken read is something to try again. What
     * broke is never shown — PLAN 17 keeps the developer's words off the screen.
     */
    data object Failed : PoolContentState
}

/**
 * Which card a task is drawn on.
 *
 * A multi-colour task appears once under each colour it is made in (PLAN 12.10),
 * and every one of those is the same task. So the task alone does not say which
 * card the user pressed, and the keyboard has to come back to the one they left.
 * The colour is what tells them apart; a card outside any colour group carries
 * none.
 */
data class PoolCardKey(
    val taskId: EntityId,
    val colorId: EntityId? = null,
)

/**
 * The one thing open over a pool.
 *
 * A sealed set rather than a flag for each, for the same reason the table has
 * one: the states exclude one another, and a pile of booleans would let two of
 * them be true at once.
 *
 * The card is carried through all of them, so closing any hands the keyboard
 * back to the card it was opened from rather than to the first card that happens
 * to name the same task.
 */
sealed interface PoolWork {
    val card: PoolCardKey
    val task: PoolTask

    /** What closing this goes back to, or null when it closes outright. */
    val parent: PoolWork?

    /** True when closing this would throw away something typed. */
    val hasUnsavedChanges: Boolean

    /** The menu over one task, offering what can be done to it. */
    data class Menu(
        override val card: PoolCardKey,
        override val task: PoolTask,
    ) : PoolWork {
        override val parent: PoolWork? get() = null
        override val hasUnsavedChanges: Boolean get() = false
    }

    /** The same panel the game table opens over a task, over the same task. */
    data class Editing(
        val from: Menu,
        val editor: TaskEditor,
    ) : PoolWork {
        override val card: PoolCardKey get() = from.card
        override val task: PoolTask get() = from.task
        override val parent: PoolWork get() = from
        override val hasUnsavedChanges: Boolean get() = editor.hasChanges
    }

    /** Asking whether the task really should go back to being words. */
    data class ConfirmingConvert(
        val from: Menu,
        val hasProgress: Boolean,
        val isSaving: Boolean = false,
    ) : PoolWork {
        override val card: PoolCardKey get() = from.card
        override val task: PoolTask get() = from.task
        override val parent: PoolWork get() = from
        override val hasUnsavedChanges: Boolean get() = false
    }

    /**
     * The pipeline of one card or board task, open to be changed (PLAN 7.3).
     *
     * The whole pipeline rather than one step, because that is how PLAN 7.3 puts
     * it in front of the user, and because a target the user describes as a whole
     * may pass through states the ordering rule forbids on the way to being
     * typed. Nothing is written until it is saved.
     *
     * This one has no menu behind it. The badge is its own control on the card,
     * so closing it goes back to the card rather than into a menu the user never
     * opened.
     */
    data class EditingStages(
        override val card: PoolCardKey,
        override val task: PoolTask,
        /**
         * What the pipeline and the total said when this was opened.
         *
         * Sent back with the save so a panel left open while the work moved on
         * is refused rather than allowed to put back what it was opened with.
         * It is also what the panel itself counts against: the boxes, the arrows
         * and the `/ N` beside them all describe the picture the user is typing
         * into, and quietly re-pointing them at a total that arrived afterwards
         * would change what their half-typed target meant without telling them.
         */
        val expected: StageSnapshot,
        /** What the user has typed for each step, as typed. */
        val draft: Map<ProductionStage, String>,
        val isSaving: Boolean = false,
        val failure: TaskProgressFailure? = null,
        /** The step the refusal is about, so the keyboard can be sent to it. */
        val invalidStage: ProductionStage? = null,
    ) : PoolWork {
        override val parent: PoolWork? get() = null
        override val hasUnsavedChanges: Boolean
            get() = draft.any { (stage, typed) -> typed != expected.stages[stage]?.toString() }

        /** What every step counts up to, from the moment this was opened. */
        val total: Int? get() = expected.requiredQuantity

        /** The steps in the order they are worked in, as the card must draw them. */
        val steps: List<ProductionStage> get() = task.stages.map { it.stage }

        /** What each step would stand at, or null when one of them is not a count. */
        val targets: Map<ProductionStage, Int>?
            get() =
                steps
                    .associateWith { stage ->
                        draft[stage]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
                            ?: return null
                    }

        /**
         * The first step whose box does not hold a usable count, for the keyboard.
         *
         * Empty and not-a-number are both here, and so is a number too large to
         * be one: a run of digits past what a count can hold is no more usable
         * than a word, and leaving it out would send the keyboard nowhere on the
         * one refusal the user is least likely to have expected.
         */
        val firstUnusableStage: ProductionStage?
            get() =
                steps.firstOrNull { stage ->
                    val typed = draft[stage]
                    typed.isNullOrEmpty() || !typed.all(Char::isDigit) || typed.toIntOrNull() == null
                }

        /** Whether one box holds something that is not a count this step could stand at. */
        fun isUnusable(stage: ProductionStage): Boolean {
            val typed = draft[stage] ?: return false
            if (typed.isEmpty()) return false
            return !typed.all(Char::isDigit) || typed.toIntOrNull() == null
        }

        /**
         * Whether the draft as it stands describes a pipeline that cannot have
         * happened, a later step standing further on than an earlier one.
         *
         * Said while it is being typed rather than only when the save comes
         * back, because the arrows are allowed to pass through it: reaching
         * `10/10/5` from `8/10/5` means moving the first step twice, and the
         * halfway point is a state the finished target is not.
         */
        val isOutOfOrder: Boolean
            get() =
                steps.zipWithNext().any { (earlier, later) ->
                    val before = draft[earlier]?.toIntOrNull() ?: return@any false
                    val after = draft[later]?.toIntOrNull() ?: return@any false
                    after > before
                }
    }
}

/**
 * What one pool screen is showing and what is open over it.
 *
 * The open work is held apart from the pool's own rows, so a fresh list arriving
 * from the database replaces the rows and leaves what the user is halfway
 * through exactly where it was.
 */
data class PoolScreenState(
    val poolType: PoolType,
    val content: PoolContentState = PoolContentState.Loading,
    val work: PoolWork? = null,
    /**
     * Which tasks have their stage list open.
     *
     * PLAN 12.11 lets the badge be expanded to see the counters, and this step
     * shows them without offering to change one. Held here rather than inside
     * the card so that a new list from the database cannot fold something the
     * user opened.
     */
    val expandedStages: Set<EntityId> = emptySet(),
    /** Bumped whenever the keyboard has to be handed back somewhere. */
    val focusRecall: Int = 0,
) {
    val isSaving: Boolean
        get() =
            when (val open = work) {
                is PoolWork.Editing -> open.editor.isSaving
                is PoolWork.ConfirmingConvert -> open.isSaving
                is PoolWork.EditingStages -> open.isSaving
                else -> false
            }

    /** The card the keyboard goes back to when what is open closes. */
    val focusCard: PoolCardKey? get() = work?.card
}
