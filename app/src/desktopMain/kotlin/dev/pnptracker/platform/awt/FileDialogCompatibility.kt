package dev.pnptracker.platform.awt

import java.util.Locale

/**
 * A JDK-internal switch that makes AWT use its own file dialog instead of GTK's.
 *
 * `sun.*` is an implementation detail rather than a promise, and it is used here
 * with that understood: if a future JDK drops it, the property is simply ignored
 * and the dialog goes back to whatever that JDK gives. Nothing else depends on it.
 */
internal const val DISABLE_GTK_FILE_DIALOGS = "sun.awt.disableGtkFileDialogs"

/**
 * Picks a file dialog that actually draws itself, on Linux.
 *
 * On the development and target environment for this release — Garuda Linux —
 * AWT's GTK file chooser opens as a completely unpainted window: GTK reports
 * `drawing failure for widget 'GtkFileChooserWidget': error occurred in
 * libfreetype` for every widget it tries to paint, and the user is left with a
 * transparent rectangle and no way to choose anything. AWT's own dialog renders
 * with the JDK's fonts and is unaffected, so Linux is told to use that one.
 *
 * This has to happen before the AWT toolkit is created, because the choice is
 * read once while the toolkit is being set up. Looking at a window after it has
 * opened would be too late, and guessing from an empty window would be guesswork,
 * so the decision is made up front and stays the same for the whole run.
 *
 * An explicit choice always wins: if the property is already set, whoever set it
 * — the user on the command line, or a JVM launcher — meant it, including when
 * they asked for the GTK dialog back.
 */
fun applyLinuxFileDialogPolicy() {
    applyLinuxFileDialogPolicy(
        osName = System.getProperty("os.name").orEmpty(),
        readProperty = { key -> System.getProperty(key) },
        writeProperty = { key, value -> System.setProperty(key, value) },
    )
}

internal fun applyLinuxFileDialogPolicy(
    osName: String,
    readProperty: (String) -> String?,
    writeProperty: (String, String) -> Unit,
) {
    // Only Linux is affected; the other desktops get their native dialog, which
    // is also what they will need when they become supported targets.
    if (!osName.lowercase(Locale.ROOT).contains("linux")) return
    if (readProperty(DISABLE_GTK_FILE_DIALOGS) != null) return
    writeProperty(DISABLE_GTK_FILE_DIALOGS, "true")
}
