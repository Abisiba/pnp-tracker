package dev.pnptracker.domain.settings

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The appearance document: what is written, what is read back, and what happens
 * to a file that cannot be understood (PLAN 12.16).
 */
class AppearanceTest {
    @Test
    fun `a machine that has chosen nothing is light and purple`() {
        // The look the application has always had. Changing this default would
        // change how every existing installation opens.
        val fresh = Appearance()

        assertEquals(ThemeMode.LIGHT, fresh.themeMode)
        assertEquals(AccentColor.PURPLE, fresh.accentColor)
        assertNull(fresh.problem)
    }

    @Test
    fun `every theme and every accent survives being written and read back`() {
        ThemeMode.entries.forEach { theme ->
            AccentColor.entries.forEach { accent ->
                val chosen = Appearance(themeMode = theme, accentColor = accent)

                val read = appearanceIn(appearanceDocumentFor(chosen))

                assertEquals(chosen, read, "$theme with $accent did not come back")
            }
        }
    }

    @Test
    fun `the document names the version and the two choices by name`() {
        val document = appearanceDocumentFor(Appearance(ThemeMode.DARK, AccentColor.TEAL))

        assertTrue("\"formatVersion\":$APPEARANCE_FORMAT_VERSION" in document, document)
        assertTrue("\"themeMode\":\"DARK\"" in document, document)
        assertTrue("\"accentColor\":\"TEAL\"" in document, document)
    }

    @Test
    fun `a file that is not this document leaves the defaults in use`() {
        listOf(
            "" to AppearanceProblem.NOT_THE_EXPECTED_SHAPE,
            "not json at all" to AppearanceProblem.NOT_THE_EXPECTED_SHAPE,
            "{}" to AppearanceProblem.NOT_THE_EXPECTED_SHAPE,
            """{"formatVersion":1}""" to AppearanceProblem.NOT_THE_EXPECTED_SHAPE,
            """{"formatVersion":2,"themeMode":"DARK","accentColor":"BLUE"}""" to AppearanceProblem.VERSION_NOT_SUPPORTED,
            """{"formatVersion":1,"themeMode":"SEPIA","accentColor":"BLUE"}""" to AppearanceProblem.VALUE_NOT_RECOGNISED,
            """{"formatVersion":1,"themeMode":"DARK","accentColor":"CHARTREUSE"}""" to AppearanceProblem.VALUE_NOT_RECOGNISED,
            """{"formatVersion":1,"themeMode":"dark","accentColor":"BLUE"}""" to AppearanceProblem.VALUE_NOT_RECOGNISED,
        ).forEach { (text, expected) ->
            val read = appearanceIn(text)

            assertEquals(expected, read.problem, "`$text` was read as ${read.problem}")
            // And whatever was wrong with it, what is in use is usable.
            assertEquals(ThemeMode.LIGHT, read.themeMode, "`$text`")
            assertEquals(AccentColor.PURPLE, read.accentColor, "`$text`")
        }
    }

    @Test
    fun `a field a newer build added does not make the file unreadable`() {
        // The same choice the settings document makes, for the same reason: this
        // file carries no user data, so being strict about an unknown field would
        // leave somebody who had opened a newer build unable to start an older one.
        val read = appearanceIn("""{"formatVersion":1,"themeMode":"DARK","accentColor":"ROSE","contrast":"high"}""")

        assertEquals(Appearance(ThemeMode.DARK, AccentColor.ROSE), read)
    }

    @Test
    fun `a store with nowhere to write answers the default and forgets what it was given`() =
        runBlocking<Unit> {
            val forgetful = AppearanceStore.Forgetful

            forgetful.write(Appearance(ThemeMode.DARK, AccentColor.ROSE))

            assertEquals(Appearance(), forgetful.read())
        }
}
