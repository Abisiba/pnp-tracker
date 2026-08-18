package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.SourceColumnType
import kotlin.time.Instant

/**
 * One filled cell of the source file, kept exactly as it was written.
 *
 * [rawText] is never trimmed, normalised or stripped of `**` markers: leading and
 * trailing spaces, line breaks and Turkish characters all survive, because this
 * row is the evidence of what the user actually wrote. Everything the application
 * derives from it lives elsewhere.
 *
 * [fillColorArgb] is only the cell's formatting. It is not the truth about a
 * color or about a game being finished; a green cell becomes nothing more than a
 * [HintDecision.PENDING] hint for the user to decide on.
 *
 * Row and column indexes are zero based, matching [ImportBatchEntity].
 */
@Entity(
    tableName = "raw_import_blocks",
    foreignKeys = [
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_batch_id"],
            // Discarding a draft batch takes its untouched raw cells with it; a
            // batch that produced real records is held back by the games and
            // tasks that point at it.
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["import_batch_id"]),
        Index(value = ["is_processed"]),
        Index(
            value = ["import_batch_id", "sheet_name", "row_index", "column_index"],
            unique = true,
        ),
    ],
)
data class RawImportBlockEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "import_batch_id")
    val importBatchId: EntityId,
    @ColumnInfo(name = "raw_text")
    val rawText: String,
    /** The worksheet the cell came from; empty for a CSV file, which has no sheets. */
    @ColumnInfo(name = "sheet_name")
    val sheetName: String,
    @ColumnInfo(name = "row_index")
    val rowIndex: Int,
    @ColumnInfo(name = "column_index")
    val columnIndex: Int,
    @ColumnInfo(name = "source_column_type")
    val sourceColumnType: SourceColumnType,
    /** The cell's fill colour as packed ARGB, or null when the format is unknown. */
    @ColumnInfo(name = "fill_color_argb")
    val fillColorArgb: Int? = null,
    @ColumnInfo(name = "game_completion_hint", defaultValue = "'NONE'")
    val gameCompletionHint: HintDecision = HintDecision.NONE,
    @ColumnInfo(name = "is_processed", defaultValue = "0")
    val isProcessed: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    init {
        require(rowIndex >= 0 && columnIndex >= 0) {
            "Cell coordinates are zero based, so they cannot be negative: row $rowIndex, column $columnIndex"
        }
    }
}
