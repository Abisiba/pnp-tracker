package dev.pnptracker.domain.backup.automatic

import dev.pnptracker.AppInfo
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.restore.CountingProbe
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.restore.aWholeBackup
import dev.pnptracker.domain.backup.restore.anEmptyBackup
import dev.pnptracker.domain.backup.restore.canonicalChecksumOf
import dev.pnptracker.domain.backup.restore.documentOf
import dev.pnptracker.domain.backup.retention.AutomaticBackupKind
import dev.pnptracker.domain.backup.retention.automaticBackupNameOf
import dev.pnptracker.ui.feature.settings.FakeBackupSource
import dev.pnptracker.ui.feature.settings.StoppedRestoreClock
import dev.pnptracker.ui.feature.settings.storageRefusal
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Taking the backup that stands in front of an import.
 *
 * Everything here is about the third step. Reading the database and writing a
 * file are ordinary work with ordinary failures; what this class exists for is
 * the promise that nothing is called a backup until it has been read back off
 * the disk by the very reader a person's own file goes through (PLAN 14.4.7).
 * So the reader below is the real [UntrustedBackupReader], not a stand in —
 * only its throwaway database is, because what a temporary database makes of
 * these rows is that probe's own test.
 */
class VerifiedSnapshotTakerTest {
    private fun taker(
        source: FakeBackupSource = FakeBackupSource(),
        writer: AutomaticSnapshotWriter = FakeSnapshotWriter(),
    ) = VerifiedSnapshotTaker(
        exporter = DatabaseBackupExporter(source, AppInfo.Current, StoppedRestoreClock()),
        writer = writer,
        reader = UntrustedBackupReader(CountingProbe()),
        clock = StoppedRestoreClock(),
    )

    @Test
    fun `the snapshot carries the rows that were written, and the name they went under`() =
        runBlocking<Unit> {
            val data = aWholeBackup()
            val writer = FakeSnapshotWriter()

            val snapshot = taker(FakeBackupSource(data), writer).takeBeforeImport()

            assertEquals(data, snapshot.data)
            assertEquals(canonicalChecksumOf(data), snapshot.dataSha256)
            assertEquals(A_SNAPSHOT_NAME, snapshot.fileName)
            assertEquals(listOf(A_SNAPSHOT_NAME), writer.written)
        }

    @Test
    fun `a database with nothing in it is still backed up`() =
        runBlocking<Unit> {
            // PLAN 14.4.8 has no threshold of any kind. The smallest import
            // there is happens on the emptiest database there is, and it is
            // backed up like every other.
            val snapshot = taker(FakeBackupSource(anEmptyBackup())).takeBeforeImport()

            assertEquals(anEmptyBackup(), snapshot.data)
        }

    @Test
    fun `a database that will not be read is a snapshot that did not happen`() =
        runBlocking<Unit> {
            val writer = FakeSnapshotWriter()

            val notTaken =
                assertFailsWith<SnapshotNotTaken> {
                    taker(FakeBackupSource(refusal = ::storageRefusal), writer).takeBeforeImport()
                }

            assertEquals(SnapshotProblem.DATABASE_NOT_READ, notTaken.problem)
            assertEquals(emptyList(), writer.written, "a file was written for a database that was never read")
        }

    @Test
    fun `a folder that will not take the file is a snapshot that did not happen`() =
        runBlocking<Unit> {
            BackupFailure.entries.forEach { refusal ->
                val notTaken =
                    assertFailsWith<SnapshotNotTaken>("$refusal") {
                        taker(writer = FakeSnapshotWriter(refuseWith = refusal)).takeBeforeImport()
                    }

                assertEquals(SnapshotProblem.NOT_WRITTEN, notTaken.problem, "$refusal")
            }
        }

    @Test
    fun `a file that does not read back as a backup is not a backup`() =
        runBlocking<Unit> {
            // Four ways for a file to be on disk and be no use: empty, not
            // JSON, not our envelope, and a backup whose checksum does not
            // cover what it holds. The reader refuses each of them, and this
            // refuses to call any of them a snapshot.
            val ruined =
                listOf(
                    "",
                    "bu bir yedek değil",
                    """{"format":"başka-bir-şey","formatVersion":1}""",
                    documentOf(aWholeBackup()).replace(canonicalChecksumOf(aWholeBackup()), "0".repeat(64)),
                )

            ruined.forEach { text ->
                val notTaken =
                    assertFailsWith<SnapshotNotTaken>(text.take(20)) {
                        taker(writer = FakeSnapshotWriter(corruptWith = text)).takeBeforeImport()
                    }

                assertEquals(SnapshotProblem.NOT_VERIFIED, notTaken.problem, text.take(20))
            }
        }

    @Test
    fun `a file that is a backup of something else is refused`() =
        runBlocking<Unit> {
            // The case the reader alone cannot see: a perfectly good backup
            // document, which is not the one that was just written. A write
            // that landed somewhere else, or a name that turned out to belong
            // to another file, looks exactly like this.
            val somebodyElses = documentOf(anEmptyBackup())

            val notTaken =
                assertFailsWith<SnapshotNotTaken> {
                    taker(FakeBackupSource(aWholeBackup()), FakeSnapshotWriter(corruptWith = somebodyElses))
                        .takeBeforeImport()
                }

            assertEquals(SnapshotProblem.NOT_VERIFIED, notTaken.problem)
        }

    @Test
    fun `the name it writes under is one rotation recognises, and carries no data`() =
        runBlocking<Unit> {
            val writer = NamingSnapshotWriter()

            val snapshot = taker(writer = writer).takeBeforeImport()

            assertEquals(listOf(snapshot.fileName), writer.written)
            // Not merely the right prefix: the strict reader of PLAN 14.4.11,
            // which is what decides whether housekeeping can ever see this file
            // again. A name it does not recognise is a snapshot that would
            // accumulate for ever.
            val read = assertNotNull(automaticBackupNameOf(snapshot.fileName), snapshot.fileName)
            assertEquals(AutomaticBackupKind.IMPORT, read.kind)
            // PLAN 14.4.7: nothing of anybody's is in the name — not a game, not
            // a file they chose, not this machine.
            assertTrue(snapshot.fileName.none { it == '/' || it == '\\' }, snapshot.fileName)
        }

    @Test
    fun `a defect on the way through is not dressed up as a failed backup`() =
        runBlocking<Unit> {
            // PLAN 14.4.5: only known storage and format failures become
            // something a user is told about. A broken invariant is a defect,
            // and one hidden behind "the backup could not be taken" is a defect
            // nobody will ever find.
            val broken = FakeBackupSource(refusal = { IllegalStateException("an invariant this code got wrong") })

            val thrown = assertFailsWith<IllegalStateException> { taker(broken).takeBeforeImport() }

            assertEquals("an invariant this code got wrong", thrown.message)
        }
}
