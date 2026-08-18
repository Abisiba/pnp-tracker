package dev.pnptracker.ui

import dev.pnptracker.AppInfo
import dev.pnptracker.ui.navigation.Screen
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Resolves the catalogue through the real Compose resource loader, so these
 * tests fail on a text that is missing from `strings.xml` as well as on one that
 * is there but empty.
 */
class TurkishTextCatalogTest {
    private val everyUserText: Map<String, StringResource> =
        mapOf(
            "App.name" to Strings.App.name,
            "App.windowTitle" to Strings.App.windowTitle,
            "App.versionLabel" to Strings.App.versionLabel,
            "Navigation.sectionLabel" to Strings.Navigation.sectionLabel,
            "Navigation.home" to Strings.Navigation.home,
            "Navigation.games" to Strings.Navigation.games,
            "Navigation.importReview" to Strings.Navigation.importReview,
            "ScreenTitles.home" to Strings.ScreenTitles.home,
            "ScreenTitles.games" to Strings.ScreenTitles.games,
            "ScreenTitles.importReview" to Strings.ScreenTitles.importReview,
            "ScreenDescriptions.home" to Strings.ScreenDescriptions.home,
            "ScreenDescriptions.games" to Strings.ScreenDescriptions.games,
            "ScreenDescriptions.importReview" to Strings.ScreenDescriptions.importReview,
            "Theme.sectionLabel" to Strings.Theme.sectionLabel,
            "Theme.switchToDark" to Strings.Theme.switchToDark,
            "Theme.switchToLight" to Strings.Theme.switchToLight,
            "Accessibility.navigationLabel" to Strings.Accessibility.navigationLabel,
            "Accessibility.selected" to Strings.Accessibility.selected,
            "Accessibility.notSelected" to Strings.Accessibility.notSelected,
        )

    @Test
    fun `no catalogued text is missing or blank`() =
        runBlocking<Unit> {
            everyUserText.forEach { (name, resource) ->
                val text = getString(resource)
                assertTrue(text.isNotBlank(), "$name resolved to blank text")
                assertEquals(text, text.trim(), "$name has stray whitespace around it")
            }
        }

    @Test
    fun `the application name and the window title are the Turkish product name`() =
        runBlocking<Unit> {
            assertEquals("PnP Üretim Takipçisi", getString(Strings.App.name))
            assertEquals("PnP Üretim Takipçisi", getString(Strings.App.windowTitle))
        }

    @Test
    fun `the version label carries the running version`() =
        runBlocking<Unit> {
            val label = getString(Strings.App.versionLabel, AppInfo.Current.version)

            assertTrue(label.contains(AppInfo.Current.version), "version missing from: $label")
            assertTrue(label.none { it == '%' }, "unformatted placeholder left in: $label")
        }

    @Test
    fun `the three navigation names differ from one another`() =
        runBlocking<Unit> {
            val names = Screen.all.map { getString(textsOf(it).navigationLabel) }

            assertEquals(3, names.size)
            assertEquals(names.size, names.toSet().size, "navigation names are not unique: $names")
        }

    @Test
    fun `every screen has a title and a description`() =
        runBlocking<Unit> {
            Screen.all.forEach { screen ->
                val texts = textsOf(screen)

                assertTrue(getString(texts.title).isNotBlank(), "$screen has no title")
                assertTrue(getString(texts.description).isNotBlank(), "$screen has no description")
            }
        }

    @Test
    fun `screen descriptions explain what each section is for`() =
        runBlocking<Unit> {
            val descriptions = Screen.all.map { getString(textsOf(it).description) }

            assertEquals(descriptions.size, descriptions.toSet().size, "descriptions are copies of one another")
            descriptions.forEach { description ->
                assertTrue(description.length > 40, "description is too short to explain anything: $description")
            }
        }

    @Test
    fun `the two selection states read differently`() =
        runBlocking<Unit> {
            assertTrue(
                getString(Strings.Accessibility.selected) != getString(Strings.Accessibility.notSelected),
                "a screen reader would not be able to tell the selected entry apart",
            )
        }

    @Test
    fun `the theme control names the appearance it switches to`() =
        runBlocking<Unit> {
            assertTrue(
                getString(Strings.Theme.switchToDark) != getString(Strings.Theme.switchToLight),
                "both directions of the theme control read the same",
            )
        }
}
