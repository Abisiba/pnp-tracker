package dev.pnptracker

/**
 * Identity of the application as used by code: the [id] names the XDG data and
 * config directories, the [version] is recorded in backups and export files.
 *
 * User visible texts are not kept here; they live in the Compose resources.
 */
data class AppInfo(
    val id: String,
    val version: String,
) {
    init {
        require(ID_PATTERN.matches(id)) {
            "Application id must be lower case kebab-case, was: $id"
        }
        require(VERSION_PATTERN.matches(version)) {
            "Application version must be major.minor.patch, was: $version"
        }
    }

    companion object {
        private val ID_PATTERN = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
        private val VERSION_PATTERN = Regex("\\d+\\.\\d+\\.\\d+")

        val Current = AppInfo(id = "pnp-tracker", version = "0.1.0")
    }
}
