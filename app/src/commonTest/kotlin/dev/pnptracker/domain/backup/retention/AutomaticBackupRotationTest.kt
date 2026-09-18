package dev.pnptracker.domain.backup.retention

import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.time.LocalMoment
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

private val WRITTEN_AT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/** The first bytes of a document the real writer produced. */
private fun documentHeader(): ByteArray =
    backupDocumentOf(anEmptyBackup(), appVersion = "0.1.0", sourceSchemaVersion = 8, createdAt = WRITTEN_AT)
        .json
        .encodeToByteArray()
        .copyOf(BACKUP_HEADER_BYTES)

/** The first bytes of a SQLite database still on [userVersion]. */
private fun databaseHeader(userVersion: Int): ByteArray {
    val header = ByteArray(BACKUP_HEADER_BYTES)
    "SQLite format 3".encodeToByteArray().copyInto(header)
    header[16] = 0x10
    header[63] = userVersion.toByte()
    return header
}

private fun momentAt(
    minute: Int,
    second: Int = 0,
) = LocalMoment(2026, 9, 9, 14, minute, second)

/**
 * A folder of files, with nothing on a disk.
 *
 * The engine's whole job is deciding, so what it decides about is handed to it
 * directly. [refuseToRemove] is how a file that will not go away is tested,
 * which PLAN 14.4.13 requires to leave the work rotation followed untouched.
 */
private class FakeBackupDirectory(
    private val files: MutableList<InspectedBackupFile> = mutableListOf(),
) : BackupDirectory {
    var refuseToRemove: Set<String> = emptySet()
    val removalsAttempted = mutableListOf<String>()

    val remaining: List<String> get() = files.map { it.name.fileName }.sorted()

    override suspend fun inspect(): List<InspectedBackupFile> = files.toList()

    override suspend fun remove(fileName: String): Boolean {
        removalsAttempted += fileName
        if (fileName in refuseToRemove) return false
        return files.removeAll { it.name.fileName == fileName }
    }

    fun put(
        fileName: String,
        ordinaryFile: Boolean = true,
        header: ByteArray = documentHeader(),
    ) {
        val name = checkNotNull(automaticBackupNameOf(fileName)) { "$fileName is not a name rotation would ever see" }
        files += InspectedBackupFile(name, ordinaryFile, header)
    }

    /** Puts both halves of a migration set, as the writer would. */
    fun putMigrationSet(
        setName: String,
        databaseUserVersion: Int = 3,
    ) {
        put("$setName.json")
        put("$setName.db", header = databaseHeader(databaseUserVersion))
    }
}

/**
 * Keeping the newest few of each kind, and touching nothing else.
 *
 * The one piece of this application that deletes files it was not asked to
 * delete, so what is tested here is mostly what it leaves alone. Every case
 * below is a file that looks close enough to be at risk: somebody's own backup,
 * a half-written pair, a link, a name one character off. PLAN 14.4.11 says the
 * application must prove a file is its own before removing it, and proving it is
 * exactly what makes all of these safe.
 */
class AutomaticBackupRotationTest {
    @Test
    fun `the newest are kept and the rest go`() =
        runBlocking<Unit> {
            val folder = FakeBackupDirectory()
            (1..5).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            val newest = importSnapshotFileName(momentAt(5))

            val outcome = AutomaticBackupRotation(folder).rotateAfter(newest.removeSuffix(".json"), keep = 3)

            assertEquals(
                listOf(importSnapshotFileName(momentAt(1)), importSnapshotFileName(momentAt(2))).sorted(),
                outcome.removed.sorted(),
            )
            assertEquals(
                listOf(importSnapshotFileName(momentAt(3)), importSnapshotFileName(momentAt(4)), newest).sorted(),
                folder.remaining,
            )
        }

    @Test
    fun `an afternoon of imports cannot crowd out a way back from a restore`() =
        runBlocking<Unit> {
            // The reason the quotas are separate (PLAN 14.4.11). Ten imports and
            // two safety backups, keeping three: under one shared count the
            // three newest files are all imports and both safety backups go —
            // which would quietly remove the only way back from a restore.
            val folder = FakeBackupDirectory()
            (10..19).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            (1..2).forEach { folder.put("pnp-oncesi-2026-09-09-140${it}00.json") }
            folder.putMigrationSet("pnp-otomatik-migration-v3-v8-2026-09-09-140000")
            val newest = importSnapshotFileName(momentAt(19))

            AutomaticBackupRotation(folder).rotateAfter(newest.removeSuffix(".json"), keep = 3)

            val left = folder.remaining
            assertEquals(3, left.count { it.startsWith(IMPORT_SNAPSHOT_PREFIX) }, left.toString())
            assertEquals(2, left.count { it.startsWith("pnp-oncesi-") }, left.toString())
            assertEquals(2, left.count { it.startsWith(MIGRATION_SNAPSHOT_PREFIX) }, left.toString())
        }

    @Test
    fun `a smaller count reaches every kind at the next snapshot`() =
        runBlocking<Unit> {
            // PLAN 14.4.12 has a reduced count take effect after the next
            // successful automatic snapshot rather than the moment it is saved.
            // The snapshot is of one kind and the count is of all of them, so a
            // pass trims each kind to its own quota — otherwise a migration set,
            // which is written about once in the life of an install, would never
            // be brought down to a number the user had lowered.
            val folder = FakeBackupDirectory()
            (1..4).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            (1..4).forEach { folder.put("pnp-oncesi-2026-09-09-140${it}00.json") }
            (1..4).forEach { folder.putMigrationSet("pnp-otomatik-migration-v3-v8-2026-09-09-140${it}30") }
            val newest = importSnapshotFileName(momentAt(4))

            AutomaticBackupRotation(folder).rotateAfter(newest.removeSuffix(".json"), keep = 2)

            val left = folder.remaining
            assertEquals(2, left.count { it.startsWith(IMPORT_SNAPSHOT_PREFIX) }, left.toString())
            assertEquals(2, left.count { it.startsWith("pnp-oncesi-") }, left.toString())
            // Two sets, which is four files.
            assertEquals(4, left.count { it.startsWith(MIGRATION_SNAPSHOT_PREFIX) }, left.toString())
        }

    @Test
    fun `a migration pair is one backup, kept and removed together`() =
        runBlocking<Unit> {
            val folder = FakeBackupDirectory()
            (1..4).forEach { folder.putMigrationSet("pnp-otomatik-migration-v3-v8-2026-09-09-140${it}00") }
            val newest = "pnp-otomatik-migration-v3-v8-2026-09-09-140400"

            val outcome = AutomaticBackupRotation(folder).rotateAfter(newest, keep = 2)

            // Two sets go, which is four files — not two files leaving two
            // orphans, and not two sets counted as four against the quota.
            assertEquals(4, outcome.removed.size, outcome.removed.toString())
            assertEquals(4, folder.remaining.size, folder.remaining.toString())
            assertTrue(folder.remaining.all { it.contains("140300") || it.contains("140400") }, folder.remaining.toString())
        }

    @Test
    fun `a backup stamped before all the others by a clock that went back is kept, and the count still holds`() =
        runBlocking<Unit> {
            // PLAN 14.7.3 / 14.4.11: the clock went back an hour between the old
            // backups and the new one, so the new one's name sorts first.
            (1..5).forEach { keep ->
                val folder = FakeBackupDirectory()
                (1..5).forEach { folder.put(importSnapshotFileName(momentAt(30 + it))) }
                val earlier = importSnapshotFileName(momentAt(10))
                folder.put(earlier)

                val outcome = AutomaticBackupRotation(folder).rotateAfter(earlier.removeSuffix(".json"), keep = keep)

                assertTrue(earlier in folder.remaining, "keep=$keep: the backup just written was removed")
                assertFalse(earlier in outcome.removed, "keep=$keep")
                assertEquals(keep, folder.remaining.size, "keep=$keep: ${folder.remaining}")
                // The others that stay are the newest by name, as before.
                val keptOthers = (1..5).reversed().take(keep - 1).map { importSnapshotFileName(momentAt(30 + it)) }
                assertEquals((keptOthers + earlier).sorted(), folder.remaining, "keep=$keep")
            }

            // The same for a migration set, which is one backup of two files.
            val folder = FakeBackupDirectory()
            (1..3).forEach { folder.putMigrationSet("pnp-otomatik-migration-v3-v8-2026-09-09-150${it}00") }
            val earlier = "pnp-otomatik-migration-v3-v8-2026-09-09-090000"
            folder.putMigrationSet(earlier)

            AutomaticBackupRotation(folder).rotateAfter(earlier, keep = 1)

            assertEquals(listOf("$earlier.db", "$earlier.json"), folder.remaining)
        }

    @Test
    fun `half a migration set is not a set, and neither half is removed`() =
        runBlocking<Unit> {
            // An interrupted write, or a deletion that only half succeeded. PLAN
            // 14.4.11 will not have either half removed on a guess.
            val folder = FakeBackupDirectory()
            (1..3).forEach { folder.putMigrationSet("pnp-otomatik-migration-v3-v8-2026-09-09-140${it}00") }
            folder.put("pnp-otomatik-migration-v3-v8-2026-09-09-135900.json")
            folder.put("pnp-otomatik-migration-v3-v8-2026-09-09-135800.db", header = databaseHeader(3))
            val newest = "pnp-otomatik-migration-v3-v8-2026-09-09-140300"

            AutomaticBackupRotation(folder).rotateAfter(newest, keep = 1)

            assertTrue("pnp-otomatik-migration-v3-v8-2026-09-09-135900.json" in folder.remaining)
            assertTrue("pnp-otomatik-migration-v3-v8-2026-09-09-135800.db" in folder.remaining)
        }

    @Test
    fun `a migration database that does not match its own name stays, with its partner`() =
        runBlocking<Unit> {
            val folder = FakeBackupDirectory()
            (1..2).forEach { folder.putMigrationSet("pnp-otomatik-migration-v3-v8-2026-09-09-140${it}00") }
            // The name says the database was on version 3; the file says 8.
            folder.putMigrationSet("pnp-otomatik-migration-v3-v8-2026-09-09-135700", databaseUserVersion = 8)

            AutomaticBackupRotation(folder).rotateAfter("pnp-otomatik-migration-v3-v8-2026-09-09-140200", keep = 1)

            assertTrue("pnp-otomatik-migration-v3-v8-2026-09-09-135700.db" in folder.remaining, folder.remaining.toString())
            assertTrue("pnp-otomatik-migration-v3-v8-2026-09-09-135700.json" in folder.remaining, folder.remaining.toString())
        }

    @Test
    fun `a file whose contents do not show it is ours is left where it is`() =
        runBlocking<Unit> {
            val folder = FakeBackupDirectory()
            (1..3).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            // Somebody's own file, put here under a name that happens to match.
            folder.put(importSnapshotFileName(momentAt(0)), header = "benim notlarım".encodeToByteArray())
            // And one that could not be read at all.
            folder.put("pnp-oncesi-2026-09-09-135500.json", header = ByteArray(0))

            AutomaticBackupRotation(folder).rotateAfter(importSnapshotFileName(momentAt(3)).removeSuffix(".json"), keep = 1)

            assertTrue(importSnapshotFileName(momentAt(0)) in folder.remaining, folder.remaining.toString())
            assertTrue("pnp-oncesi-2026-09-09-135500.json" in folder.remaining, folder.remaining.toString())
            assertFalse(importSnapshotFileName(momentAt(0)) in folder.removalsAttempted)
        }

    @Test
    fun `what is not a plain file is never removed, however it is named`() =
        runBlocking<Unit> {
            // A symbolic link is the case this is for: one pointing at the
            // user's database, under a name rotation would otherwise clear.
            val folder = FakeBackupDirectory()
            (1..3).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            folder.put(importSnapshotFileName(momentAt(0)), ordinaryFile = false)

            AutomaticBackupRotation(folder).rotateAfter(importSnapshotFileName(momentAt(3)).removeSuffix(".json"), keep = 1)

            assertTrue(importSnapshotFileName(momentAt(0)) in folder.remaining)
            assertEquals(emptyList(), folder.removalsAttempted.filter { it == importSnapshotFileName(momentAt(0)) })
        }

    @Test
    fun `nothing is removed until the new backup is there to be found`() =
        runBlocking<Unit> {
            // The structural half of "no old file goes before the new one is
            // written and verified" (PLAN 14.4.11). A caller with nothing
            // written has no set name to give, and an invented one clears
            // nothing.
            val folder = FakeBackupDirectory()
            (1..5).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            val before = folder.remaining

            val outcome = AutomaticBackupRotation(folder).rotateAfter("pnp-otomatik-import-2026-09-09-235959", keep = 1)

            assertTrue(outcome.refused)
            assertEquals(emptyList(), outcome.removed)
            assertEquals(emptyList(), folder.removalsAttempted)
            assertEquals(before, folder.remaining)
        }

    @Test
    fun `a new backup that cannot be shown to be ours clears nothing either`() =
        runBlocking<Unit> {
            val folder = FakeBackupDirectory()
            (1..5).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            folder.put(importSnapshotFileName(momentAt(9)), header = "yarım yazılmış".encodeToByteArray())

            val outcome = AutomaticBackupRotation(folder).rotateAfter(importSnapshotFileName(momentAt(9)).removeSuffix(".json"), keep = 1)

            assertTrue(outcome.refused)
            assertEquals(emptyList(), folder.removalsAttempted)
        }

    @Test
    fun `a file that will not go away is reported and stops nothing`() =
        runBlocking<Unit> {
            // PLAN 14.4.13: an old backup that cannot be deleted puts nobody's
            // data at risk, so it must not fail the import or migration that has
            // just been protected.
            val folder = FakeBackupDirectory()
            (1..4).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            folder.refuseToRemove = setOf(importSnapshotFileName(momentAt(1)))

            val outcome = AutomaticBackupRotation(folder).rotateAfter(importSnapshotFileName(momentAt(4)).removeSuffix(".json"), keep = 2)

            assertEquals(listOf(importSnapshotFileName(momentAt(1))), outcome.couldNotRemove)
            assertEquals(listOf(importSnapshotFileName(momentAt(2))), outcome.removed)
            assertFalse(outcome.refused)
            // And the stubborn file is still there, still countable, still
            // reachable by the next pass.
            assertTrue(importSnapshotFileName(momentAt(1)) in folder.remaining)
        }

    @Test
    fun `a pass that was interrupted is finished by the next one`() =
        runBlocking<Unit> {
            val folder = FakeBackupDirectory()
            (1..6).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            val newest = importSnapshotFileName(momentAt(6)).removeSuffix(".json")
            val rotation = AutomaticBackupRotation(folder)
            folder.refuseToRemove = setOf(importSnapshotFileName(momentAt(1)), importSnapshotFileName(momentAt(2)))

            val first = rotation.rotateAfter(newest, keep = 2)
            assertEquals(2, first.couldNotRemove.size)

            // Nothing was written down between the two, so the second pass reads
            // the folder again and simply sees what is still over the count.
            folder.refuseToRemove = emptySet()
            val second = rotation.rotateAfter(newest, keep = 2)

            assertEquals(
                listOf(importSnapshotFileName(momentAt(1)), importSnapshotFileName(momentAt(2))).sorted(),
                second.removed.sorted(),
            )
            assertEquals(2, folder.remaining.size, folder.remaining.toString())
        }

    @Test
    fun `running it twice over a settled folder changes nothing the second time`() =
        runBlocking<Unit> {
            val folder = FakeBackupDirectory()
            (1..5).forEach { folder.put(importSnapshotFileName(momentAt(it))) }
            val newest = importSnapshotFileName(momentAt(5)).removeSuffix(".json")
            val rotation = AutomaticBackupRotation(folder)

            rotation.rotateAfter(newest, keep = 3)
            val settled = folder.remaining
            val again = rotation.rotateAfter(newest, keep = 3)

            assertEquals(emptyList(), again.removed)
            assertEquals(settled, folder.remaining)
        }

    @Test
    fun `ordinals are compared as numbers, so the tenth is newer than the second`() =
        runBlocking<Unit> {
            // Sorted as text, `-10` would come before `-2` and rotation would
            // throw away the newest file of the second (PLAN 14.4.11).
            val folder = FakeBackupDirectory()
            val moment = momentAt(33, 55)
            listOf(1, 2, 10).forEach { folder.put(importSnapshotFileName(moment, attempt = it)) }

            AutomaticBackupRotation(folder).rotateAfter(importSnapshotFileName(moment, attempt = 10).removeSuffix(".json"), keep = 1)

            assertEquals(listOf(importSnapshotFileName(moment, attempt = 10)), folder.remaining)
        }

    @Test
    fun `order comes from the name, whatever order the folder lists things in`() =
        runBlocking<Unit> {
            val forwards = FakeBackupDirectory()
            (1..5).forEach { forwards.put(importSnapshotFileName(momentAt(it))) }
            val backwards = FakeBackupDirectory()
            (5 downTo 1).forEach { backwards.put(importSnapshotFileName(momentAt(it))) }
            val newest = importSnapshotFileName(momentAt(5)).removeSuffix(".json")

            AutomaticBackupRotation(forwards).rotateAfter(newest, keep = 2)
            AutomaticBackupRotation(backwards).rotateAfter(newest, keep = 2)

            assertEquals(forwards.remaining, backwards.remaining)
            assertEquals(listOf(importSnapshotFileName(momentAt(4)), importSnapshotFileName(momentAt(5))).sorted(), forwards.remaining)
        }

    @Test
    fun `keeping fewer than one or more than fifty is not retention`() =
        runBlocking<Unit> {
            val rotation = AutomaticBackupRotation(FakeBackupDirectory())

            assertFailsWith<IllegalArgumentException> { rotation.rotateAfter("pnp-otomatik-import-2026-09-09-143355", keep = 0) }
            assertFailsWith<IllegalArgumentException> { rotation.rotateAfter("pnp-otomatik-import-2026-09-09-143355", keep = -1) }
            assertFailsWith<IllegalArgumentException> { rotation.rotateAfter("pnp-otomatik-import-2026-09-09-143355", keep = 51) }
        }

    @Test
    fun `the counts this keeps are the ones the plan names`() {
        assertEquals(1, MINIMUM_AUTOMATIC_BACKUPS)
        assertEquals(50, MAXIMUM_AUTOMATIC_BACKUPS)
        assertEquals(7, DEFAULT_AUTOMATIC_BACKUPS)
    }
}
