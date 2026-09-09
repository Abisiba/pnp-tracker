package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.backupDocumentOf
import dev.pnptracker.domain.backup.backupJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the file says about itself, and whether the checksum bears it out.
 *
 * The two versions are separate questions and the answers are kept separate:
 * PLAN 14.4.1 has the format version and the schema version move independently,
 * and a person told the wrong one of them goes looking for the wrong remedy.
 *
 * The checksum tests are the ones worth reading twice. A checksum over the
 * *canonical* form of the data means two files that say the same thing agree even
 * if they were written differently, and a file that says something different
 * disagrees however small the difference is. Both halves of that are tested,
 * because a checksum that only ever agreed would be a decoration.
 */
class BackupEnvelopeTest {
    private val probe = CountingProbe()
    private val reader = UntrustedBackupReader(probe)

    @Test
    fun `a document that does not name this format is refused`() =
        runBlocking<Unit> {
            val other = documentOf().replaceFirst("\"pnp-tracker-backup\"", "\"some-other-backup\"")
            assertEquals(BackupProblem.WRONG_FORMAT, refusalOf(other))
            assertEquals(BackupPlace("envelope", "format"), placeOf(other))
        }

    @Test
    fun `a format version this build does not know is refused, and which way it is wrong is said`() =
        runBlocking<Unit> {
            val newer = documentOf().replaceFirst("\"formatVersion\":1", "\"formatVersion\":2")
            assertEquals(BackupProblem.FORMAT_TOO_NEW, refusalOf(newer))

            // PLAN 14.4.1: there is no upgrade chain in the first version, so a
            // version below the first is refused rather than guessed at.
            val older = documentOf().replaceFirst("\"formatVersion\":1", "\"formatVersion\":0")
            assertEquals(BackupProblem.FORMAT_TOO_OLD, refusalOf(older))
        }

    @Test
    fun `rows from a schema this build does not have are refused, and which way is said`() =
        runBlocking<Unit> {
            val newer = documentOf().replaceFirst("\"sourceSchemaVersion\":8", "\"sourceSchemaVersion\":9")
            assertEquals(BackupProblem.SCHEMA_TOO_NEW, refusalOf(newer))

            val older = documentOf().replaceFirst("\"sourceSchemaVersion\":8", "\"sourceSchemaVersion\":7")
            assertEquals(BackupProblem.SCHEMA_TOO_OLD, refusalOf(older))
        }

    @Test
    fun `the version of the application that wrote it never refuses anything`() =
        runBlocking<Unit> {
            // PLAN 14.4.1 says so outright: it is information, not a gate.
            listOf("\"9.9.9\"", "\"0.0.1-alpha\"", "\"\"").forEach { version ->
                val document = documentOf().replaceFirst("\"appVersion\":\"0.1.0\"", "\"appVersion\":$version")
                val result = reader.read(fileOf(document))
                assertTrue(result is BackupReadResult.Valid, "an application version refused a backup: $version")
            }
        }

    @Test
    fun `a moment that is not one, or is not written the way the format writes one, is refused`() =
        runBlocking<Unit> {
            val notAMoment =
                listOf(
                    "dün",
                    "2026-09-08 09:15:00",
                    "2026-13-45T99:99:99Z",
                    "",
                )
            notAMoment.forEach { text ->
                val document = documentOf().replaceFirst("\"$WRITTEN_AT\"", "\"$text\"")
                assertEquals(BackupProblem.INVALID_CREATED_AT, refusalOf(document), "accepted '$text' as a moment")
            }

            // These are real moments written another way. The format is canonical
            // (PLAN 14.4.1), so the same instant has one spelling and these are
            // not it.
            listOf("2026-09-08T12:15:00+03:00", "2026-09-08T09:15:00.000Z").forEach { text ->
                val document = documentOf().replaceFirst("\"$WRITTEN_AT\"", "\"$text\"")
                assertEquals(BackupProblem.INVALID_CREATED_AT, refusalOf(document), "accepted '$text' as canonical")
            }
        }

    @Test
    fun `a checksum that is not shaped like one is refused before it is compared`() =
        runBlocking<Unit> {
            val real = Regex("\"dataSha256\":\"([0-9a-f]{64})\"").find(documentOf())!!.groupValues[1]

            val shapes =
                listOf(
                    real.uppercase(),
                    real.dropLast(1),
                    real + "a",
                    real.dropLast(1) + "z",
                    "",
                )
            shapes.forEach { hash ->
                val document = documentOf().replaceFirst(real, hash)
                assertEquals(BackupProblem.MALFORMED_CHECKSUM, refusalOf(document), "accepted the hash '$hash'")
            }
            assertEquals(0, probe.asked)
        }

    @Test
    fun `one character of the data changed is a checksum that no longer describes it`() =
        runBlocking<Unit> {
            val altered = documentOf().replaceFirst("\"Harmonies\"", "\"Harmonjes\"")

            assertEquals(BackupProblem.CHECKSUM_MISMATCH, refusalOf(altered))
            assertEquals(BackupPlace("envelope", "dataSha256"), placeOf(altered))
        }

    @Test
    fun `nothing is put in a database when the checksum does not match`() =
        runBlocking<Unit> {
            val altered = documentOf().replaceFirst("\"sortOrder\":0", "\"sortOrder\":1")

            assertEquals(BackupProblem.CHECKSUM_MISMATCH, refusalOf(altered))
            assertEquals(0, probe.asked, "a file with a wrong checksum was tried out on a database anyway")
        }

    @Test
    fun `a file whose fields were written in another order still says the same thing`() =
        runBlocking<Unit> {
            // Every object's fields reversed, top to bottom. The checksum covers
            // the canonical form of the data rather than the bytes of the file,
            // so this is the same backup and is accepted as one.
            val reversed = backupJson.encodeToString(JsonElement.serializer(), reversedFields(parsed(documentOf())))
            assertTrue(reversed != documentOf(), "the surgery did not change the file")

            val result = reader.read(fileOf(reversed))

            val read = (result as BackupReadResult.Valid).backup
            assertEquals(
                "Harmonies",
                read.data.games
                    .single()
                    .name,
            )
            assertEquals(1, probe.asked)
        }

    @Test
    fun `two backups of the same data taken at different moments have the same checksum`() =
        runBlocking<Unit> {
            val data = aWholeBackup()
            val early = backupDocumentOf(data, "0.1.0", 8, Instant.fromEpochMilliseconds(1_000_000_000_000))
            val late = backupDocumentOf(data, "9.9.9", 8, moment)

            assertEquals(early.envelope.dataSha256, late.envelope.dataSha256)
            assertTrue(reader.read(fileOf(early.json)) is BackupReadResult.Valid)
            assertTrue(reader.read(fileOf(late.json)) is BackupReadResult.Valid)
        }

    @Test
    fun `what a valid backup says about itself is what the summary reports`() =
        runBlocking<Unit> {
            val result = reader.read(fileOf(documentOf()))

            val summary = (result as BackupReadResult.Valid).backup.summary
            assertEquals("pnp-yedek-2026-09-08.json", summary.fileName)
            assertEquals(WRITTEN_AT, summary.createdAt)
            assertEquals(1, summary.formatVersion)
            assertEquals(8, summary.sourceSchemaVersion)
            assertEquals(1, summary.gameCount)
            assertEquals(2, summary.taskCount)
            assertEquals(1, summary.colorCount)
            assertEquals(1, summary.importCount)
            assertEquals(1, summary.progressEventCount)
            assertEquals(2, summary.historyEventCount)
        }

    private suspend fun refusalOf(text: String): BackupProblem = (reader.read(fileOf(text)) as BackupReadResult.Refused).rejection.problem

    private suspend fun placeOf(text: String): BackupPlace = (reader.read(fileOf(text)) as BackupReadResult.Refused).rejection.place

    private fun parsed(text: String): JsonElement = backupJson.parseToJsonElement(text)

    private fun reversedFields(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.entries.reversed().associate { it.key to reversedFields(it.value) })
            is JsonArray -> JsonArray(element.map { reversedFields(it) })
            else -> element
        }
}
