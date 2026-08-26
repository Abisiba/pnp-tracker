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
 */
data class TaskDraft(
    val colorId: EntityId,
    val requiredQuantity: Int,
    val trackingMode: TrackingMode,
    /** The user's own words, kept exactly, or null when they wrote none. */
    val notes: String?,
)
