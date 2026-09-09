package dev.pnptracker.domain.backup

import dev.pnptracker.domain.time.LocalMoment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The two decisions about what a backup is called.
 *
 * A name travels further than a file does — it appears in a folder listing, in a
 * message, in a screenshot somebody sends for help — so the offered one carries
 * a date and nothing else, and the accepted one is either a `.json` or is
 * refused.
 */
class BackupFileNameTest {
    private fun day(
        year: Int,
        month: Int,
        dayOfMonth: Int,
    ) = LocalMoment(year = year, month = month, dayOfMonth = dayOfMonth, hour = 23, minute = 59, second = 41)

    @Test
    fun `the offered name is the user's own calendar day`() {
        assertEquals("pnp-yedek-2026-09-08.json", suggestedBackupFileName(day(2026, 9, 8)))
    }

    @Test
    fun `single digits are padded so names sort the way days do`() {
        assertEquals("pnp-yedek-2026-01-05.json", suggestedBackupFileName(day(2026, 1, 5)))
        assertEquals("pnp-yedek-2026-12-31.json", suggestedBackupFileName(day(2026, 12, 31)))
    }

    @Test
    fun `the time of day never reaches the name`() {
        val early = LocalMoment(year = 2026, month = 9, dayOfMonth = 8, hour = 0, minute = 0, second = 0)
        val late = LocalMoment(year = 2026, month = 9, dayOfMonth = 8, hour = 23, minute = 59, second = 41)
        assertEquals(suggestedBackupFileName(early), suggestedBackupFileName(late))
    }

    @Test
    fun `a name with no extension becomes a json`() {
        assertEquals("yedek.json", jsonFileNameOf("yedek"))
        assertEquals("pnp-yedek-2026-09-08.json", jsonFileNameOf("pnp-yedek-2026-09-08"))
    }

    @Test
    fun `a name that is already a json is left exactly as it was`() {
        assertEquals("yedek.json", jsonFileNameOf("yedek.json"))
        assertEquals("YEDEK.JSON", jsonFileNameOf("YEDEK.JSON"), "a second extension for a case difference")
    }

    @Test
    fun `a hidden file has no extension to keep`() {
        assertEquals(".yedek.json", jsonFileNameOf(".yedek"))
    }

    @Test
    fun `any other extension is refused rather than doubled`() {
        val refused = assertFailsWith<BackupException> { jsonFileNameOf("yedek.txt") }
        assertEquals(BackupFailure.UNSUPPORTED_FILE_TYPE, refused.failure)
        assertFailsWith<BackupException> { jsonFileNameOf("yedek.2026") }
        assertFailsWith<BackupException> { jsonFileNameOf("yedek.json.gz") }
    }
}
