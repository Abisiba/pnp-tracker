package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.backupJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What has to be true of the JSON itself, before anything is read out of it.
 *
 * Two halves. The scanner is tested directly, because it exists to answer
 * questions the parser has already thrown the evidence away for — which of two
 * identical keys was written, how deep the brackets went — and the only place to
 * ask those is on the text. The rest goes through the whole reader, because
 * "this document is refused" is the fact that matters and the way it is refused
 * has to survive the parser being handed the same file.
 */
class BackupJsonStructureTest {
    private val reader = UntrustedBackupReader(CountingProbe())

    @Test
    fun `what the writer produces is what the reader accepts`() =
        runBlocking<Unit> {
            // The one test that has to hold for any of the others to matter.
            val result = reader.read(fileOf(documentOf()))
            assertTrue(result is BackupReadResult.Valid, "the writer's own output was refused: $result")
        }

    @Test
    fun `the JSON library on its own would keep the last of two identical keys`() {
        // Which is exactly why the scanner exists. If this ever starts throwing,
        // the library has taken the decision over and this test says so.
        val parsed = backupJson.parseToJsonElement("""{"a":1,"a":2}""").jsonObject
        assertEquals("2", parsed.getValue("a").jsonPrimitive.content)
        assertEquals(1, parsed.size, "a repeated key survived as two entries")
    }

    @Test
    fun `a repeated key is found wherever it is`() =
        runBlocking<Unit> {
            val atTheTop = documentOf().replaceFirst("{\"format\"", "{\"format\":\"x\",\"format\"")
            assertEquals(BackupProblem.DUPLICATE_KEY, refusalOf(atTheTop))

            val inTheData = documentOf().replaceFirst("\"colors\":[", "\"colors\":[],\"colors\":[")
            assertEquals(BackupProblem.DUPLICATE_KEY, refusalOf(inTheData))

            val inARow = documentOf().replaceFirst("\"canonicalName\":", "\"canonicalName\":\"x\",\"canonicalName\":")
            assertEquals(BackupProblem.DUPLICATE_KEY, refusalOf(inARow))
        }

    @Test
    fun `a key written with escapes is the same key`() =
        runBlocking<Unit> {
            // Two spellings of `format`, which every JSON reader resolves to one
            // name. Comparing the text as written would let this through.
            val escaped = documentOf().replaceFirst("{\"format\"", "{\"\\u0066ormat\":\"x\",\"format\"")
            assertEquals(BackupProblem.DUPLICATE_KEY, refusalOf(escaped))
        }

    @Test
    fun `the same key in two different objects is not a repetition`() {
        assertNull(scanJsonText("""{"a":{"id":1},"b":{"id":2}}"""))
        assertNull(scanJsonText("""[{"id":1},{"id":2}]"""))
    }

    @Test
    fun `punctuation inside a string does not fool the scanner`() {
        assertNull(scanJsonText("""{"note":"{\"a\":1,\"a\":2}","other":"}}]],:"}"""))
    }

    @Test
    fun `a game name that looks like a duplicated object is carried through`() =
        runBlocking<Unit> {
            val mischief = """{"a":1,"a":2} : , } ] "quoted" \ backslash"""
            val data = aWholeBackup().let { it.copy(games = it.games.map { game -> game.copy(name = mischief) }) }

            val result = reader.read(fileOf(documentOf(data)))

            assertEquals(
                mischief,
                (result as BackupReadResult.Valid)
                    .backup.data.games
                    .single()
                    .name,
            )
        }

    @Test
    fun `a surrogate pair is one character and an unpaired half is not a character`() {
        assertNull(scanJsonText("""{"a":"\ud83d\ude00"}"""))
        assertEquals(BackupProblem.MALFORMED_JSON, scanJsonText("""{"a":"\ud83d"}""")?.problem)
        assertEquals(BackupProblem.MALFORMED_JSON, scanJsonText("""{"a":"\ude00"}""")?.problem)
        assertEquals(BackupProblem.MALFORMED_JSON, scanJsonText("""{"a":"\ud83dx"}""")?.problem)
    }

    @Test
    fun `an escape the format does not have is a broken document`() {
        assertEquals(BackupProblem.MALFORMED_JSON, scanJsonText("""{"a":"\q"}""")?.problem)
        assertEquals(BackupProblem.MALFORMED_JSON, scanJsonText("""{"a":"\u00zz"}""")?.problem)
        assertEquals(BackupProblem.MALFORMED_JSON, scanJsonText("""{"a":"\""")?.problem)
        // Every escape the format does have.
        assertNull(scanJsonText("""{"a":"\"\\\/\b\f\n\r\t\u0041"}"""))
    }

    @Test
    fun `nesting past the limit is refused before anything parses it`() =
        runBlocking<Unit> {
            val deep = "{\"a\":".repeat(10_000) + "1" + "}".repeat(10_000)
            assertEquals(BackupProblem.TOO_DEEPLY_NESTED, refusalOf(deep))

            // And the limit is where it says it is, from either side.
            val atTheLimit = "[".repeat(MAXIMUM_JSON_DEPTH) + "]".repeat(MAXIMUM_JSON_DEPTH)
            assertNull(scanJsonText(atTheLimit))
            val onePast = "[".repeat(MAXIMUM_JSON_DEPTH + 1) + "]".repeat(MAXIMUM_JSON_DEPTH + 1)
            assertEquals(BackupProblem.TOO_DEEPLY_NESTED, scanJsonText(onePast)?.problem)
        }

    @Test
    fun `a document that stops in the middle is refused`() =
        runBlocking<Unit> {
            assertEquals(BackupProblem.MALFORMED_JSON, refusalOf(documentOf().dropLast(80)))
        }

    @Test
    fun `anything after the document is refused`() =
        runBlocking<Unit> {
            assertEquals(BackupProblem.MALFORMED_JSON, refusalOf(documentOf() + "sonradan eklenmiş"))
            assertEquals(BackupProblem.MALFORMED_JSON, refusalOf(documentOf() + documentOf()))
        }

    @Test
    fun `comments and trailing commas are not part of this format`() =
        runBlocking<Unit> {
            val commented = documentOf().replaceFirst("{", "{/* elle düzenlendi */")
            assertEquals(BackupProblem.MALFORMED_JSON, refusalOf(commented))

            val trailing = documentOf().dropLast(1) + ",}"
            assertEquals(BackupProblem.MALFORMED_JSON, refusalOf(trailing))
        }

    @Test
    fun `a document that is not an object is refused for its shape`() =
        runBlocking<Unit> {
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf("[]"))
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf("null"))
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf("\"pnp-tracker-backup\""))
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf("42"))
        }

    @Test
    fun `a field the format does not know is refused, in the envelope, in data and in a row`() =
        runBlocking<Unit> {
            val inEnvelope = documentOf().replaceFirst("{", "{\"reserved\":1,")
            assertEquals(BackupProblem.UNKNOWN_FIELD, refusalOf(inEnvelope))
            assertEquals(BackupPlace.Envelope, placeOf(inEnvelope))

            val newTable = documentOf().replaceFirst("\"colors\":[", "\"settings\":[],\"colors\":[")
            assertEquals(BackupProblem.UNKNOWN_FIELD, refusalOf(newTable))

            val inARow = documentOf().replaceFirst("\"canonicalName\"", "\"favourite\":true,\"canonicalName\"")
            assertEquals(BackupProblem.UNKNOWN_FIELD, refusalOf(inARow))
            assertEquals("colors", placeOf(inARow).part)
        }

    @Test
    fun `the name of an unknown field is never carried out of the file`() =
        runBlocking<Unit> {
            // It is text somebody else wrote, and a refusal is a thing that gets
            // shown and logged.
            val secretive = documentOf().replaceFirst("{", "{\"kullanıcının-gizli-notu\":1,")
            assertNull(placeOf(secretive).field)
        }

    @Test
    fun `a field the format requires cannot be left out`() =
        runBlocking<Unit> {
            val noChecksum = documentOf().replaceFirst(Regex("\"dataSha256\":\"[0-9a-f]{64}\","), "")
            assertEquals(BackupProblem.MISSING_FIELD, refusalOf(noChecksum))
            assertEquals("dataSha256", placeOf(noChecksum).field)

            val noSortOrder = documentOf().replaceFirst(",\"sortOrder\":0", "")
            assertEquals(BackupProblem.MISSING_FIELD, refusalOf(noSortOrder))
            assertEquals(BackupPlace("colors", "sortOrder"), placeOf(noSortOrder))

            val noTable = documentOf().replaceFirst("\"draftTaskColors\":[", "\"x\":[")
            assertEquals(BackupProblem.MISSING_FIELD, refusalOf(noTable))
        }

    @Test
    fun `a value of the wrong kind is refused rather than converted`() =
        runBlocking<Unit> {
            val numberAsText = documentOf().replaceFirst("\"sortOrder\":0", "\"sortOrder\":\"0\"")
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(numberAsText))

            val textAsNumber = documentOf().replaceFirst("\"canonicalName\":\"Kırmızı\"", "\"canonicalName\":1")
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(textAsNumber))

            val flagAsText = documentOf().replaceFirst("\"isProcessed\":true", "\"isProcessed\":\"true\"")
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(flagAsText))

            val withoutStages = aWholeBackup().copy(taskStages = emptyList())
            val tableAsObject = documentOf(withoutStages).replaceFirst("\"taskStages\":[]", "\"taskStages\":{}")
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(tableAsObject))

            val rowAsNumber = documentOf().replaceFirst("\"colors\":[{", "\"colors\":[1,{")
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(rowAsNumber))
        }

    @Test
    fun `null where the format allows none is a wrong value and not a missing one`() =
        runBlocking<Unit> {
            // PLAN 14.4.1: a field being absent and a field being null are
            // different things, and this is the second of them.
            val nullName = documentOf().replaceFirst("\"canonicalName\":\"Kırmızı\"", "\"canonicalName\":null")
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(nullName))
            assertEquals(BackupPlace("colors", "canonicalName"), placeOf(nullName))
        }

    @Test
    fun `a number that is not whole, not in range or not a number is refused`() =
        runBlocking<Unit> {
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(documentOf().replaceFirst("\"sortOrder\":0", "\"sortOrder\":0.5")))
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(documentOf().replaceFirst("\"sortOrder\":0", "\"sortOrder\":1.0")))
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(documentOf().replaceFirst("\"sortOrder\":0", "\"sortOrder\":1e3")))
            // Past what an Int holds, which is what this column is read into.
            assertEquals(
                BackupProblem.WRONG_TYPE,
                refusalOf(documentOf().replaceFirst("\"sortOrder\":0", "\"sortOrder\":3000000000")),
            )
            // And past what a moment holds.
            assertEquals(
                BackupProblem.WRONG_TYPE,
                refusalOf(documentOf().replaceFirst("\"importedAt\":$CREATED", "\"importedAt\":99999999999999999999")),
            )
        }

    @Test
    fun `NaN and infinity never become numbers`() =
        runBlocking<Unit> {
            // The parser keeps an unquoted token as text and leaves deciding what
            // it means to whoever reads it. Nothing here ever reads one: the
            // shape check asks whether the token is a whole number in range, and
            // neither of these is, so neither reaches a column.
            assertEquals(BackupProblem.WRONG_TYPE, refusalOf(documentOf().replaceFirst("\"sortOrder\":0", "\"sortOrder\":NaN")))
            assertEquals(
                BackupProblem.WRONG_TYPE,
                refusalOf(documentOf().replaceFirst("\"sortOrder\":0", "\"sortOrder\":-Infinity")),
            )
        }

    private suspend fun refusalOf(text: String): BackupProblem = (reader.read(fileOf(text)) as BackupReadResult.Refused).rejection.problem

    private suspend fun placeOf(text: String): BackupPlace = (reader.read(fileOf(text)) as BackupReadResult.Refused).rejection.place
}
