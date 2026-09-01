package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.stagesOf
import kotlin.time.Instant

/**
 * How far one step of a card or board task has got.
 *
 * A task's stages are written when the task is, all of them together, so a
 * pipeline is never half described. The set is fixed by the pool — PLAN 7.2 and
 * 8 — and the primary key keeps a task from carrying the same stage twice.
 *
 * [orderIndex] is the position in that pool's pipeline, stored rather than
 * derived so a query can order by it without knowing the enum. It is what the
 * rule `0 <= cut <= laminated <= printed <= total` is checked along: a stage may
 * never be counted further than the one before it.
 *
 * There is no "done" flag. A stage is done when its count reaches the task's
 * total, which is one fact rather than two that could disagree.
 */
@Entity(
    tableName = "task_stages",
    primaryKeys = ["task_id", "stage"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["task_id", "order_index"], unique = true),
    ],
)
data class TaskStageEntity(
    @ColumnInfo(name = "task_id")
    val taskId: EntityId,
    @ColumnInfo(name = "stage")
    val stage: ProductionStage,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    /** How many pieces have been through this step. */
    @ColumnInfo(name = "completed_quantity", defaultValue = "0")
    val completedQuantity: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    init {
        require(orderIndex >= 0) { "A stage cannot come before the start of its pipeline: $orderIndex" }
        require(completedQuantity >= 0) {
            "A stage cannot have made less than nothing, was: $completedQuantity"
        }
    }
}

/**
 * The stage rows a task of this pool starts life with.
 *
 * A plain function over [stagesOf] rather than a step any writer performs, so
 * the two places that create tasks — one by hand, one from an import — shape a
 * pipeline the same way without either reaching into the other's transaction.
 * Each caller inserts these rows inside its own write, which is what keeps a
 * task and its pipeline atomic.
 *
 * [completedQuantity] is nothing done for an ordinary new task. A task that is
 * born finished — an import carrying a `**` the user agreed to — starts with its
 * whole total, because PLAN 6.4 will not have a finished task whose pipeline
 * disagrees, and the rule is the same one [dev.pnptracker.domain.tasks.CompletionRules]
 * gives for finishing a task that already existed.
 *
 * A pool with no pipeline returns nothing, so a caller needs no special case.
 */
fun stageRowsFor(
    taskId: EntityId,
    poolType: PoolType,
    moment: Instant,
    completedQuantity: Int = 0,
): List<TaskStageEntity> =
    stagesOf(poolType).mapIndexed { index, stage ->
        TaskStageEntity(
            taskId = taskId,
            stage = stage,
            orderIndex = index,
            completedQuantity = completedQuantity,
            createdAt = moment,
            updatedAt = moment,
        )
    }
