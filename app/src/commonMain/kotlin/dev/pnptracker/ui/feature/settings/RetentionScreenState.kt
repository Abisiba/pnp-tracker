package dev.pnptracker.ui.feature.settings

import dev.pnptracker.domain.settings.SettingsProblem
import dev.pnptracker.domain.settings.SettingsWriteFailure

/**
 * Where choosing how many automatic backups to keep has got to.
 *
 * A case each rather than a value and two booleans, for the reason
 * [BackupScreenState] gives: "it is being read" and "it is being written" are
 * never both true, and a screen that reads the state should not have to work
 * that out.
 *
 * [Ready] carries what the file said as well as what is in use, because PLAN
 * 14.4.12 asks the screen to say plainly when the default is standing in for a
 * file it could not understand.
 */
sealed interface RetentionScreenState {
    /** The file has not been read yet. */
    data object Loading : RetentionScreenState

    /**
     * The number in use, and anything the user should know about where it came
     * from.
     *
     * [problem] is null both when the file was read and when there is no file —
     * a machine with no settings has nothing wrong with it. [justSaved] is what
     * the screen announces after a save; it says nothing about the file's
     * contents and is cleared as soon as the user changes anything.
     */
    data class Ready(
        val automaticBackupCount: Int,
        val problem: SettingsProblem? = null,
        val justSaved: Boolean = false,
    ) : RetentionScreenState

    /** A value is on its way to the file. */
    data object Saving : RetentionScreenState

    /**
     * Nothing was written, and why.
     *
     * [automaticBackupCount] is still the number in use: PLAN 14.4.12 keeps the
     * running value when a write fails, so the screen goes on showing what the
     * application is actually doing rather than what somebody tried to ask for.
     */
    data class Failed(
        val automaticBackupCount: Int,
        val failure: SettingsWriteFailure,
    ) : RetentionScreenState

    /** True while something is in flight, so the screen can refuse a second one. */
    val isBusy: Boolean
        get() = this is Loading || this is Saving

    /** The number the application is keeping to, whatever is on screen. */
    val countInUse: Int?
        get() =
            when (this) {
                is Ready -> automaticBackupCount
                is Failed -> automaticBackupCount
                Loading, Saving -> null
            }
}
