package dev.pnptracker.domain.model

/** How far an import has got. */
enum class ImportBatchStatus {
    /** Still being reviewed; nothing has been written to the production tables. */
    DRAFT,

    /** The user accepted the import and it produced real records. */
    CONFIRMED,

    /** A confirmed import was taken back again. */
    ROLLED_BACK,
}
