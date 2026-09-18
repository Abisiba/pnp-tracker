package dev.pnptracker.ui.feature.settings

import dev.pnptracker.AppInfo
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.restore.BackupProblem
import dev.pnptracker.domain.backup.restore.CountingProbe
import dev.pnptracker.domain.backup.restore.RestoreProblem
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.restore.aRestorableBackup
import dev.pnptracker.domain.backup.restore.canonicalChecksumOf
import dev.pnptracker.domain.backup.restore.documentOf
import dev.pnptracker.domain.backup.restore.fileOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The order of the restore, which is the whole of its safety.
 *
 * Almost every test here is about something *not* happening. That is the shape of
 * the feature: the destructive question is not asked until the file has passed;
 * the database is not read until the question is answered; the transaction is not
 * started until the safety backup is on disk. Each of those is a step somebody
 * could remove and still have a working restore, and each of them is the reason
 * this one cannot lose anybody's data.
 *
 * The reader is real throughout. A double that said "this file is fine" would
 * make every test here pass over a flow that never checks anything.
 */
class RestoreControllerTest {
    @Test
    fun `changing one's mind at the dialog is not a failure and reads nothing`() =
        runBlocking<Unit> {
            val gateway = FakeSourceGateway(chosen = null)
            val source = FakeBackupSource()
            val safety = FakeSafetyWriter()
            val controller = aRestoreController(gateway, safety = safety, source = source)

            controller.chooseBackup()

            assertEquals(RestoreScreenState.Idle, controller.state)
            assertEquals(0, source.reads, "the database was read for a restore nobody asked for")
            assertEquals(0, safety.writes)
        }

    @Test
    fun `a backup that passes every check ends in a question and nothing else`() =
        runBlocking<Unit> {
            val source = FakeBackupSource()
            val safety = FakeSafetyWriter()
            val restorer = FakeRestorer()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer, source)

            controller.chooseBackup()

            val asking = controller.state as RestoreScreenState.Confirming
            assertEquals("pnp-yedek-2026-09-08.json", asking.summary.fileName)
            assertEquals(1, asking.summary.gameCount)
            assertEquals(2, asking.summary.taskCount)
            // PLAN 12.16: not a byte is read from the database, and not a byte
            // written anywhere, until the question has been answered.
            assertEquals(0, source.reads)
            assertEquals(0, safety.writes)
            assertEquals(0, restorer.applied)
        }

    @Test
    fun `a file that does not pass never becomes a question`() =
        runBlocking<Unit> {
            // One example from each family the reader can refuse for, so the
            // controller is shown to carry the reason through rather than to
            // flatten it: a file that is not JSON, one whose checksum no longer
            // describes it, and one from a version this build does not know.
            val refusals =
                listOf(
                    fileOf("{") to BackupProblem.MALFORMED_JSON,
                    fileOf(documentOf().replaceFirst("\"Harmonies\"", "\"Harmonjes\"")) to BackupProblem.CHECKSUM_MISMATCH,
                    fileOf(documentOf().replaceFirst("\"formatVersion\":1", "\"formatVersion\":2")) to BackupProblem.FORMAT_TOO_NEW,
                )
            refusals.forEach { (file, expected) ->
                val source = FakeBackupSource()
                val safety = FakeSafetyWriter()
                val restorer = FakeRestorer()
                val controller = aRestoreController(FakeSourceGateway(file), safety, restorer, source)

                controller.chooseBackup()

                assertEquals(RestoreScreenState.Rejected(expected), controller.state)
                assertEquals(0, source.reads, "a refused file still had the database read for it")
                assertEquals(0, safety.writes)
                assertEquals(0, restorer.applied)
            }
        }

    @Test
    fun `another file can be chosen after one was refused`() =
        runBlocking<Unit> {
            val controller = aRestoreController(FakeSourceGateway(fileOf("{")))
            controller.chooseBackup()
            assertTrue(controller.state is RestoreScreenState.Rejected)

            val second = aRestoreController(FakeSourceGateway(aRealBackupFile()))
            second.chooseBackup()
            assertTrue(second.state is RestoreScreenState.Confirming)
        }

    @Test
    fun `backing out of the question writes nothing anywhere`() =
        runBlocking<Unit> {
            val source = FakeBackupSource()
            val safety = FakeSafetyWriter()
            val restorer = FakeRestorer()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer, source)
            controller.chooseBackup()

            controller.cancelRestore()

            assertEquals(RestoreScreenState.Idle, controller.state)
            assertEquals(0, safety.writes, "backing out made a safety backup")
            assertEquals(0, restorer.applied)
            assertEquals(0, source.reads)

            // And the agreement is gone with it: a later confirmation has nothing
            // left to apply.
            controller.confirmRestore()
            assertEquals(0, restorer.applied)
        }

    @Test
    fun `agreeing saves a way back before it replaces anything`() =
        runBlocking<Unit> {
            val data = aRestorableBackup()
            val source = FakeBackupSource(data)
            val safety = FakeSafetyWriter()
            val restorer = FakeRestorer()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer, source)
            controller.chooseBackup()

            controller.confirmRestore()

            val done = controller.state as RestoreScreenState.Restored
            assertEquals("pnp-yedek-2026-09-08.json", done.fileName)
            assertEquals("pnp-oncesi-2026-09-09-090924.json", done.safetyFileName)
            assertEquals(1, safety.writes)
            assertEquals(1, restorer.applied)
            assertEquals(1, controller.restoredTick)

            // The safety file holds what the database held, in the format a
            // manual backup uses, and the transaction was told the same thing so
            // it can refuse a database that moved underneath it.
            assertTrue(safety.lastBytes!!.decodeToString().contains("\"pnp-tracker-backup\""))
            assertEquals(data, restorer.lastSnapshot?.data)
            assertEquals(canonicalChecksumOf(data), restorer.lastSnapshot?.dataSha256)
        }

    @Test
    fun `a safety backup that cannot be made stops the restore before it starts`() =
        runBlocking<Unit> {
            // The database itself will not answer, so there is nothing to write.
            val source = FakeBackupSource(refusal = { storageRefusal() })
            val restorer = FakeRestorer()
            val safety = FakeSafetyWriter()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer, source)
            controller.chooseBackup()

            controller.confirmRestore()

            assertEquals(RestoreScreenState.Failed(RestoreProblem.SAFETY_BACKUP_NOT_MADE, null), controller.state)
            assertEquals(0, safety.writes)
            assertEquals(0, restorer.applied, "the transaction ran without a way back from it")
            assertEquals(0, controller.restoredTick)
        }

    @Test
    fun `a safety backup that cannot be written stops the restore before it starts`() =
        runBlocking<Unit> {
            val restorer = FakeRestorer()
            val safety = FakeSafetyWriter(refuse = BackupFailure.NOT_WRITABLE)
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
            controller.chooseBackup()

            controller.confirmRestore()

            // PLAN 14.4.4: carrying on here would take away the user's way back
            // without telling them.
            assertEquals(RestoreScreenState.Failed(RestoreProblem.SAFETY_BACKUP_NOT_WRITTEN, null), controller.state)
            assertEquals(0, restorer.applied)
        }

    @Test
    fun `a transaction that refuses leaves the safety backup named on the screen`() =
        runBlocking<Unit> {
            val safety = FakeSafetyWriter()
            val restorer = FakeRestorer(RestoreProblem.DATA_CHANGED_MEANWHILE)
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
            controller.chooseBackup()

            controller.confirmRestore()

            val failed = controller.state as RestoreScreenState.Failed
            assertEquals(RestoreProblem.DATA_CHANGED_MEANWHILE, failed.problem)
            // The file was written and is kept: it is the way back, and the
            // screen has to be able to name it.
            assertEquals("pnp-oncesi-2026-09-09-090924.json", failed.safetyFileName)
            assertEquals(1, safety.writes)
            assertEquals(0, controller.restoredTick, "a refused restore told the screens their data had changed")
        }

    @Test
    fun `a second agreement while the first is running does nothing at all`() =
        runBlocking<Unit> {
            val safety = FakeSafetyWriter()
            safety.gate = CompletableDeferred()
            val restorer = FakeRestorer()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
            controller.chooseBackup()

            coroutineScope {
                val first = async { controller.confirmRestore() }
                // Let the first press get as far as the safety backup and stop
                // there, which is where a double click does the damage: one press
                // committed and a second arriving before it has finished.
                yield()
                repeat(3) { controller.confirmRestore() }
                assertTrue(controller.state.isUnstoppable, "the flow was still stoppable after it had been agreed to")
                safety.gate?.complete(Unit)
                first.await()
            }

            assertEquals(1, safety.writes, "one agreement made more than one safety backup")
            assertEquals(1, restorer.applied, "one agreement ran more than one transaction")
        }

    @Test
    fun `a second dialog cannot be opened while one is on screen`() =
        runBlocking<Unit> {
            val gateway = FakeSourceGateway(aRealBackupFile())
            gateway.gate = CompletableDeferred()
            val controller = aRestoreController(gateway)

            coroutineScope {
                val first = async { controller.chooseBackup() }
                yield()
                repeat(3) { controller.chooseBackup() }
                assertEquals(1, gateway.asked, "a repeated press opened the dialog more than once")
                gateway.gate?.complete(Unit)
                first.await()
            }
        }

    @Test
    fun `the button behind an unanswered question does nothing`() =
        runBlocking<Unit> {
            val gateway = FakeSourceGateway(aRealBackupFile())
            val controller = aRestoreController(gateway)
            controller.chooseBackup()
            assertEquals(1, gateway.asked)

            controller.chooseBackup()

            assertEquals(1, gateway.asked, "the question was overtaken by a second dialog")
            assertTrue(controller.state is RestoreScreenState.Confirming)
        }

    @Test
    fun `an agreement belongs to the file it was asked about`() =
        runBlocking<Unit> {
            val restorer = FakeRestorer()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), restorer = restorer)
            controller.chooseBackup()
            val stale = controller.state as RestoreScreenState.Confirming

            // The user chooses again, and the question is now about the second
            // reading. The token the first one carried is spent.
            controller.cancelRestore()
            controller.chooseBackup()
            assertTrue((controller.state as RestoreScreenState.Confirming).token != stale.token)

            // Answering the old question — a stale event, a duplicated click —
            // applies to nothing.
            val fresh = controller.state
            controller.confirmRestore()
            assertEquals(1, restorer.applied)
            assertTrue(fresh is RestoreScreenState.Confirming)
        }

    @Test
    fun `the keyboard is called back whenever a surface closes`() =
        runBlocking<Unit> {
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()))
            assertEquals(0, controller.focusRecall)

            controller.chooseBackup()
            // A question is opening rather than closing, so nothing is recalled yet.
            assertEquals(0, controller.focusRecall)
            controller.cancelRestore()
            assertEquals(1, controller.focusRecall)

            controller.chooseBackup()
            controller.confirmRestore()
            assertEquals(2, controller.focusRecall)
        }

    @Test
    fun `another restore can be started after one has finished`() =
        runBlocking<Unit> {
            val restorer = FakeRestorer()
            val safety = FakeSafetyWriter()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer)
            controller.chooseBackup()
            controller.confirmRestore()

            controller.startOver()
            controller.chooseBackup()
            controller.confirmRestore()

            assertEquals(2, restorer.applied)
            assertEquals(2, safety.writes, "the second restore reused the first one's safety backup")
            assertEquals(2, controller.restoredTick)
        }

    @Test
    fun `no state ever carries a path or anything out of the data`() =
        runBlocking<Unit> {
            // Every state the flow can reach, collected as it goes and then read
            // for things that must never be in one. The file name is allowed and
            // is the only thing here that came from outside.
            val seen = mutableListOf<RestoreScreenState>()
            val restorer = FakeRestorer(RestoreProblem.COULD_NOT_APPLY)
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), restorer = restorer)
            seen += controller.state
            controller.chooseBackup()
            seen += controller.state
            controller.confirmRestore()
            seen += controller.state

            val text = seen.joinToString(" | ") { it.toString() }
            listOf(
                "Harmonies",
                "Kırmızı",
                "oyunlar.xlsx",
                COLOR_ID_PREFIX,
                "/home/",
                "SELECT",
                "INSERT",
            ).forEach { forbidden ->
                assertTrue(forbidden !in text, "a restore state carried '$forbidden': $text")
            }
        }

    @Test
    fun `a dialog that answers with nothing usable is refused rather than believed`() =
        runBlocking<Unit> {
            val restorer = FakeRestorer()
            val gateway = FakeSourceGateway(chosen = null, refuse = BackupFailure.NO_DESTINATION)
            val controller = aRestoreController(gateway, restorer = restorer)

            controller.chooseBackup()

            assertEquals(RestoreScreenState.Rejected(BackupProblem.UNREADABLE), controller.state)
            assertEquals(0, restorer.applied)
        }

    @Test
    fun `startOver is refused while the restore is running`() =
        runBlocking<Unit> {
            val safety = FakeSafetyWriter()
            safety.gate = CompletableDeferred()
            val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety = safety)
            controller.chooseBackup()

            coroutineScope {
                val running = async { controller.confirmRestore() }
                yield()
                controller.startOver()
                assertNull(
                    controller.state as? RestoreScreenState.Idle,
                    "the flow was thrown away while the safety backup was being written",
                )
                safety.gate?.complete(Unit)
                running.await()
            }
        }

    @Test
    fun `a defect on the way through is not dressed up as a bad backup`() =
        runBlocking<Unit> {
            // PLAN 14.4.5: only known file, format and storage failures become
            // something the user is told about. A broken invariant of this
            // application's own is a defect, and a defect hidden behind "the
            // backup could not be applied" is a defect nobody will ever find.
            val raised =
                listOf<() -> Throwable>(
                    { IllegalStateException("an invariant this code got wrong") },
                    { NullPointerException("something that should never be null") },
                )
            raised.forEach { make ->
                val safety = FakeSafetyWriter()
                val controller = aRestoreController(FakeSourceGateway(aRealBackupFile()), safety, restorer = FakeRestorer())
                val broken =
                    RestoreController(
                        sources = FakeSourceGateway(aRealBackupFile()),
                        reader = UntrustedBackupReader(CountingProbe()),
                        exporter = DatabaseBackupExporter(FakeBackupSource(), AppInfo.Current, StoppedRestoreClock()),
                        safety = safety,
                        restorer = BrokenRestorer(make),
                        housekeeping = RecordingHousekeeping(),
                        clock = StoppedRestoreClock(),
                    )
                broken.chooseBackup()

                val thrown = assertFails { broken.confirmRestore() }

                assertEquals(make().message, thrown.message, "the defect was replaced by something else")
                assertTrue(controller.state is RestoreScreenState.Idle)
            }
        }

    @Test
    fun `a fatal error is never caught`() =
        runBlocking<Unit> {
            val controller =
                RestoreController(
                    sources = FakeSourceGateway(aRealBackupFile()),
                    reader = UntrustedBackupReader(CountingProbe()),
                    exporter = DatabaseBackupExporter(FakeBackupSource(), AppInfo.Current, StoppedRestoreClock()),
                    safety = FakeSafetyWriter(),
                    restorer = BrokenRestorer { StackOverflowError("out of room") },
                    housekeeping = RecordingHousekeeping(),
                    clock = StoppedRestoreClock(),
                )
            controller.chooseBackup()

            assertFailsWith<StackOverflowError> { controller.confirmRestore() }
        }

    private companion object {
        /** The start of every identifier the fixture uses; none of them may reach a state. */
        const val COLOR_ID_PREFIX = "aa000000-0000-4000-8000"
    }
}
