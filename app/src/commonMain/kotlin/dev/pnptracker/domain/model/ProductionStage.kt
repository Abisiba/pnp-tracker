package dev.pnptracker.domain.model

/**
 * A step a card or board task passes through.
 *
 * PLAN 7.2 and 8 give the two pipelines three steps each, and the two share
 * everything but their middle one: a card is laminated where a board piece is
 * glued. They are one enum with one template per pool rather than two parallel
 * sets, so the counters, the ordering rule and the queries are written once.
 *
 * Three dimensional and special tasks have no pipeline at all. PLAN 6 counts a
 * 3D print as one batch plus whatever failed, and PLAN 9 leaves special work as
 * a checklist or a plain count, so neither ever carries a stage row.
 */
enum class ProductionStage {
    /** Both pipelines start here. */
    PRINT,

    /** Cards only. */
    LAMINATE,

    /** Board pieces only. */
    GLUE,

    /** Both pipelines end here. */
    CUT,
}

/**
 * The stages a task of this pool runs through, in the order they are worked in.
 *
 * The order is the whole of the rule PLAN 7.2 writes as
 * `0 <= cut <= laminated <= printed <= total`: a stage may never be further
 * along than the one before it. Returning a list rather than a set is what lets
 * that be checked by position instead of by naming pairs of stages.
 *
 * A pool with no pipeline returns nothing, which is how a caller finds out that
 * a stage row would be meaningless rather than by being told a special case.
 */
fun stagesOf(poolType: PoolType): List<ProductionStage> =
    when (poolType) {
        PoolType.CARD -> listOf(ProductionStage.PRINT, ProductionStage.LAMINATE, ProductionStage.CUT)
        PoolType.BOARD -> listOf(ProductionStage.PRINT, ProductionStage.GLUE, ProductionStage.CUT)
        PoolType.THREE_D, PoolType.SPECIAL -> emptyList()
    }

/** True when a task of this pool is worked through stages at all. */
val PoolType.hasStages: Boolean get() = stagesOf(this).isNotEmpty()
