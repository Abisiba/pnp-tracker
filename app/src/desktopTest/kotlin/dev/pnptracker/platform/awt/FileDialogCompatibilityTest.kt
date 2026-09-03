package dev.pnptracker.platform.awt

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the one startup decision that keeps the file dialog usable on Linux.
 *
 * The GTK chooser opens unpainted here, so AWT is told up front to use its own.
 * That has to be settled before the toolkit exists, which is why this is checked
 * as a plain function over a property map rather than by opening a window.
 */
class FileDialogCompatibilityTest {
    private var originalValue: String? = null

    @BeforeTest
    fun rememberRealProperty() {
        originalValue = System.getProperty(DISABLE_GTK_FILE_DIALOGS)
    }

    @AfterTest
    fun restoreRealProperty() {
        // The property is global to the test JVM, so it never leaks into a sibling.
        val original = originalValue
        if (original == null) System.clearProperty(DISABLE_GTK_FILE_DIALOGS) else System.setProperty(DISABLE_GTK_FILE_DIALOGS, original)
    }

    /** A property map standing in for the JVM's, so nothing global is touched. */
    private class Properties(
        vararg entries: Pair<String, String>,
    ) {
        val values = entries.toMap().toMutableMap()

        fun read(key: String): String? = values[key]

        fun write(
            key: String,
            value: String,
        ) {
            values[key] = value
        }
    }

    private fun applyOn(
        osName: String,
        properties: Properties,
    ) = applyLinuxFileDialogPolicy(osName, properties::read, properties::write)

    @Test
    fun `on linux with nothing set the awt dialog is chosen`() {
        val properties = Properties()

        applyOn("Linux", properties)

        assertEquals("true", properties.read(DISABLE_GTK_FILE_DIALOGS))
    }

    @Test
    fun `asking for the gtk dialog back is respected`() {
        val properties = Properties(DISABLE_GTK_FILE_DIALOGS to "false")

        applyOn("Linux", properties)

        assertEquals(
            "false",
            properties.read(DISABLE_GTK_FILE_DIALOGS),
            "an explicit choice was overwritten, so the user could not get the gtk dialog back",
        )
    }

    @Test
    fun `an existing true is left exactly as it was`() {
        val properties = Properties(DISABLE_GTK_FILE_DIALOGS to "true")

        applyOn("Linux", properties)

        assertEquals("true", properties.read(DISABLE_GTK_FILE_DIALOGS))
    }

    @Test
    fun `windows and macos are left alone`() {
        for (osName in listOf("Windows 11", "Mac OS X", "FreeBSD")) {
            val properties = Properties()

            applyOn(osName, properties)

            assertNull(
                properties.read(DISABLE_GTK_FILE_DIALOGS),
                "$osName does not have this problem and must keep its own dialog",
            )
        }
    }

    @Test
    fun `the linux name is recognised however it is capitalised`() {
        for (osName in listOf("Linux", "linux", "LINUX")) {
            val properties = Properties()

            applyOn(osName, properties)

            assertEquals("true", properties.read(DISABLE_GTK_FILE_DIALOGS), "not recognised: $osName")
        }
    }

    @Test
    fun `the real property is set on this linux machine`() {
        System.clearProperty(DISABLE_GTK_FILE_DIALOGS)

        applyLinuxFileDialogPolicy()

        assertEquals("true", System.getProperty(DISABLE_GTK_FILE_DIALOGS))
    }

    @Test
    fun `the policy is applied before anything can create the awt toolkit`() {
        val source = Files.readString(mainSource())
        val body = source.substringAfter("fun main() {")

        val policyCall = body.indexOf("applyLinuxFileDialogPolicy()")
        assertTrue(policyCall >= 0, "main() no longer applies the file dialog policy")

        // Everything below eventually reaches AWT: Compose builds the toolkit, and
        // the picker is handed to the controller ready to open a dialog.
        for (later in listOf("application {", "AwtImportFilePicker", "ImportController(")) {
            val at = body.indexOf(later)
            assertTrue(at < 0 || policyCall < at, "'$later' comes before the file dialog policy in main()")
        }
    }

    @Test
    fun `the policy itself does not touch awt`() {
        // Comments are stripped first: the property being set is literally named
        // after the dialog, and prose about it is not a reference to the class.
        val code = withoutComments(Files.readString(policySource()))

        for (forbidden in listOf("java.awt", "Toolkit", "FileDialog(", "androidx.compose")) {
            assertTrue(
                forbidden !in code,
                "the policy references $forbidden, which would build the toolkit it is meant to configure first",
            )
        }
    }

    /** Strips KDoc and line comments, so prose is not mistaken for code. */
    private fun withoutComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun mainSource(): Path = moduleRoot().resolve("src/desktopMain/kotlin/dev/pnptracker/Main.kt")

    private fun policySource(): Path = moduleRoot().resolve("src/desktopMain/kotlin/dev/pnptracker/platform/awt/FileDialogCompatibility.kt")

    private fun moduleRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .firstOrNull { Files.isDirectory(it.resolve("src/desktopMain/kotlin")) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("Could not locate the 'app' module from ${Path.of("").toAbsolutePath()}")
    }
}
