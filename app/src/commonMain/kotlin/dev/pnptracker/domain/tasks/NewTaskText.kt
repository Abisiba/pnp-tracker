package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId

/**
 * A name the user has just typed into a cell, not saved yet, to be made a task (PLAN 12.6).
 *
 * The cell's editor still holds the words: [expectedDocument] is what the cell
 * said when the editor opened, [newDocument] is what it says now, and the name is
 * [newDocument] from [startOffset] to [endOffset]. Nothing is written until the
 * task is saved, and then the text and the task are written together.
 */
data class NewTaskText(
    val gameId: EntityId,
    val columnType: CellColumnType,
    val expectedDocument: String,
    val newDocument: String,
    val startOffset: Int,
    val endOffset: Int,
) {
    init {
        require(startOffset in 0 until endOffset && endOffset <= newDocument.length) {
            "The name has to be somewhere in the new text: $startOffset..$endOffset of ${newDocument.length}"
        }
    }

    /** The name the task will carry. */
    val name: String get() = newDocument.substring(startOffset, endOffset)

    companion object {
        /**
         * The name typed into [draft] since it was [original], or null when there is none.
         *
         * Only a plain insertion counts: the draft is the original with one stretch
         * of new words put somewhere in it, and nothing taken away. The words are
         * the new name without the spaces and line breaks around them. Anything
         * else — nothing typed, only spaces, something deleted, or a name running
         * over several lines — is not a new name, and the editor goes on as before.
         */
        fun typedInto(
            gameId: EntityId,
            columnType: CellColumnType,
            original: String,
            draft: String,
        ): NewTaskText? {
            if (draft.length <= original.length) return null
            var prefix = 0
            while (prefix < original.length && original[prefix] == draft[prefix]) prefix++
            var suffix = 0
            while (suffix < original.length - prefix && original[original.length - 1 - suffix] == draft[draft.length - 1 - suffix]) {
                suffix++
            }
            if (prefix + suffix != original.length) return null
            var start = prefix
            var end = draft.length - suffix
            while (start < end && draft[start].isWhitespace()) start++
            while (end > start && draft[end - 1].isWhitespace()) end--
            if (start == end) return null
            if (draft.substring(start, end).any { it == '\n' || it == '\r' }) return null
            return NewTaskText(gameId, columnType, original, draft, start, end)
        }
    }
}

/**
 * Makes a name the user has only just typed into a task, text and task at once.
 *
 * The one call behind a task window opened with Enter (PLAN 12.6): the cell's
 * new text is saved and the new name becomes the task in the same transaction,
 * so either both are written or neither is.
 */
interface TaskCreationFromNewText {
    /**
     * @return the identities of the tasks that were created, in the order of [drafts].
     * @throws TaskFromTextException for a refusal the user can act on; nothing is
     *   written, the text included.
     */
    suspend fun createTasksInNewText(
        text: NewTaskText,
        drafts: List<TaskDraft>,
    ): List<EntityId>

    companion object {
        /** For a screen built without the database behind it: every save is refused. */
        val Unavailable: TaskCreationFromNewText =
            object : TaskCreationFromNewText {
                override suspend fun createTasksInNewText(
                    text: NewTaskText,
                    drafts: List<TaskDraft>,
                ): List<EntityId> = throw TaskFromTextException(TaskFromTextFailure.COULD_NOT_SAVE)
            }
    }
}
