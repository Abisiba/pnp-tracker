package dev.pnptracker.data.database

import dev.pnptracker.AppInfo
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.canonicalBackupDataJson
import dev.pnptracker.domain.model.HistoryEventKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)

/**
 * What a backup of a real database actually contains.
 *
 * The document is read back by parsing it, not by comparing it with the objects
 * that produced it. Asking the mapping whether it agrees with itself would prove
 * only that it is consistent; what is worth knowing is whether the value a test
 * put into storage is the value that comes out of the file, and the only way to
 * ask that is to look at the file.
 */
class BackupSnapshotTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private var realDatabaseExisted = false
    private var database: AppDatabase? = null

    @BeforeTest
    fun createDirectory() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
    }

    @AfterTest
    fun deleteDirectory() {
        database?.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExisted)
        directory.delete()
    }

    private fun openFilled(): AppDatabase {
        val opened = DatabaseFactory().open(directory.databaseFile)
        database = opened
        runBlocking { fillWithEverything(opened) }
        return opened
    }

    private fun documentOf(opened: AppDatabase) =
        runBlocking {
            DatabaseBackupExporter(
                source = BackupStore(opened),
                appInfo = AppInfo.Current,
                clock = StoppedClock(MOMENT),
            ).backupDocument()
        }

    private fun dataOf(json: String): JsonObject {
        val document = Json.parseToJsonElement(json).jsonObject
        return document.getValue("data").jsonObject
    }

    private fun rowsOf(
        data: JsonObject,
        array: String,
    ) = data.getValue(array).jsonArray.map { it.jsonObject }

    private fun row(
        data: JsonObject,
        array: String,
        id: String,
        idField: String = "id",
    ): JsonObject = rowsOf(data, array).single { it.getValue(idField).jsonPrimitive.content == id }

    @Test
    fun `the document carries every table, with the rows the database holds`() {
        val data = dataOf(documentOf(openFilled()).json)

        assertEquals(14, rowsOf(data, "colors").size, "twelve base colours and two custom ones")
        assertEquals(2, rowsOf(data, "colorAliases").size)
        assertEquals(3, rowsOf(data, "importBatches").size)
        assertEquals(2, rowsOf(data, "games").size)
        assertEquals(3, rowsOf(data, "gameCells").size)
        assertEquals(3, rowsOf(data, "rawImportBlocks").size)
        assertEquals(3, rowsOf(data, "tasks").size)
        assertEquals(6, rowsOf(data, "cellSegments").size)
        assertEquals(3, rowsOf(data, "taskColors").size)
        assertEquals(3, rowsOf(data, "taskStages").size)
        assertEquals(3, rowsOf(data, "progressEvents").size)
        assertEquals(11, rowsOf(data, "historyEvents").size)
        assertEquals(2, rowsOf(data, "importBatchCells").size)
        assertEquals(2, rowsOf(data, "draftTasks").size)
        assertEquals(2, rowsOf(data, "draftTaskColors").size)
    }

    @Test
    fun `the base colours are in the backup rather than assumed`() {
        // PLAN 5.7 makes the twelve ordinary records, editable and deletable; a
        // backup that left them out and expected them to be seeded again would
        // undo whatever the user had done to them.
        val colors = rowsOf(dataOf(documentOf(openFilled()).json), "colors")
        val names = colors.map { it.getValue("canonicalName").jsonPrimitive.content }
        assertTrue("Gri" in names && "Kırmızı" in names, names.toString())
        assertTrue("Fıstık Yeşili" in names && "İnci Beyazı" in names, names.toString())
    }

    @Test
    fun `text comes back character for character`() {
        val data = dataOf(documentOf(openFilled()).json)
        val task = row(data, "tasks", TASK_MULTICOLOR)

        assertEquals(AWKWARD_TEXT, task.getValue("notes").jsonPrimitive.content)
        assertEquals("Kılıç $AWKWARD_TEXT", task.getValue("name").jsonPrimitive.content)
        assertEquals(AWKWARD_TEXT, row(data, "rawImportBlocks", RAW_BLOCK_ACCEPTED).getValue("rawText").jsonPrimitive.content)
        assertEquals(
            AWKWARD_TEXT,
            row(data, "progressEvents", "3c000000-0000-4000-8000-000000000001").getValue("note").jsonPrimitive.content,
        )
    }

    @Test
    fun `whitespace a rollback depends on is kept exactly`() {
        val data = dataOf(documentOf(openFilled()).json)
        val cells = rowsOf(data, "importBatchCells")
        val documents = cells.map { it.getValue("documentBefore").jsonPrimitive.content }.sorted()
        assertEquals(listOf("", DOCUMENT_BEFORE_WITH_SPACES), documents)
    }

    @Test
    fun `a null field and an empty one stay different things`() {
        val data = dataOf(documentOf(openFilled()).json)

        val taskSegment = row(data, "cellSegments", "2b000000-0000-4000-8000-000000000002")
        assertEquals(JsonNull, taskSegment.getValue("text"), "a task piece carries no text of its own")
        assertEquals(TASK_MULTICOLOR, taskSegment.getValue("taskId").jsonPrimitive.content)

        val plainSegment = row(data, "cellSegments", "2b000000-0000-4000-8000-000000000001")
        assertEquals("Kullanıcının kendi notu ", plainSegment.getValue("text").jsonPrimitive.content)
        assertEquals(JsonNull, plainSegment.getValue("taskId"))

        assertEquals(JsonNull, row(data, "tasks", TASK_FINISHED).getValue("notes"))
        assertEquals("", row(data, "tasks", TASK_SOFT_DELETED).getValue("notes").jsonPrimitive.content)
    }

    @Test
    fun `moments keep the exact value the column holds`() {
        val data = dataOf(documentOf(openFilled()).json)
        val game = row(data, "games", GAME_DELETED_ROW)

        assertEquals(1_700_000_000_000L, game.getValue("createdAt").jsonPrimitive.long)
        assertEquals(1_700_000_600_000L, game.getValue("updatedAt").jsonPrimitive.long)
        assertEquals(1_700_001_200_000L, game.getValue("deletedAt").jsonPrimitive.long)
        assertEquals(1_700_000_900_000L, game.getValue("completedAt").jsonPrimitive.long)
    }

    @Test
    fun `values keep their JSON type`() {
        val data = dataOf(documentOf(openFilled()).json)
        val task = row(data, "tasks", TASK_MULTICOLOR)

        assertEquals(JsonPrimitive(true), task.getValue("primaryBatchCompleted"), "a flag is a JSON boolean")
        assertEquals(JsonPrimitive(false), task.getValue("isCompleted"))
        assertTrue(task.getValue("isMissing").jsonPrimitive.boolean)
        assertEquals(15, task.getValue("requiredQuantity").jsonPrimitive.int)
        assertEquals("THREE_D", task.getValue("poolType").jsonPrimitive.content, "an enum keeps the name it is stored under")
        assertEquals("THREE_D_BATCH", task.getValue("trackingMode").jsonPrimitive.content)
        assertEquals(TASK_MULTICOLOR, task.getValue("id").jsonPrimitive.content, "an identifier is its canonical text")
    }

    @Test
    fun `a task the user deleted is in the backup, with the moment it went`() {
        val task = row(dataOf(documentOf(openFilled()).json), "tasks", TASK_SOFT_DELETED)
        assertEquals(1_700_001_200_000L, task.getValue("deletedAt").jsonPrimitive.long)
        assertTrue(task.getValue("needsInfo").jsonPrimitive.boolean)
    }

    @Test
    fun `a task of two colours stays one task with two ordered slots`() {
        val data = dataOf(documentOf(openFilled()).json)
        val slots =
            rowsOf(data, "taskColors")
                .filter { it.getValue("taskId").jsonPrimitive.content == TASK_MULTICOLOR }
        assertEquals(listOf(0, 1), slots.map { it.getValue("slotIndex").jsonPrimitive.int })
        assertEquals(
            listOf(CUSTOM_COLOR_A, CUSTOM_COLOR_B),
            slots.map { it.getValue("colorId").jsonPrimitive.content },
        )
        assertEquals(1, rowsOf(data, "tasks").count { it.getValue("id").jsonPrimitive.content == TASK_MULTICOLOR })
    }

    @Test
    fun `every kind of history line survives`() {
        val kinds =
            rowsOf(dataOf(documentOf(openFilled()).json), "historyEvents")
                .map { it.getValue("kind").jsonPrimitive.content }
                .toSet()
        assertEquals(HistoryEventKind.entries.map { it.name }.toSet(), kinds)
    }

    @Test
    fun `an import in each of its three states is carried`() {
        val data = dataOf(documentOf(openFilled()).json)
        assertEquals(
            setOf("DRAFT", "CONFIRMED", "ROLLED_BACK"),
            rowsOf(data, "importBatches").map { it.getValue("status").jsonPrimitive.content }.toSet(),
        )
        // A batch that detected no range keeps its four nulls rather than gaining
        // borrowed numbers.
        val unbounded = row(data, "importBatches", BATCH_ROLLED_BACK)
        assertEquals(JsonNull, unbounded.getValue("startRowIndex"))
        assertEquals(JsonNull, unbounded.getValue("endColumnIndex"))
    }

    @Test
    fun `the hints an import read and the drafts it produced are carried`() {
        val data = dataOf(documentOf(openFilled()).json)
        val accepted = row(data, "rawImportBlocks", RAW_BLOCK_ACCEPTED)
        assertEquals("ACCEPTED", accepted.getValue("gameCompletionHint").jsonPrimitive.content)
        assertEquals(GAME_KEPT, accepted.getValue("completionTargetGameId").jsonPrimitive.content)
        assertEquals(0xFF92D050.toInt(), accepted.getValue("fillColorArgb").jsonPrimitive.int)

        val draft = row(data, "draftTasks", DRAFT_FULL)
        assertEquals(TASK_MULTICOLOR, draft.getValue("materializedTaskId").jsonPrimitive.content)
        assertEquals(3, draft.getValue("selectionStartIndex").jsonPrimitive.int)
        assertEquals(11, draft.getValue("selectionEndIndex").jsonPrimitive.int)
        val bare = row(data, "draftTasks", DRAFT_BARE)
        assertEquals(JsonNull, bare.getValue("selectedPoolType"))
        assertEquals(JsonNull, bare.getValue("materializedTaskId"))
    }

    @Test
    fun `every table comes back in the order the format fixes`() {
        val data = dataOf(documentOf(openFilled()).json)

        // A number is padded before it is compared, so `10` sorts after `9`
        // here exactly as it does in the query that produced the order.
        fun keysOf(
            array: String,
            vararg fields: String,
        ) = rowsOf(data, array).map { row ->
            fields.map { field ->
                val value = row.getValue(field).jsonPrimitive.content
                if (value.all { it.isDigit() }) value.padStart(10, '0') else value
            }
        }

        listOf(
            keysOf("colors", "id"),
            keysOf("colorAliases", "colorId", "normalizedAlias"),
            keysOf("importBatches", "id"),
            keysOf("games", "id"),
            keysOf("gameCells", "id"),
            keysOf("rawImportBlocks", "id"),
            keysOf("tasks", "id"),
            keysOf("cellSegments", "cellId", "orderIndex"),
            keysOf("taskColors", "taskId", "slotIndex"),
            keysOf("taskStages", "taskId", "orderIndex"),
            keysOf("progressEvents", "id"),
            keysOf("historyEvents", "id"),
            keysOf("importBatchCells", "importBatchId", "cellId"),
            keysOf("draftTasks", "id"),
            keysOf("draftTaskColors", "draftTaskId", "slotIndex"),
        ).forEachIndexed { index, keys ->
            assertEquals(keys.sortedWith(rowOrder), keys, "array ${index + 1} is not in its own order")
        }
    }

    @Test
    fun `the schema version comes from the database that was read`() {
        assertEquals(8, documentOf(openFilled()).envelope.sourceSchemaVersion)
    }

    @Test
    fun `reading the database twice writes the same bytes`() {
        val opened = openFilled()
        assertEquals(documentOf(opened).json, documentOf(opened).json)
    }

    @Test
    fun `a backup writes nothing to the database`() {
        val opened = openFilled()
        val before = runBlocking { tableCounts(opened) }
        documentOf(opened)
        assertEquals(before, runBlocking { tableCounts(opened) })
    }

    @Test
    fun `one changed value changes the checksum`() {
        val opened = openFilled()
        val before = documentOf(opened)
        runBlocking {
            writeRow(
                opened,
                "task_colors",
                listOf("task_id" to TASK_SOFT_DELETED, "color_id" to CUSTOM_COLOR_A, "slot_index" to 0),
            )
        }
        val after = documentOf(opened)
        assertNotEquals(before.envelope.dataSha256, after.envelope.dataSha256)
        assertEquals(before.envelope.createdAt, after.envelope.createdAt, "only the data changed")
    }

    @Test
    fun `the canonical data of the document is the data the checksum covers`() {
        val opened = openFilled()
        val document = documentOf(opened)
        val data = runBlocking { BackupStore(opened).snapshot().data }
        assertTrue(document.json.contains("\"data\":" + canonicalBackupDataJson(data)))
    }

    private suspend fun tableCounts(opened: AppDatabase): List<Long> =
        listOf(
            "colors",
            "color_aliases",
            "import_batches",
            "games",
            "game_cells",
            "raw_import_blocks",
            "tasks",
            "cell_segments",
            "task_colors",
            "task_stages",
            "progress_events",
            "history_events",
            "import_batch_cells",
            "draft_tasks",
            "draft_task_colors",
        ).map { rowCount(opened, it) }

    private companion object {
        /** Compares rows by their key parts, the way SQLite ordered them. */
        val rowOrder =
            Comparator<List<String>> { left, right ->
                left.zip(right).firstNotNullOfOrNull { (a, b) -> a.compareTo(b).takeIf { it != 0 } } ?: 0
            }
    }
}
