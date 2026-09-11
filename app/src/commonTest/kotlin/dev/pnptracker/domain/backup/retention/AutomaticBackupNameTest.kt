package dev.pnptracker.domain.backup.retention

import dev.pnptracker.domain.backup.backupStampOf
import dev.pnptracker.domain.backup.restore.safetyBackupFileName
import dev.pnptracker.domain.time.LocalMoment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MOMENT = LocalMoment(2026, 9, 9, 14, 33, 55)

/**
 * What the backups nobody asked for are called, and which names are ours.
 *
 * Two halves of one subject. Writing the names matters because they are also the
 * sort key and the way a migration's two files find each other again. Reading
 * them matters more: rotation deletes things, and the first question it asks of
 * a file is whether this application would ever have written that name. A
 * generous answer here would put somebody's own file within reach of a delete.
 */
class AutomaticBackupNameTest {
    @Test
    fun `an import snapshot says what it is and when, down to the second`() {
        assertEquals("pnp-otomatik-import-2026-09-09-143355.json", importSnapshotFileName(MOMENT))
    }

    @Test
    fun `a migration set is named once, for both of its files`() {
        val setName = migrationSnapshotSetName(fromSchemaVersion = 3, toSchemaVersion = 8, moment = MOMENT)

        assertEquals("pnp-otomatik-migration-v3-v8-2026-09-09-143355", setName)
        // PLAN 14.4.9 keeps the pair together, and this is how: one name, two
        // extensions, so neither can drift away from the other.
        assertEquals(
            listOf("pnp-otomatik-migration-v3-v8-2026-09-09-143355.db", "pnp-otomatik-migration-v3-v8-2026-09-09-143355.json"),
            listOf("$setName.db", "$setName.json"),
        )
    }

    @Test
    fun `a second set in the same second carries one ordinal across both files`() {
        val setName = migrationSnapshotSetName(fromSchemaVersion = 7, toSchemaVersion = 8, moment = MOMENT, attempt = 2)

        assertEquals("pnp-otomatik-migration-v7-v8-2026-09-09-143355-2", setName)
        val database = assertNotNull(automaticBackupNameOf("$setName.db"))
        val document = assertNotNull(automaticBackupNameOf("$setName.json"))
        assertEquals(database.setName, document.setName)
        assertEquals(2, database.attempt)
        assertEquals(2, document.attempt)
    }

    @Test
    fun `every automatic name carries nothing out of anybody's data`() {
        val names =
            listOf(
                importSnapshotFileName(MOMENT, attempt = 3),
                safetyBackupFileName(MOMENT, attempt = 3),
                "${migrationSnapshotSetName(3, 8, MOMENT, attempt = 3)}.json",
            )

        names.forEach { name ->
            val parsed = assertNotNull(automaticBackupNameOf(name), name)
            // Whatever is left after the words this application chose is digits,
            // dashes and the extension. No game, no file, no machine, no user.
            val remainder =
                name
                    .removePrefix(IMPORT_SNAPSHOT_PREFIX)
                    .removePrefix(MIGRATION_SNAPSHOT_PREFIX)
                    .removePrefix("pnp-oncesi-")
                    .removeSuffix(parsed.extension)
            assertTrue(remainder.all { it.isDigit() || it == '-' || it == 'v' }, name)
        }
    }

    @Test
    fun `the three kinds are told apart, and a migration says which versions`() {
        assertEquals(AutomaticBackupKind.IMPORT, automaticBackupNameOf(importSnapshotFileName(MOMENT))?.kind)
        assertEquals(AutomaticBackupKind.SAFETY, automaticBackupNameOf(safetyBackupFileName(MOMENT))?.kind)

        val migration = assertNotNull(automaticBackupNameOf("${migrationSnapshotSetName(3, 8, MOMENT)}.db"))
        assertEquals(AutomaticBackupKind.MIGRATION, migration.kind)
        assertEquals(3, migration.fromSchemaVersion)
        assertEquals(8, migration.toSchemaVersion)
        assertEquals(MIGRATION_DATABASE_EXTENSION, migration.extension)
    }

    @Test
    fun `a manual backup is not an automatic one and never becomes reachable`() {
        // The whole of PLAN 14.4.11's exemption for manual files rests on this
        // one answer: rotation only ever sees what this function admits to.
        assertNull(automaticBackupNameOf("pnp-yedek-2026-09-09.json"))
        assertNull(automaticBackupNameOf("pnp-yedek-2026-09-09-143355.json"))
    }

    @Test
    fun `names that are nearly ours are not ours`() {
        val refused =
            listOf(
                // The first attempt carries no ordinal, so a `-1` was written by
                // something else.
                "pnp-otomatik-import-2026-09-09-143355-1.json",
                // Ordinals are counted, not padded.
                "pnp-otomatik-import-2026-09-09-143355-02.json",
                // The stamp is fixed width, every field of it.
                "pnp-otomatik-import-2026-9-9-143355.json",
                "pnp-otomatik-import-2026-09-09-1433.json",
                // A moment nothing here could have been taken at.
                "pnp-otomatik-import-2026-13-09-143355.json",
                "pnp-otomatik-import-2026-09-32-143355.json",
                "pnp-otomatik-import-2026-09-09-243355.json",
                "pnp-otomatik-import-2026-09-09-146055.json",
                "pnp-otomatik-import-2026-09-09-143360.json",
                // Right name, wrong extension — and a `.part` is a file being
                // written, which is the atomic writer's business and not this.
                "pnp-otomatik-import-2026-09-09-143355.db",
                "pnp-otomatik-import-2026-09-09-143355.json.part",
                "pnp-oncesi-2026-09-09-143355.json.part",
                // Anything at all on either end.
                "kopya-pnp-otomatik-import-2026-09-09-143355.json",
                "pnp-otomatik-import-2026-09-09-143355 (1).json",
                "pnp-otomatik-import-.json",
                // A schema version this application does not number from.
                "pnp-otomatik-migration-v0-v8-2026-09-09-143355.json",
                "pnp-otomatik-migration-v03-v8-2026-09-09-143355.json",
                "pnp-otomatik-migration-3-8-2026-09-09-143355.json",
                // Not a backup at all.
                "notlar.txt",
                "pnp.db",
                "",
            )

        refused.forEach { assertNull(automaticBackupNameOf(it), it) }
    }

    @Test
    fun `every name this application writes is a name it recognises`() {
        val moments =
            listOf(
                LocalMoment(2026, 1, 2, 3, 4, 5),
                LocalMoment(2026, 12, 31, 23, 59, 59),
                LocalMoment(2026, 9, 9, 0, 0, 0),
            )

        moments.forEach { moment ->
            (1..12).forEach { attempt ->
                val written =
                    listOf(
                        importSnapshotFileName(moment, attempt),
                        safetyBackupFileName(moment, attempt),
                        "${migrationSnapshotSetName(1, 8, moment, attempt)}.json",
                        "${migrationSnapshotSetName(1, 8, moment, attempt)}.db",
                    )
                written.forEach { name ->
                    val parsed = assertNotNull(automaticBackupNameOf(name), name)
                    assertEquals(name, parsed.fileName)
                    assertEquals(attempt, parsed.attempt)
                    assertEquals(backupStampOf(moment), parsed.stamp)
                }
            }
        }
    }

    @Test
    fun `there is no attempt before the first and no schema version before the first`() {
        assertFailsWith<IllegalArgumentException> { importSnapshotFileName(MOMENT, attempt = 0) }
        assertFailsWith<IllegalArgumentException> { migrationSnapshotSetName(3, 8, MOMENT, attempt = 0) }
        assertFailsWith<IllegalArgumentException> { migrationSnapshotSetName(0, 8, MOMENT) }
        assertFailsWith<IllegalArgumentException> { migrationSnapshotSetName(3, 0, MOMENT) }
    }
}
