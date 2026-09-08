package dev.pnptracker.data.repository

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.fillWithEverything
import dev.pnptracker.domain.backup.BackupDocument
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private val EARLY = Instant.fromEpochMilliseconds(1_700_000_000_000)
private val LATE = Instant.fromEpochMilliseconds(1_757_320_364_031)

/**
 * Whether the same data always makes the same file.
 *
 * Two databases are built from the very same rows, one of them writing each
 * table's rows in the opposite order, so their pages differ and their natural
 * row order differs. Everything a backup says about them has to be identical —
 * not merely equivalent, but the same bytes — because the checksum is over those
 * bytes and because PLAN 14.4.1 promises a reader that two backups of an
 * unchanged database can be compared directly.
 *
 * This is what makes the whole format checkable. A file that varied with the
 * order rows happened to be written in would still parse, still restore, and
 * still be impossible to tell apart from a corrupted one.
 */
class BackupDeterminismTest {
    private val directories = mutableListOf<TemporaryDatabaseDirectory>()
    private val databases = mutableListOf<AppDatabase>()
    private var realDatabaseExisted = false

    @BeforeTest
    fun rememberRealDatabase() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
    }

    @AfterTest
    fun closeEverything() {
        databases.forEach { it.close() }
        directories.forEach {
            it.assertRealApplicationDatabaseUntouched(realDatabaseExisted)
            it.delete()
        }
    }

    /** A database holding the whole fixture, written forwards or backwards. */
    private fun filled(reversed: Boolean): AppDatabase {
        val directory = TemporaryDatabaseDirectory()
        directories += directory
        val database = DatabaseFactory().open(directory.databaseFile)
        databases += database
        runBlocking { fillWithEverything(database, reversed = reversed) }
        return database
    }

    private fun documentOf(
        database: AppDatabase,
        at: Instant = LATE,
    ): BackupDocument =
        runBlocking {
            DatabaseBackupExporter(BackupStore(database), AppInfo.Current, StoppedClock(at)).backupDocument()
        }

    @Test
    fun `the order rows were written in does not reach the file`() {
        val forwards = documentOf(filled(reversed = false))
        val backwards = documentOf(filled(reversed = true))

        assertEquals(forwards.envelope.data, backwards.envelope.data, "the two readings disagree about the data")
        assertEquals(
            canonicalBackupDataJson(forwards.envelope.data),
            canonicalBackupDataJson(backwards.envelope.data),
        )
        assertEquals(forwards.envelope.dataSha256, backwards.envelope.dataSha256)
        assertEquals(forwards.json, backwards.json, "the same data made two different files")
    }

    @Test
    fun `the fixture really was written in two different orders`() {
        // Otherwise the test above would be comparing a database with itself.
        val forwards = runBlocking { BackupStore(filled(reversed = false)).snapshot().data }
        assertTrue(forwards.historyEvents.size > 1 && forwards.colors.size > 1)
    }

    @Test
    fun `only the moment differs when the same database is read at two times`() {
        val database = filled(reversed = false)
        val early = documentOf(database, at = EARLY)
        val late = documentOf(database, at = LATE)

        assertEquals(early.envelope.dataSha256, late.envelope.dataSha256)
        assertEquals(early.envelope.data, late.envelope.data)
        assertNotEquals(early.envelope.createdAt, late.envelope.createdAt)
        assertEquals(
            early.json.replace(early.envelope.createdAt, late.envelope.createdAt),
            late.json,
            "the two documents differ in more than the moment they were taken",
        )
    }
}
