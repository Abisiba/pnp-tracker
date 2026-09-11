package dev.pnptracker.domain.backup.retention

import dev.pnptracker.domain.settings.SettingsStore

/**
 * Clearing old automatic backups away, after a new one has been written.
 *
 * An interface so the thing that writes a backup does not have to know where the
 * retention number comes from, and so a test can watch the housekeeping happen
 * without a settings file or a folder.
 *
 * Nothing here throws for an outcome. PLAN 14.4.13 makes a file that will not go
 * away a thing to report rather than a failure — the work this follows has
 * already succeeded and the user's data is not at risk — so a caller can run it
 * last and ignore what it says.
 */
fun interface AutomaticBackupHousekeeping {
    /**
     * @param setName the name, without its extension, of the backup that has
     *   just been written and verified. Rotation looks for it and clears nothing
     *   at all if it cannot find it.
     */
    suspend fun afterWriting(setName: String)
}

/**
 * The housekeeping the application really does: read the number, apply it.
 *
 * The number is read each time rather than remembered. PLAN 14.4.12 has a change
 * take effect at the next automatic backup, and reading it here is what makes
 * that true without anybody having to tell this object that a setting moved.
 *
 * A machine with no settings file gets the default, which is the same answer the
 * screen shows; reading does not create the file.
 */
class SettingsDrivenHousekeeping(
    private val settings: SettingsStore,
    private val rotation: AutomaticBackupRotation,
) : AutomaticBackupHousekeeping {
    override suspend fun afterWriting(setName: String) {
        rotation.rotateAfter(setName, settings.read().automaticBackupCount)
    }
}
