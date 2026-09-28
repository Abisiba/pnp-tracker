package dev.pnptracker.domain.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The version of the appearance document, which is nobody else's (PLAN 12.16). */
const val APPEARANCE_FORMAT_VERSION: Int = 1

/**
 * Light or dark, chosen by the user and remembered.
 *
 * It lives here rather than beside the colours it turns into, because it is the
 * value that gets written to a file: the pixels are the user interface's business
 * and the choice is the application's.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    ;

    fun toggled(): ThemeMode =
        when (this) {
            LIGHT -> DARK
            DARK -> LIGHT
        }
}

/**
 * The colour the interface accents itself with.
 *
 * A closed set rather than any colour at all, and that is the whole of the
 * decision. Every one of these has a light and a dark value picked to stay
 * readable against the surfaces it is drawn on (PLAN 17), which a free choice
 * could not promise — somebody would pick pale yellow and lose the words on
 * their own buttons.
 *
 * It has nothing to do with the colour catalogue. A colour named `Mor` in the
 * catalogue is a paint the user owns and assigns to 3D tasks; this is chrome, and
 * changing it changes no task's colour.
 */
enum class AccentColor {
    /** What the application has always looked like, and still does by default. */
    PURPLE,
    BLUE,
    TEAL,
    GREEN,
    AMBER,
    ROSE,
}

/** Why the appearance in use is the default rather than what the file said. */
enum class AppearanceProblem {
    /** The file is there and could not be read at all. */
    COULD_NOT_READ,

    /** What is in it is not the document this writes. */
    NOT_THE_EXPECTED_SHAPE,

    /** It names a version of the appearance format this build does not know. */
    VERSION_NOT_SUPPORTED,

    /** It names a theme or an accent this build has no such thing as. */
    VALUE_NOT_RECOGNISED,
}

/**
 * How the application looks right now, and whether that came from the file.
 *
 * Both values are always usable: either the file's or the default, so nothing
 * above this has to decide what to do about a bad one. [problem] is what the
 * screen shows beside them, and it is null both when the file was read and when
 * there is no file yet — a machine where nobody has chosen anything has none.
 */
data class Appearance(
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val accentColor: AccentColor = AccentColor.PURPLE,
    val problem: AppearanceProblem? = null,
)

/**
 * Where the appearance lives, seen from the side that has no files.
 *
 * Its own file rather than a field in `settings.json`, for the reason that file
 * gives for its own shape: it is read before every automatic backup and written
 * by one screen, and widening it would make every reader of one setting a reader
 * of the other. The two are written and read at different moments by different
 * parts of the application, so they are two documents (PLAN 12.16).
 */
interface AppearanceStore {
    /**
     * The appearance as it stands, falling back to the default rather than
     * failing. Reading never creates the file.
     */
    suspend fun read(): Appearance

    /**
     * Writes [appearance] as the whole document, atomically.
     *
     * The only thing that creates the file, and it happens because somebody chose
     * something.
     *
     * @throws AppearanceNotSaved if it could not be written. What was there
     *   before is byte for byte unchanged when this throws, and what is on screen
     *   is already what they asked for.
     */
    suspend fun write(appearance: Appearance)

    companion object {
        /**
         * An application with nowhere to remember how it looks.
         *
         * For a screen built to be looked at once, or a test with no business
         * with files: it answers with the default and forgets every choice, which
         * is exactly what having no file means.
         */
        val Forgetful: AppearanceStore =
            object : AppearanceStore {
                override suspend fun read(): Appearance = Appearance()

                override suspend fun write(appearance: Appearance) = Unit
            }
    }
}

/** Carries the reason and, for a developer, the cause. Never shown to a user. */
class AppearanceNotSaved(
    val failure: SettingsWriteFailure,
    cause: Throwable? = null,
) : Exception("The appearance could not be saved: $failure", cause)

@Serializable
private data class AppearanceDocumentV1(
    val formatVersion: Int,
    val themeMode: String,
    val accentColor: String,
)

/**
 * The one reader and writer of the appearance document.
 *
 * `ignoreUnknownKeys` for the same reason the settings document has it: this file
 * carries no user data, so being strict about a field a newer build added would
 * leave somebody unable to start an older one.
 */
private val appearanceJson: Json =
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
 * Reads [text] as an appearance document, and says what to use.
 *
 * Kept apart from any file so the whole of the decision can be tested without
 * one. Every unusable file ends up in the same place — the defaults, with a
 * reason — because there is exactly one safe thing to do with a file that cannot
 * be understood, and that is to leave it alone and carry on.
 */
fun appearanceIn(text: String): Appearance {
    val document =
        try {
            appearanceJson.decodeFromString<AppearanceDocumentV1>(text)
        } catch (notOurDocument: Exception) {
            // Deliberately broad, and deliberately only here: deserialisation
            // answers with several unrelated types for one question — "is this
            // our document" — and the answer to all of them is the same.
            return Appearance(problem = AppearanceProblem.NOT_THE_EXPECTED_SHAPE)
        }
    if (document.formatVersion != APPEARANCE_FORMAT_VERSION) {
        return Appearance(problem = AppearanceProblem.VERSION_NOT_SUPPORTED)
    }
    val theme = ThemeMode.entries.firstOrNull { it.name == document.themeMode }
    val accent = AccentColor.entries.firstOrNull { it.name == document.accentColor }
    if (theme == null || accent == null) {
        return Appearance(problem = AppearanceProblem.VALUE_NOT_RECOGNISED)
    }
    return Appearance(themeMode = theme, accentColor = accent)
}

/** The document this writes for [appearance], as the file will hold it. */
fun appearanceDocumentFor(appearance: Appearance): String =
    appearanceJson.encodeToString(
        AppearanceDocumentV1(
            formatVersion = APPEARANCE_FORMAT_VERSION,
            themeMode = appearance.themeMode.name,
            accentColor = appearance.accentColor.name,
        ),
    )
