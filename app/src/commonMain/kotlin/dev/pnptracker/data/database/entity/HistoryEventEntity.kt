package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.ProductionStage
import kotlin.time.Instant

/**
 * One thing that happened to a task or to a game.
 *
 * Append only, like [ProgressEventEntity] and for the same reason: PLAN 5.12
 * makes a history something derived from what was recorded, and PLAN 12.15 asks
 * the history screen to show records that have since been deleted. Neither
 * survives a table whose rows can be edited or removed, so nothing in the
 * production API updates or deletes one.
 *
 * [gameId] is written at the moment of the event rather than looked up later,
 * and that is not a convenience. A task reaches its game through its piece of a
 * cell, and turning a task back into text takes that piece away — so a history
 * row written without the game would become a row nobody can place, on exactly
 * the event PLAN 12.15 asks the screen to show. The same is true of any future
 * rollback that unpicks an import.
 *
 * Both foreign keys are RESTRICT. A task or a game with history cannot be erased
 * out from under it, and no cascade takes the history along when one is deleted —
 * deletion here is a tombstone (PLAN 5.2), which leaves both rows in place, and
 * physical removal is the separate maintenance action in the same section. A cascade
 * written today for the sake of that action would throw the history away at
 * exactly the moment PLAN wants it kept.
 *
 * [stage], [previousQuantity] and [newQuantity] belong to
 * [HistoryEventKind.TASK_STAGE_QUANTITY_CHANGED] alone and are null everywhere
 * else. There is deliberately no free text column, no payload and no developer
 * message: a history the screen has to parse is not a history it can filter, and
 * a column that holds anything ends up holding the things nobody designed for.
 */
@Entity(
    tableName = "history_events",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        // The screen's own order: everything that has happened, newest first.
        Index(value = ["occurred_at"]),
        // One game's history, in order — and the index the RESTRICT on games
        // needs, since SQLite indexes no foreign key on its own and would
        // otherwise scan this table on every write to a game row.
        Index(value = ["game_id", "occurred_at"]),
        // The same for one task, which is how a task's own panel will read its
        // past and how the RESTRICT on tasks is answered.
        Index(value = ["task_id", "occurred_at"]),
    ],
)
data class HistoryEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "kind")
    val kind: HistoryEventKind,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant,
    /** The game this happened in, or the game this happened to. Never absent. */
    @ColumnInfo(name = "game_id")
    val gameId: EntityId,
    /** The task this happened to; null only when the game itself is the subject. */
    @ColumnInfo(name = "task_id")
    val taskId: EntityId? = null,
    /** Which step of the pipeline moved, for a stage event only. */
    @ColumnInfo(name = "stage")
    val stage: ProductionStage? = null,
    /** What that step stood at before, for a stage event only. */
    @ColumnInfo(name = "previous_quantity")
    val previousQuantity: Int? = null,
    /** What that step stands at now, for a stage event only. */
    @ColumnInfo(name = "new_quantity")
    val newQuantity: Int? = null,
) {
    init {
        if (kind.isAboutGameItself) {
            require(taskId == null) {
                "$kind is about the game itself, so it cannot also name a task ($taskId)."
            }
        } else {
            require(taskId != null) {
                "$kind is something that happened to a task, so it has to name one."
            }
        }
        if (kind.carriesStageQuantities) {
            require(stage != null && previousQuantity != null && newQuantity != null) {
                "$kind is a step moving between two counts, so it needs all three: " +
                    "stage=$stage, previous=$previousQuantity, new=$newQuantity"
            }
            require(previousQuantity >= 0 && newQuantity >= 0) {
                "A pipeline step cannot stand below nothing: $previousQuantity -> $newQuantity"
            }
            require(previousQuantity != newQuantity) {
                "A step that did not move is not an event: both counts were $newQuantity."
            }
        } else {
            require(stage == null && previousQuantity == null && newQuantity == null) {
                "$kind carries no pipeline detail, but was given " +
                    "stage=$stage, previous=$previousQuantity, new=$newQuantity"
            }
        }
    }
}
