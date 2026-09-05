package dev.pnptracker.data.database.converter

import androidx.room3.ColumnTypeConverter
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.HistoryEventKind
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.ProgressEventKind
import dev.pnptracker.domain.model.SegmentKind
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.model.TrackingMode
import kotlin.time.Instant

/**
 * Translates the domain value types into the two column types SQLite stores them
 * in. Keeping the annotations here rather than on the domain types leaves
 * [EntityId] and the timestamps free of any persistence concern.
 *
 * Timestamps are stored as epoch milliseconds, so persisted time has millisecond
 * precision; anything finer that an [Instant] carries is lost on the way to disk.
 *
 * Enums are stored under their declared names rather than their ordinals, so
 * reordering a declaration cannot silently reinterpret existing rows.
 */
object DatabaseConverters {
    @ColumnTypeConverter
    fun entityIdToText(id: EntityId): String = id.toString()

    @ColumnTypeConverter
    fun textToEntityId(text: String): EntityId = EntityId.parse(text)

    @ColumnTypeConverter
    fun instantToEpochMilliseconds(instant: Instant): Long = instant.toEpochMilliseconds()

    @ColumnTypeConverter
    fun epochMillisecondsToInstant(epochMilliseconds: Long): Instant = Instant.fromEpochMilliseconds(epochMilliseconds)

    @ColumnTypeConverter
    fun poolTypeToText(poolType: PoolType): String = poolType.name

    @ColumnTypeConverter
    fun textToPoolType(name: String): PoolType = PoolType.valueOf(name)

    @ColumnTypeConverter
    fun trackingModeToText(trackingMode: TrackingMode): String = trackingMode.name

    @ColumnTypeConverter
    fun textToTrackingMode(name: String): TrackingMode = TrackingMode.valueOf(name)

    @ColumnTypeConverter
    fun cellColumnTypeToText(columnType: CellColumnType): String = columnType.name

    @ColumnTypeConverter
    fun textToCellColumnType(name: String): CellColumnType = CellColumnType.valueOf(name)

    @ColumnTypeConverter
    fun segmentKindToText(kind: SegmentKind): String = kind.name

    @ColumnTypeConverter
    fun textToSegmentKind(name: String): SegmentKind = SegmentKind.valueOf(name)

    @ColumnTypeConverter
    fun importBatchStatusToText(status: ImportBatchStatus): String = status.name

    @ColumnTypeConverter
    fun textToImportBatchStatus(name: String): ImportBatchStatus = ImportBatchStatus.valueOf(name)

    @ColumnTypeConverter
    fun importSourceFormatToText(format: ImportSourceFormat): String = format.name

    @ColumnTypeConverter
    fun textToImportSourceFormat(name: String): ImportSourceFormat = ImportSourceFormat.valueOf(name)

    @ColumnTypeConverter
    fun sourceColumnTypeToText(sourceColumnType: SourceColumnType): String = sourceColumnType.name

    @ColumnTypeConverter
    fun textToSourceColumnType(name: String): SourceColumnType = SourceColumnType.valueOf(name)

    @ColumnTypeConverter
    fun productionStageToText(stage: ProductionStage): String = stage.name

    @ColumnTypeConverter
    fun textToProductionStage(name: String): ProductionStage = ProductionStage.valueOf(name)

    @ColumnTypeConverter
    fun progressEventKindToText(kind: ProgressEventKind): String = kind.name

    @ColumnTypeConverter
    fun textToProgressEventKind(name: String): ProgressEventKind = ProgressEventKind.valueOf(name)

    @ColumnTypeConverter
    fun historyEventKindToText(kind: HistoryEventKind): String = kind.name

    @ColumnTypeConverter
    fun textToHistoryEventKind(name: String): HistoryEventKind = HistoryEventKind.valueOf(name)

    @ColumnTypeConverter
    fun hintDecisionToText(decision: HintDecision): String = decision.name

    @ColumnTypeConverter
    fun textToHintDecision(name: String): HintDecision = HintDecision.valueOf(name)
}
