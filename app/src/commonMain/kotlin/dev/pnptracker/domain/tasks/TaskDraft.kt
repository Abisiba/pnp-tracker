package dev.pnptracker.domain.tasks

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.TrackingMode

/**
 * One task the user has described but not yet created.
 *
 * The name is deliberately not in here. A batch is made out of one stretch of
 * the user's own text and every task in it starts from that same stretch, so the
 * name belongs to the selection rather than to any one row of the form — and
 * putting it in each row would invite the reader to think the rows could differ
 * in it, which is a rename, not a creation.
 *
 * That shared start is the whole extent of what the rows have in common. PLAN
 * 12.7 makes the tasks they create fully independent: separate identities,
 * quantities, notes, counters, completion and pool membership, with no group,
 * parent or shared record between them. Once they exist, nothing in the database
 * says they were made together, and nothing needs to.
 *
 * A draft carries a **list** of colours because PLAN 5.10 lets one task be made
 * in several. That is one task with one total and one counter (PLAN 12.7), not
 * several tasks sharing anything: the way to describe several is several drafts.
 * So the shape of a draft is also the difference between the two — a list of
 * colours in one draft is one task, and a colour each in three drafts is three.
 */
data class TaskDraft(
    /**
     * The colours the task is made in, in the order the user chose them.
     *
     * Empty for work that has no colour: PLAN 5.10 gives colours to three
     * dimensional printing alone, so a card, a board piece or a special task is
     * described without any. Which pools may carry one is not this type's to
     * know — the transaction that has the cell in front of it decides, and
     * refuses a colour that does not belong to the pool being written to.
     */
    val colorIds: List<EntityId>,
    val requiredQuantity: Int,
    val trackingMode: TrackingMode,
    /** The user's own words, kept exactly, or null when they wrote none. */
    val notes: String?,
)
