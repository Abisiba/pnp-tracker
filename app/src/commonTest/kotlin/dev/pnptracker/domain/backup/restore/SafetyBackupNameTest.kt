package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.time.LocalMoment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the backup taken just before a restore is called.
 *
 * The manual backup's name is dated to the day and the user is asked before one
 * replaces another. This one is made without being asked for, so its name has to
 * settle the question on its own: down to the second, and with somewhere to go
 * when two land in the same one. Getting that wrong loses the very file that
 * exists so that nothing is lost.
 */
class SafetyBackupNameTest {
    @Test
    fun `the name says what it is and when, down to the second`() {
        val name = safetyBackupFileName(LocalMoment(2026, 9, 9, 14, 33, 55))

        assertEquals("pnp-oncesi-2026-09-09-143355.json", name)
    }

    @Test
    fun `every part is padded, so names of the same day line up and sort`() {
        val name = safetyBackupFileName(LocalMoment(2026, 1, 2, 3, 4, 5))

        assertEquals("pnp-oncesi-2026-01-02-030405.json", name)
    }

    @Test
    fun `a second name for the same moment is a different name`() {
        val moment = LocalMoment(2026, 9, 9, 14, 33, 55)

        val names = (1..4).map { safetyBackupFileName(moment, it) }

        assertEquals(
            listOf(
                "pnp-oncesi-2026-09-09-143355.json",
                "pnp-oncesi-2026-09-09-143355-2.json",
                "pnp-oncesi-2026-09-09-143355-3.json",
                "pnp-oncesi-2026-09-09-143355-4.json",
            ),
            names,
        )
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `it is a json, and carries nothing out of anybody's data`() {
        val name = safetyBackupFileName(LocalMoment(2026, 12, 31, 23, 59, 59), attempt = 7)

        assertTrue(name.endsWith(".json"))
        // Only the prefix, the digits of the moment and the ordinal. A file name
        // travels further than a file's contents do (PLAN 14.4.4).
        assertTrue(name.removePrefix(SAFETY_BACKUP_PREFIX).removeSuffix(".json").all { it.isDigit() || it == '-' }, name)
    }

    @Test
    fun `there is no attempt before the first`() {
        assertFailsWith<IllegalArgumentException> { safetyBackupFileName(LocalMoment(2026, 9, 9, 14, 33, 55), attempt = 0) }
    }
}
