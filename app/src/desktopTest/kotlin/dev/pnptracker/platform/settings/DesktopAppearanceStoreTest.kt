package dev.pnptracker.platform.settings

import dev.pnptracker.domain.settings.AccentColor
import dev.pnptracker.domain.settings.Appearance
import dev.pnptracker.domain.settings.AppearanceNotSaved
import dev.pnptracker.domain.settings.AppearanceProblem
import dev.pnptracker.domain.settings.SettingsWriteFailure
import dev.pnptracker.domain.settings.ThemeMode
import dev.pnptracker.platform.files.PlatformFileRules
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The appearance on a real disk (PLAN 12.16).
 *
 * The same two promises the other two files make: reading never creates it, and a
 * file that cannot be understood is left exactly as it is rather than repaired.
 *
 * And one of its own, which is the opposite of the table's sizes. Somebody pressed
 * a control and asked for this to be remembered, so a refused write **is** raised
 * — the screen has a line to show for it.
 */
class DesktopAppearanceStoreTest {
    private lateinit var folder: Path
    private lateinit var appearanceFile: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-appearance-test")
        appearanceFile = folder.resolve("appearance.json")
    }

    @AfterTest
    fun deleteFolder() {
        val absolute = folder.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        PlatformFileRules.letWritingBack(absolute)
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun aStore() = DesktopAppearanceStore(appearanceFile)

    @Test
    fun `with no file the application is light and purple, and there is still no file`() =
        runBlocking<Unit> {
            val read = aStore().read()

            assertEquals(Appearance(), read)
            assertNull(read.problem, "a machine that has chosen nothing has no problem")
            assertTrue(Files.notExists(appearanceFile), "reading created the file")
        }

    @Test
    fun `what was chosen is what the next start reads`() =
        runBlocking<Unit> {
            val chosen = Appearance(ThemeMode.DARK, AccentColor.TEAL)

            aStore().write(chosen)

            // A different store over the same file: the next start of the
            // application, as far as this file is concerned.
            assertEquals(chosen, aStore().read())
        }

    @Test
    fun `a second choice replaces the first and leaves one document`() =
        runBlocking<Unit> {
            val store = aStore()

            store.write(Appearance(ThemeMode.DARK, AccentColor.ROSE))
            store.write(Appearance(ThemeMode.LIGHT, AccentColor.GREEN))

            assertEquals(Appearance(ThemeMode.LIGHT, AccentColor.GREEN), store.read())
            assertEquals(listOf("appearance.json"), Files.list(folder).use { it.map { p -> p.fileName.toString() }.sorted().toList() })
        }

    @Test
    fun `a file that is not this document is reported and left exactly as it was`() =
        runBlocking<Unit> {
            val hand = """{"formatVersion":1,"themeMode":"SEPIA","accentColor":"BLUE"}"""
            Files.writeString(appearanceFile, hand)

            val read = aStore().read()

            assertEquals(AppearanceProblem.VALUE_NOT_RECOGNISED, read.problem)
            assertEquals(Appearance().themeMode, read.themeMode)
            assertEquals(hand, Files.readString(appearanceFile), "the file was rewritten")
        }

    @Test
    fun `a folder that will not take a file says so instead of failing silently`() =
        runBlocking<Unit> {
            PlatformFileRules.refuseWriting(folder)

            val refused = assertFailsWith<AppearanceNotSaved> { aStore().write(Appearance(ThemeMode.DARK)) }

            assertEquals(SettingsWriteFailure.NOT_WRITABLE, refused.failure)
            PlatformFileRules.letWritingBack(folder)
            assertTrue(Files.notExists(appearanceFile), "a refused write left a file behind")
        }
}
