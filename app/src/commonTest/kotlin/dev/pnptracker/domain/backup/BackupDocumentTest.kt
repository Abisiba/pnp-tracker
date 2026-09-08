package dev.pnptracker.domain.backup

import dev.pnptracker.AppInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private const val COLOR_ID = "11111111-1111-4111-8111-111111111111"
private const val GAME_ID = "33333333-3333-4333-8333-333333333333"

/** A clock that always says the same thing and counts how often it was asked. */
private class CountingClock(
    private val fixed: Instant,
) : Clock {
    var readings: Int = 0
        private set

    override fun now(): Instant {
        readings++
        return fixed
    }
}

private class FixedSource(
    private val snapshot: BackupSnapshot,
) : BackupSource {
    override suspend fun snapshot(): BackupSnapshot = snapshot
}

private fun emptyData(
    colors: List<BackupColorRow> = emptyList(),
    games: List<BackupGameRow> = emptyList(),
) = BackupData(
    colors = colors,
    colorAliases = emptyList(),
    importBatches = emptyList(),
    games = games,
    gameCells = emptyList(),
    rawImportBlocks = emptyList(),
    tasks = emptyList(),
    cellSegments = emptyList(),
    taskColors = emptyList(),
    taskStages = emptyList(),
    progressEvents = emptyList(),
    historyEvents = emptyList(),
    importBatchCells = emptyList(),
    draftTasks = emptyList(),
    draftTaskColors = emptyList(),
)

private val aColor =
    BackupColorRow(
        id = COLOR_ID,
        canonicalName = "Gri",
        normalizedName = "gri",
        hex = "#808080",
        sortOrder = 2,
    )

/**
 * What a backup document is, as bytes and as a promise about them.
 *
 * Nothing here touches a database. The question is narrower and worth answering
 * on its own: given some data, does the writer always produce the same document,
 * and does the checksum in it describe the data the document actually carries.
 * A test that went through storage as well would answer both questions at once
 * and tell you nothing about which of them had broken.
 */
class BackupDocumentTest {
    @Test
    fun `the canonical data is written compactly, in a fixed order, with nothing left out`() {
        val json = canonicalBackupDataJson(emptyData(colors = listOf(aColor)))
        val expected =
            """{"colors":[{"id":"$COLOR_ID","canonicalName":"Gri",""" +
                """"normalizedName":"gri","hex":"#808080","sortOrder":2}],""" +
                """"colorAliases":[],"importBatches":[],"games":[],"gameCells":[],""" +
                """"rawImportBlocks":[],"tasks":[],"cellSegments":[],"taskColors":[],""" +
                """"taskStages":[],"progressEvents":[],"historyEvents":[],""" +
                """"importBatchCells":[],"draftTasks":[],"draftTaskColors":[]}"""
        assertEquals(expected, json)
    }

    @Test
    fun `a field that is null is written as null rather than left out`() {
        val game =
            BackupGameRow(
                id = GAME_ID,
                name = "Örnek Oyun",
                isManuallyCompleted = false,
                completedAt = null,
                createdAt = 1_757_310_000_000,
                updatedAt = 1_757_310_000_000,
                deletedAt = null,
                sourceImportBatchId = null,
            )
        val json = canonicalBackupDataJson(emptyData(games = listOf(game)))
        val games = Json.parseToJsonElement(json).jsonObject.getValue("games")
        val written = games.jsonArray.single().jsonObject

        assertEquals(
            listOf(
                "id",
                "name",
                "isManuallyCompleted",
                "completedAt",
                "createdAt",
                "updatedAt",
                "deletedAt",
                "sourceImportBatchId",
            ),
            written.keys.toList(),
        )
        assertEquals(JsonNull, written.getValue("completedAt"))
        assertEquals(JsonNull, written.getValue("deletedAt"))
        assertEquals(JsonNull, written.getValue("sourceImportBatchId"))
        // A value that happens to equal a default is still written, so a reader
        // never has to know what the defaults were.
        assertEquals(JsonPrimitive(false), written.getValue("isManuallyCompleted"))
    }

    @Test
    fun `numbers are written as integers and never as floating point`() {
        val json = canonicalBackupDataJson(emptyData(colors = listOf(aColor)))
        assertFalse('.' in json.substringAfter("\"sortOrder\":").substringBefore('}'))
        assertFalse("e+" in json || "E+" in json)
    }

    @Test
    fun `the same data is always the same bytes`() {
        val data = emptyData(colors = listOf(aColor))
        assertEquals(canonicalBackupDataJson(data), canonicalBackupDataJson(data))
        assertEquals(canonicalBackupDataJson(data), canonicalBackupDataJson(emptyData(colors = listOf(aColor))))
    }

    @Test
    fun `the checksum is the digest of the canonical data and of nothing else`() {
        val data = emptyData(colors = listOf(aColor))
        val document = backupDocumentOf(data, "0.1.0", 8, Instant.fromEpochMilliseconds(1_757_320_364_031))

        assertEquals(sha256Of(canonicalBackupDataJson(data).encodeToByteArray()), document.envelope.dataSha256)
        assertEquals(64, document.envelope.dataSha256.length)
        assertTrue(document.envelope.dataSha256.all { it in "0123456789abcdef" }, document.envelope.dataSha256)
    }

    @Test
    fun `the document embeds exactly the bytes it hashed`() {
        // The one assumption the checksum rests on: what goes inside the envelope
        // is character for character what was hashed. If the two ever parted
        // company, every backup would look corrupt to the reader that checked it.
        val data = emptyData(colors = listOf(aColor))
        val document = backupDocumentOf(data, "0.1.0", 8, Instant.fromEpochMilliseconds(1_757_320_364_031))
        assertTrue(
            document.json.contains("\"data\":" + canonicalBackupDataJson(data)),
            "the embedded data is not the data that was hashed",
        )
    }

    @Test
    fun `when the moment changes the checksum does not`() {
        val data = emptyData(colors = listOf(aColor))
        val early = backupDocumentOf(data, "0.1.0", 8, Instant.fromEpochMilliseconds(1_000_000_000_000))
        val late = backupDocumentOf(data, "0.1.0", 8, Instant.fromEpochMilliseconds(1_757_320_364_031))

        assertEquals(early.envelope.dataSha256, late.envelope.dataSha256)
        assertNotEquals(early.envelope.createdAt, late.envelope.createdAt)
        assertNotEquals(early.json, late.json)
    }

    @Test
    fun `when the application version changes the checksum does not`() {
        val data = emptyData(colors = listOf(aColor))
        val moment = Instant.fromEpochMilliseconds(1_757_320_364_031)
        assertEquals(
            backupDocumentOf(data, "0.1.0", 8, moment).envelope.dataSha256,
            backupDocumentOf(data, "9.9.9", 8, moment).envelope.dataSha256,
        )
    }

    @Test
    fun `when one value changes the checksum changes`() {
        val moment = Instant.fromEpochMilliseconds(1_757_320_364_031)
        val one = backupDocumentOf(emptyData(colors = listOf(aColor)), "0.1.0", 8, moment)
        val other =
            backupDocumentOf(emptyData(colors = listOf(aColor.copy(sortOrder = 3))), "0.1.0", 8, moment)
        assertNotEquals(one.envelope.dataSha256, other.envelope.dataSha256)
    }

    @Test
    fun `the envelope names the format, its version and where the rows came from`() {
        val document =
            backupDocumentOf(emptyData(), "0.1.0", 8, Instant.fromEpochMilliseconds(1_757_320_364_031))
        val envelope = document.envelope

        assertEquals("pnp-tracker-backup", envelope.format)
        assertEquals(1, envelope.formatVersion)
        assertEquals("0.1.0", envelope.appVersion)
        assertEquals(8, envelope.sourceSchemaVersion)
        assertEquals("2025-09-08T08:32:44.031Z", envelope.createdAt)
    }

    @Test
    fun `the envelope is written before the data, in the order the format fixes`() {
        val document =
            backupDocumentOf(emptyData(), "0.1.0", 8, Instant.fromEpochMilliseconds(1_757_320_364_031))
        val written = Json.parseToJsonElement(document.json).jsonObject
        assertEquals(
            listOf("format", "formatVersion", "appVersion", "sourceSchemaVersion", "createdAt", "dataSha256", "data"),
            written.keys.toList(),
        )
        assertTrue(written.getValue("data") is JsonObject)
    }

    @Test
    fun `the exporter asks the clock once and stamps the document with that moment`() =
        runBlocking<Unit> {
            val clock = CountingClock(Instant.fromEpochMilliseconds(1_757_320_364_031))
            val exporter =
                DatabaseBackupExporter(
                    source = FixedSource(BackupSnapshot(sourceSchemaVersion = 8, data = emptyData())),
                    appInfo = AppInfo.Current,
                    clock = clock,
                )

            val document = exporter.backupDocument()

            assertEquals(1, clock.readings)
            assertEquals("2025-09-08T08:32:44.031Z", document.envelope.createdAt)
            assertEquals(AppInfo.Current.version, document.envelope.appVersion)
        }

    @Test
    fun `the exporter records the schema version the rows actually came from`() =
        runBlocking<Unit> {
            val exporter =
                DatabaseBackupExporter(
                    source = FixedSource(BackupSnapshot(sourceSchemaVersion = 8, data = emptyData())),
                    appInfo = AppInfo.Current,
                    clock = CountingClock(Instant.fromEpochMilliseconds(1_757_320_364_031)),
                )
            assertEquals(8, exporter.backupDocument().envelope.sourceSchemaVersion)
        }

    @Test
    fun `the same database and the same clock produce the same document twice`() =
        runBlocking<Unit> {
            val source = FixedSource(BackupSnapshot(sourceSchemaVersion = 8, data = emptyData(colors = listOf(aColor))))
            val moment = Instant.fromEpochMilliseconds(1_757_320_364_031)
            val first = DatabaseBackupExporter(source, AppInfo.Current, CountingClock(moment)).backupDocument()
            val second = DatabaseBackupExporter(source, AppInfo.Current, CountingClock(moment)).backupDocument()
            assertEquals(first.json, second.json)
        }
}
