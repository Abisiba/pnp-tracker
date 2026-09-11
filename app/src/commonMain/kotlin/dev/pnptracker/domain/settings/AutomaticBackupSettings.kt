package dev.pnptracker.domain.settings

import dev.pnptracker.domain.backup.retention.DEFAULT_AUTOMATIC_BACKUPS
import dev.pnptracker.domain.backup.retention.MAXIMUM_AUTOMATIC_BACKUPS
import dev.pnptracker.domain.backup.retention.MINIMUM_AUTOMATIC_BACKUPS
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The version of the settings document, which is not the backup's (PLAN 14.4.12). */
const val SETTINGS_FORMAT_VERSION: Int = 1

/**
 * The settings file, exactly as it is written and read.
 *
 * One field beside the version, because PLAN 14.4.12 adds one setting and says
 * so. A second field here would be a decision nobody has made.
 */
@Serializable
internal data class SettingsDocumentV1(
    val formatVersion: Int,
    val automaticBackupCount: Int,
)

/**
 * The one reader and writer of the settings document.
 *
 * `ignoreUnknownKeys` is the deliberate opposite of the backup's setting, and
 * PLAN 14.4.12 gives the reason: a backup carries the user's data, so quietly
 * dropping a field there loses some of it — while this file carries no data at
 * all, and being strict about it would leave somebody who had opened a newer
 * build unable to start an older one. Compact, like the backup's, so what is
 * written is the document the contract shows and not a prettier arrangement of
 * it.
 */
internal val settingsJson: Json =
    Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        isLenient = false
        allowComments = false
        allowTrailingComma = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

/**
 * Why the number in use is the default rather than the one in the file.
 *
 * Only ever about a file that exists and could not be used. A missing file is
 * not a problem and gets none of these: a fresh install has no settings and
 * nothing has gone wrong.
 *
 * Each of these leaves the file **exactly as it is**. PLAN 14.4.12 will not have
 * the application quietly rewrite something it could not understand — the file
 * may be the user's own edit, or a newer build's, and either way overwriting it
 * without being asked destroys the only copy of what it said.
 */
enum class SettingsProblem {
    /** The file is there and could not be read at all. */
    COULD_NOT_READ,

    /** What is in it is not the document this writes. */
    NOT_THE_EXPECTED_SHAPE,

    /** It names a version of the settings format this build does not know. */
    VERSION_NOT_SUPPORTED,

    /** It carries a number, and not one that may be kept (PLAN 14.4.12). */
    VALUE_OUT_OF_RANGE,
}

/**
 * What the settings say right now, and whether they came from the file.
 *
 * [automaticBackupCount] is always usable: it is either the file's value or the
 * default, so nothing above this has to decide what to do about a bad one.
 * [problem] is what the screen shows beside it, and it is null both when the
 * file was read and when there is no file yet.
 */
data class AutomaticBackupSettings(
    val automaticBackupCount: Int = DEFAULT_AUTOMATIC_BACKUPS,
    val problem: SettingsProblem? = null,
) {
    init {
        require(isKeepableCount(automaticBackupCount)) {
            "A settings value that reached this point should already have been replaced by the default"
        }
    }
}

/** Whether [count] is a number of automatic backups that may be kept. */
fun isKeepableCount(count: Int): Boolean = count in MINIMUM_AUTOMATIC_BACKUPS..MAXIMUM_AUTOMATIC_BACKUPS

/**
 * Why a setting could not be saved, in the terms a user can act on.
 *
 * The atomic writer tells five kinds of failure apart and this keeps two,
 * because a person changing a number does two different things about them: find
 * out why the configuration folder will not take a file, or simply try again.
 * The distinctions the writer makes matter to a backup, where the destination is
 * one the user chose; here it is always the same folder.
 */
enum class SettingsWriteFailure {
    /** The configuration folder cannot be written to. */
    NOT_WRITABLE,

    /** The write did not finish; whatever was there before is untouched. */
    COULD_NOT_WRITE,
}

/** Carries [failure] and, for a developer, the cause. Never shown to a user. */
class SettingsNotSaved(
    val failure: SettingsWriteFailure,
    cause: Throwable? = null,
) : Exception("The setting could not be saved: $failure", cause)

/**
 * Where the settings live, seen from the side that has no files.
 *
 * Deliberately not a "load once and keep" object. The file is small, it is read
 * when a screen opens and written when somebody presses save, and holding a
 * cached copy would only create a second answer able to disagree with the disk.
 */
interface SettingsStore {
    /**
     * The settings as they stand, falling back to the default rather than
     * failing. Reading never creates the file.
     */
    suspend fun read(): AutomaticBackupSettings

    /**
     * Writes [automaticBackupCount] as the whole document, atomically.
     *
     * This is the only thing that creates the settings file, and it happens only
     * because somebody asked for it. A read, a backup, a restore and a rotation
     * all leave a machine with no settings file exactly as they found it.
     *
     * @throws IllegalArgumentException if the count is not one that may be kept.
     *   That is a defect rather than an outcome: the screen refuses such a value
     *   long before here.
     * @throws SettingsNotSaved if it could not be written. The file that was
     *   there before is byte for byte unchanged when this throws.
     */
    suspend fun write(automaticBackupCount: Int)
}

/**
 * Reads [text] as a settings document, and says what to use.
 *
 * Kept apart from any file so the whole of the decision can be tested without
 * one, and so the platform side has nothing to decide. Every unusable file ends
 * up at the same place — the default, with a reason — because there is exactly
 * one safe thing to do with a settings file that cannot be understood, and that
 * is to leave it alone and carry on.
 */
fun settingsIn(text: String): AutomaticBackupSettings {
    val document =
        try {
            settingsJson.decodeFromString<SettingsDocumentV1>(text)
        } catch (notOurDocument: Exception) {
            // Deliberately broad, and deliberately only here. Deserialisation
            // answers with several unrelated exception types for one question —
            // "is this our document" — and the answer to all of them is the
            // same. Nothing else in this file catches anything.
            return AutomaticBackupSettings(problem = SettingsProblem.NOT_THE_EXPECTED_SHAPE)
        }
    if (document.formatVersion != SETTINGS_FORMAT_VERSION) {
        return AutomaticBackupSettings(problem = SettingsProblem.VERSION_NOT_SUPPORTED)
    }
    if (!isKeepableCount(document.automaticBackupCount)) {
        return AutomaticBackupSettings(problem = SettingsProblem.VALUE_OUT_OF_RANGE)
    }
    return AutomaticBackupSettings(automaticBackupCount = document.automaticBackupCount)
}

/** The document this writes for [automaticBackupCount], as the file will hold it. */
fun settingsDocumentFor(automaticBackupCount: Int): String {
    require(isKeepableCount(automaticBackupCount)) { "A count outside the allowed range is never written" }
    return settingsJson.encodeToString(SettingsDocumentV1(SETTINGS_FORMAT_VERSION, automaticBackupCount))
}
