package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import dev.pnptracker.domain.model.EntityId

/**
 * What one cell said before an import was allowed to write into it.
 *
 * Confirming an import only ever appends to a cell (PLAN 11.4.2), so taking the
 * import back means putting the cell back to the words it held beforehand. PLAN
 * 11.4.4 will not have that worked out afterwards from what is left: the user
 * may have written in the cell since, and subtracting task names from today's
 * document would quietly throw away whatever they added. So the confirmation
 * stores the document it actually read, and the rollback compares against it.
 *
 * [documentBefore] is the cell as it **reads** — its pieces laid end to end,
 * each task piece contributing the name of its task (PLAN 5.5) — not a copy of
 * its rows. That is deliberate. A row identity is not durable: editing a cell
 * merges neighbouring runs of plain text into the first one's identity, so a
 * provenance tag on a piece would either vanish on the first edit or end up
 * carrying the user's own words. PLAN 11.4.4 therefore binds the record to the
 * stored text rather than to any row, and `cell_segments` gains no import
 * column.
 *
 * An empty cell is stored as the empty string rather than left out. The absence
 * of a row means something else and must go on meaning it: that this import
 * never aimed at that cell — or, for a batch confirmed before this table
 * existed, that nothing was recorded and PLAN 11.4.4 refuses the rollback rather
 * than guessing.
 *
 * One row per (batch, cell). Several drafts of one import can aim at the same
 * cell, and they all share the one document that was there before any of them.
 */
@Entity(
    tableName = "import_batch_cells",
    primaryKeys = ["import_batch_id", "cell_id"],
    foreignKeys = [
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_batch_id"],
            // Discarding a draft takes its rows with it, exactly as it does the
            // raw cells. A batch that reaches this table has been confirmed and
            // is not deletable, so the cascade is a shape rather than a path.
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = GameCellEntity::class,
            parentColumns = ["id"],
            childColumns = ["cell_id"],
            // The record of what a cell held may not be erased by something
            // passing the cell on its way out.
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["cell_id"])],
)
data class ImportBatchCellEntity(
    @ColumnInfo(name = "import_batch_id")
    val importBatchId: EntityId,
    @ColumnInfo(name = "cell_id")
    val cellId: EntityId,
    /** The whole of what the cell read at the moment the import was confirmed. */
    @ColumnInfo(name = "document_before")
    val documentBefore: String,
)
