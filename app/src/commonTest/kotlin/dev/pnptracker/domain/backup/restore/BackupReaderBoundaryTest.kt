package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BackupData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A probe that goes wrong in a way no backup could have caused. */
private class ThrowingProbe(
    private val thrown: () -> Nothing,
) : BackupProbe {
    override suspend fun probe(
        data: BackupData,
        dataSha256: String,
    ): BackupRejection? = thrown()
}

/**
 * The line between "this backup is wrong" and "this code is wrong".
 *
 * PLAN 14.4.5 draws it and will not have it blurred: a file that says something
 * impossible becomes a typed refusal, and a programming mistake stays a
 * programming mistake. The temptation to catch everything on a path like this is
 * strong, because a crash while reading a file looks like the file's fault — and
 * giving in to it would turn every future bug in the validator into a message
 * telling the user their backup is corrupt.
 *
 * The other half of the boundary is what a refusal carries. It gets shown, and it
 * may get logged, so it must hold nothing out of the file: no path, no
 * identifier, no name, no note, no SQL, no exception text.
 */
class BackupReaderBoundaryTest {
    @Test
    fun `an invariant failure inside the probe is not dressed up as a corrupt backup`() =
        runBlocking<Unit> {
            val reader = UntrustedBackupReader(ThrowingProbe { error("the probe has a bug") })

            val thrown = assertFailsWith<IllegalStateException> { reader.read(fileOf(documentOf())) }
            assertEquals("the probe has a bug", thrown.message)
        }

    @Test
    fun `a missing value inside the probe is not dressed up either`() =
        runBlocking<Unit> {
            val reader = UntrustedBackupReader(ThrowingProbe { throw NullPointerException("nothing there") })

            assertFailsWith<NullPointerException> { reader.read(fileOf(documentOf())) }
        }

    @Test
    fun `something that is not an exception at all is left alone`() =
        runBlocking<Unit> {
            // Nothing on this path catches `Throwable`, and this is what says so.
            val reader = UntrustedBackupReader(ThrowingProbe { throw Error("out of everything") })

            assertFailsWith<Error> { reader.read(fileOf(documentOf())) }
        }

    @Test
    fun `what the probe refuses is what the reader answers`() =
        runBlocking<Unit> {
            val rejection = BackupRejection(BackupProblem.TEMP_VALIDATION_FAILED, BackupPlace("data"))
            val reader = UntrustedBackupReader(CountingProbe(rejection))

            val result = reader.read(fileOf(documentOf()))

            assertEquals(rejection, (result as BackupReadResult.Refused).rejection)
        }

    @Test
    fun `a refusal carries nothing out of the file`() =
        runBlocking<Unit> {
            val reader = UntrustedBackupReader(CountingProbe())
            val secrets = listOf(COLOR, GAME, TASK_PRINTED, "Harmonies", "Kırmızı", "oyunlar.xlsx", "kenarları bozuldu")

            val documents =
                listOf(
                    documentOf().replaceFirst("\"pnp-tracker-backup\"", "\"other\""),
                    documentOf().replaceFirst("\"Harmonies\"", "\"Harmonjes\""),
                    documentOf().replaceFirst("{", "{\"Harmonies-alanı\":1,"),
                    documentOf().replaceFirst("\"canonicalName\":\"Kırmızı\"", "\"canonicalName\":\"  \""),
                    documentOf().replaceFirst(",\"sortOrder\":0", ""),
                    documentOf().dropLast(40),
                )

            documents.forEach { document ->
                val result = reader.read(fileOf(document))
                val rejection = (result as BackupReadResult.Refused).rejection
                val written = rejection.toString()
                secrets.forEach { secret ->
                    assertFalse(secret in written, "a refusal carried '$secret': $written")
                }
                assertFalse("SELECT" in written || "INSERT" in written, written)
                assertFalse("/" in written, "a refusal carried something path shaped: $written")
            }
        }

    @Test
    fun `every problem there is can be told apart from every other`() {
        // The point of a typed refusal is that a later slice can choose a Turkish
        // sentence from it. Two problems sharing a name would make one of those
        // sentences unreachable.
        val names = BackupProblem.entries.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(BackupProblem.entries.size >= 20, "the problem list has shrunk: ${names.size}")
    }
}
