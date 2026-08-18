package dev.pnptracker.data.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.ImportSourceFormat
import kotlin.time.Instant

private val SHA_256 = Regex("[0-9a-f]{64}")

/**
 * One run of reading a spreadsheet or a CSV file.
 *
 * Only the file name and the fingerprint are kept, never the path the file was
 * read from: the path says where one machine happened to keep a personal file,
 * and it would end up in backups and exports.
 *
 * The row and column bounds describe the rectangle the reader recognised. They
 * are zero based and inclusive, so a sheet whose data sits in the first three
 * rows records `startRowIndex = 0` and `endRowIndex = 2`. Either all four bounds
 * are known or none of them are.
 *
 * Whether this file has been imported before is a question about the other rows
 * in this table, so it is answered by querying [sha256] rather than by a stored
 * flag that would go stale as soon as another import ran.
 */
@Entity(
    tableName = "import_batches",
    indices = [Index(value = ["sha256"]), Index(value = ["status"])],
)
data class ImportBatchEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: EntityId,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "sha256")
    val sha256: String,
    @ColumnInfo(name = "source_format")
    val sourceFormat: ImportSourceFormat,
    /** The worksheet this batch read; empty for a CSV file, which has no sheets. */
    @ColumnInfo(name = "sheet_name")
    val sheetName: String,
    @ColumnInfo(name = "start_row_index")
    val startRowIndex: Int? = null,
    @ColumnInfo(name = "end_row_index")
    val endRowIndex: Int? = null,
    @ColumnInfo(name = "start_column_index")
    val startColumnIndex: Int? = null,
    @ColumnInfo(name = "end_column_index")
    val endColumnIndex: Int? = null,
    @ColumnInfo(name = "created_game_count", defaultValue = "0")
    val createdGameCount: Int = 0,
    @ColumnInfo(name = "raw_block_count", defaultValue = "0")
    val rawBlockCount: Int = 0,
    @ColumnInfo(name = "created_task_count", defaultValue = "0")
    val createdTaskCount: Int = 0,
    @ColumnInfo(name = "status")
    val status: ImportBatchStatus = ImportBatchStatus.DRAFT,
    @ColumnInfo(name = "imported_at")
    val importedAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    init {
        require(fileName.isNotBlank()) { "An import needs the name of the file it read." }
        require(SHA_256.matches(sha256)) {
            "A fingerprint must be 64 lower case hex characters, was: '$sha256'"
        }
        require(createdGameCount >= 0 && rawBlockCount >= 0 && createdTaskCount >= 0) {
            "Import counts cannot be negative: games=$createdGameCount, blocks=$rawBlockCount, tasks=$createdTaskCount"
        }
        val bounds = listOf(startRowIndex, endRowIndex, startColumnIndex, endColumnIndex)
        require(bounds.all { it == null } || bounds.all { it != null }) {
            "The detected range is either fully known or fully unknown, was: $bounds"
        }
        if (startRowIndex != null && endRowIndex != null && startColumnIndex != null && endColumnIndex != null) {
            require(startRowIndex >= 0 && startColumnIndex >= 0) {
                "Row and column indexes are zero based, so they cannot be negative: " +
                    "rows $startRowIndex..$endRowIndex, columns $startColumnIndex..$endColumnIndex"
            }
            require(startRowIndex <= endRowIndex) {
                "The first row cannot come after the last one: $startRowIndex..$endRowIndex"
            }
            require(startColumnIndex <= endColumnIndex) {
                "The first column cannot come after the last one: $startColumnIndex..$endColumnIndex"
            }
        }
    }
}
