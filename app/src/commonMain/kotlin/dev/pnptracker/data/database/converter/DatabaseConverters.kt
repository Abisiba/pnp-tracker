package dev.pnptracker.data.database.converter

import androidx.room3.ColumnTypeConverter
import dev.pnptracker.domain.model.ColorRelation
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.ImportBatchStatus
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.domain.model.PoolType
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
    fun colorRelationToText(relation: ColorRelation): String = relation.name

    @ColumnTypeConverter
    fun textToColorRelation(name: String): ColorRelation = ColorRelation.valueOf(name)

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
    fun hintDecisionToText(decision: HintDecision): String = decision.name

    @ColumnTypeConverter
    fun textToHintDecision(name: String): HintDecision = HintDecision.valueOf(name)
}
