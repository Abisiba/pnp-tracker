package dev.pnptracker.data.database.converter

import androidx.room3.ColumnTypeConverter
import dev.pnptracker.domain.model.EntityId
import kotlin.time.Instant

/**
 * Translates the domain value types into the two column types SQLite stores them
 * in. Keeping the annotations here rather than on the domain types leaves
 * [EntityId] and the timestamps free of any persistence concern.
 *
 * Timestamps are stored as epoch milliseconds, so persisted time has millisecond
 * precision; anything finer that an [Instant] carries is lost on the way to disk.
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
}
