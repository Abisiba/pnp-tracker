package dev.pnptracker.domain.diagnostics

import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupEnvelopeV1
import dev.pnptracker.domain.backup.restore.BackupPlace
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * How bad one recorded event is, and there are only three answers (PLAN 14.7.1).
 */
enum class DiagnosticLevel {
    /** A rare event that changed all of the user's data and went through. */
    INFO,

    /** Something was refused or left half done; the user's data is not at risk. */
    WARN,

    /** What the user asked for did not happen because of storage, files or a defect. */
    ERROR,
}

/**
 * The closed list of things a diagnostic line may say happened (PLAN 14.7.2).
 *
 * The code is what goes into the file. It is fixed here, in the source, so a
 * line can only ever name an event somebody wrote down on purpose.
 */
enum class DiagnosticEvent(
    val code: String,
    val level: DiagnosticLevel,
) {
    STARTUP_REFUSED("startup.refused", DiagnosticLevel.ERROR),
    MIGRATION_COMPLETED("startup.migration_completed", DiagnosticLevel.INFO),
    SETTINGS_READ_PROBLEM("settings.read_problem", DiagnosticLevel.WARN),
    SETTINGS_WRITE_FAILED("settings.write_failed", DiagnosticLevel.WARN),
    STORAGE_READ_FAILED("storage.read_failed", DiagnosticLevel.ERROR),
    STORAGE_WRITE_FAILED("storage.write_failed", DiagnosticLevel.ERROR),
    IMPORT_FILE_UNREADABLE("import.file_unreadable", DiagnosticLevel.WARN),
    IMPORT_SNAPSHOT_FAILED("import.snapshot_failed", DiagnosticLevel.ERROR),
    IMPORT_CHANGED_MEANWHILE("import.changed_meanwhile", DiagnosticLevel.WARN),
    IMPORT_RECORDS_CONTRADICT("import.records_contradict", DiagnosticLevel.WARN),
    IMPORT_ROLLBACK_PROVENANCE_BROKEN("import.rollback_provenance_broken", DiagnosticLevel.WARN),
    IMPORT_DRAFT_HELD_BY_RECORDS("import.draft_held_by_records", DiagnosticLevel.WARN),
    BACKUP_WRITE_FAILED("backup.write_failed", DiagnosticLevel.WARN),
    BACKUP_ROTATION_INCOMPLETE("backup.rotation_incomplete", DiagnosticLevel.WARN),
    RESTORE_FILE_REFUSED("restore.file_refused", DiagnosticLevel.WARN),
    RESTORE_NOT_COMPLETED("restore.not_completed", DiagnosticLevel.ERROR),
    RESTORE_COMPLETED("restore.completed", DiagnosticLevel.INFO),
    EXPORT_WRITE_FAILED("export.write_failed", DiagnosticLevel.WARN),
    EXPORT_BROKEN_DATA("export.broken_data", DiagnosticLevel.ERROR),
    UNEXPECTED_FAILURE("app.unexpected_failure", DiagnosticLevel.ERROR),
    RECORDS_DROPPED("diagnostics.records_dropped", DiagnosticLevel.WARN),
}

/**
 * The part of the application an event happened in: a fixed name, never a
 * screen title and never anything out of the user's data (PLAN 14.7.2).
 */
enum class DiagnosticArea {
    APPLICATION,
    STARTUP,
    SETTINGS,
    IMPORT_FILE,
    IMPORT_DRAFT,
    IMPORT_REVIEW,
    IMPORT_CONFIRMATION,
    IMPORT_ROLLBACK,
    DRAFT_REMOVAL,
    UNFINISHED_IMPORTS,
    SETTLED_IMPORTS,
    GAME_TABLE,
    GAME_SETUP,
    CELL_TEXT,
    TASK_FROM_TEXT,
    TASK_EDIT,
    TASK_SETUP,
    TASK_PROGRESS,
    COLORS,
    POOLS,
    HISTORY,
    EXPORT,
    BACKUP,
    RESTORE,
    IMPORT_SNAPSHOTS,
    SAFETY_BACKUPS,
    MIGRATION_SETS,
}

/**
 * The name of an exception's class, and nothing else about it.
 *
 * A class name is decided when the code is compiled; nothing a person types,
 * imports or chooses can become one. The message and the stack trace are where
 * paths, identifiers and people's words live, so they are never looked at: this
 * reads the class and throws the exception away (PLAN 14.7.1).
 */
class ExceptionClassName private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is ExceptionClassName && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        /** What is written for a class that has no usable name, such as a lambda. */
        const val UNNAMED: String = "?"

        private val SAFE_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
        private const val LONGEST_NAME = 200
        private const val LONGEST_CHAIN = 16

        /** The class of [failure] itself. */
        fun of(failure: Throwable): ExceptionClassName = ExceptionClassName(safeNameOf(failure))

        /**
         * The class at the far end of [failure]'s causes, or null when it has none.
         *
         * At most sixteen steps, and a cause that points back at one already seen
         * ends the walk instead of going round for ever.
         */
        fun ofRootCause(failure: Throwable): ExceptionClassName? {
            val seen = mutableListOf(failure)
            var current = failure
            for (step in 1..LONGEST_CHAIN) {
                val next = current.cause ?: break
                if (seen.any { it === next }) break
                seen += next
                current = next
            }
            return if (current === failure) null else ExceptionClassName(safeNameOf(current))
        }

        private fun safeNameOf(failure: Throwable): String {
            val name = failure::class.qualifiedName ?: return UNNAMED
            return if (name.length <= LONGEST_NAME && SAFE_NAME.matches(name)) name else UNNAMED
        }
    }
}

/**
 * Where in the backup format a refusal was, in the format's own words.
 *
 * Only an array the format declares — or `envelope`, `file`, `data` — and only a
 * field that array's record type declares, are accepted. Anything else yields no
 * place at all, so a caller who hands over a value out of a file writes nothing.
 */
class DiagnosticPlace private constructor(
    val part: String,
    val field: String?,
) {
    override fun equals(other: Any?): Boolean = other is DiagnosticPlace && other.part == part && other.field == field

    override fun hashCode(): Int = part.hashCode() * 31 + field.hashCode()

    override fun toString(): String = if (field == null) part else "$part.$field"

    companion object {
        private val vocabulary: Map<String, Set<String>> by lazy { formatVocabulary() }

        /** The place, or null when either half is not a word the format itself uses. */
        fun of(place: BackupPlace): DiagnosticPlace? {
            val fields = vocabulary[place.part] ?: return null
            if (place.field != null && place.field !in fields) return null
            return DiagnosticPlace(place.part, place.field)
        }

        private fun formatVocabulary(): Map<String, Set<String>> {
            val data = BackupData.serializer().descriptor
            val arrays =
                (0 until data.elementsCount).associate { index ->
                    val row = data.getElementDescriptor(index).getElementDescriptor(0)
                    data.getElementName(index) to namesOf(row)
                }
            return arrays +
                mapOf(
                    BackupPlace.Envelope.part to namesOf(BackupEnvelopeV1.serializer().descriptor),
                    BackupPlace.File.part to emptySet(),
                    DATA_PART to emptySet(),
                )
        }

        private fun namesOf(descriptor: SerialDescriptor): Set<String> =
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()

        private const val DATA_PART = "data"
    }
}

/**
 * One thing worth writing down, built only from fixed names and numbers.
 *
 * There is no text parameter anywhere in this type, on purpose. A task name, a
 * file name, a path, an identifier, SQL or an exception's message cannot be
 * handed to it, because nothing here takes a string: the event and the area are
 * enums of this package, the reason is any enum constant (whose name the
 * compiler fixed), the place is checked against the backup format's own
 * vocabulary, and a failure is reduced to its class names the moment it arrives
 * and then let go (PLAN 14.7.1).
 */
class DiagnosticRecord(
    val event: DiagnosticEvent,
    val level: DiagnosticLevel = event.level,
    reason: Enum<*>? = null,
    val area: DiagnosticArea? = null,
    place: BackupPlace? = null,
    val fromSchema: Int? = null,
    val toSchema: Int? = null,
    val count: Long? = null,
    failure: Throwable? = null,
) {
    /** The name of the reason's constant, never anything computed at run time. */
    val reason: String? = reason?.name

    val place: DiagnosticPlace? = place?.let(DiagnosticPlace::of)

    val exception: ExceptionClassName? = failure?.let(ExceptionClassName::of)

    val cause: ExceptionClassName? = failure?.let(ExceptionClassName::ofRootCause)
}

/**
 * Where the application hands what went wrong.
 *
 * Recording never waits for a disk and never throws: a diagnostic that could not
 * be written changes nothing about what the user asked for (PLAN 16).
 */
fun interface Diagnostics {
    fun record(record: DiagnosticRecord)

    companion object {
        /** Records nothing; for code that has nowhere to write. */
        val None: Diagnostics = Diagnostics { }
    }
}
