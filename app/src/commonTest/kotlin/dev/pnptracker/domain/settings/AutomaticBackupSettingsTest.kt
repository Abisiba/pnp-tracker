package dev.pnptracker.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading and writing the one setting this application has.
 *
 * The reading half is where all the care is. A settings file is small enough to
 * be edited by hand and old enough to have been written by another build, so
 * every way it can be wrong ends at the same place — the default, with a reason,
 * and the file left exactly as it was (PLAN 14.4.12). Nothing here throws for a
 * file it cannot understand, because a person should not be unable to start the
 * application over a number.
 */
class AutomaticBackupSettingsTest {
    @Test
    fun `the document written is the one the contract shows`() {
        // Byte for byte, because the contract in PLAN 14.4.12 shows the file and
        // a person may well open it.
        assertEquals("""{"formatVersion":1,"automaticBackupCount":7}""", settingsDocumentFor(7))
    }

    @Test
    fun `a number that may be kept survives being written and read`() {
        listOf(1, 7, 50).forEach { count ->
            val read = settingsIn(settingsDocumentFor(count))

            assertEquals(count, read.automaticBackupCount, "$count")
            assertNull(read.problem, "$count")
        }
    }

    @Test
    fun `a number outside the range is never written`() {
        // The screen refuses these long before here, so reaching this is a
        // defect rather than an outcome and it is raised as one.
        listOf(0, -1, -50, 51, 1000).forEach { count ->
            assertFailsWith<IllegalArgumentException>("$count") { settingsDocumentFor(count) }
        }
    }

    @Test
    fun `a number outside the range in the file is refused and the default used`() {
        listOf(0, -1, 51, 1000).forEach { count ->
            val read = settingsIn("""{"formatVersion":1,"automaticBackupCount":$count}""")

            assertEquals(SettingsProblem.VALUE_OUT_OF_RANGE, read.problem, "$count")
            assertEquals(7, read.automaticBackupCount, "$count")
        }
    }

    @Test
    fun `zero is out of range rather than off`() {
        // PLAN 14.4.12 makes automatic protection something that cannot be
        // turned off; zero is refused like any other number out of range.
        val read = settingsIn("""{"formatVersion":1,"automaticBackupCount":0}""")

        assertEquals(SettingsProblem.VALUE_OUT_OF_RANGE, read.problem)
        assertEquals(7, read.automaticBackupCount)
        assertTrue(!isKeepableCount(0))
    }

    @Test
    fun `a file this build does not know the version of is left alone`() {
        listOf(0, 2, 99).forEach { version ->
            val read = settingsIn("""{"formatVersion":$version,"automaticBackupCount":9}""")

            assertEquals(SettingsProblem.VERSION_NOT_SUPPORTED, read.problem, "$version")
            assertEquals(7, read.automaticBackupCount, "$version")
        }
    }

    @Test
    fun `anything that is not the document is the default and a reason`() {
        val refused =
            listOf(
                "",
                "   ",
                "{",
                "not json at all",
                "[]",
                """{"formatVersion":1}""",
                """{"automaticBackupCount":9}""",
                """{"formatVersion":1,"automaticBackupCount":null}""",
                """{"formatVersion":1,"automaticBackupCount":7.5}""",
            )

        refused.forEach { text ->
            val read = settingsIn(text)

            assertEquals(SettingsProblem.NOT_THE_EXPECTED_SHAPE, read.problem, text)
            assertEquals(7, read.automaticBackupCount, text)
        }
    }

    @Test
    fun `a field this build has never heard of is ignored`() {
        // The deliberate opposite of the backup's rule, and PLAN 14.4.12 gives
        // the reason: this file carries no user data, and being strict about it
        // would leave somebody who had opened a newer build unable to start an
        // older one.
        val read = settingsIn("""{"formatVersion":1,"automaticBackupCount":12,"theme":"dark","whatIsThis":[1,2]}""")

        assertEquals(12, read.automaticBackupCount)
        assertNull(read.problem)
    }

    @Test
    fun `a number written in quotes is still that number`() {
        // Measured rather than assumed: the parser reads a quoted integer as the
        // integer. Kept as a test because it is worth knowing and worth keeping
        // — a settings file is small enough to be edited by hand, and somebody
        // who typed `"7"` meant seven. This is the settings side of the split
        // PLAN 14.4.12 draws: strict where user data is at stake, forgiving
        // where a number is.
        val read = settingsIn("""{"formatVersion":"1","automaticBackupCount":"9"}""")

        assertEquals(9, read.automaticBackupCount)
        assertNull(read.problem)
    }

    @Test
    fun `the range is the one the plan names`() {
        assertTrue(isKeepableCount(1))
        assertTrue(isKeepableCount(50))
        assertTrue(!isKeepableCount(0))
        assertTrue(!isKeepableCount(51))
        assertEquals(7, AutomaticBackupSettings().automaticBackupCount)
        assertNull(AutomaticBackupSettings().problem)
    }

    @Test
    fun `a settings value carries no path, no hash and nothing of anybody's`() {
        // PLAN 14.4.12: the file holds a version and a number and nothing else,
        // so there is nothing in it that could identify a machine or a person.
        val written = settingsDocumentFor(23)

        assertEquals("""{"formatVersion":1,"automaticBackupCount":23}""", written)
        assertTrue(written.none { it == '/' || it == '\\' }, written)
    }
}
