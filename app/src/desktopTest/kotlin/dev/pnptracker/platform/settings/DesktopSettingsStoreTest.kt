package dev.pnptracker.platform.settings

import dev.pnptracker.domain.settings.SettingsNotSaved
import dev.pnptracker.domain.settings.SettingsProblem
import dev.pnptracker.domain.settings.SettingsWriteFailure
import dev.pnptracker.platform.files.AtomicFileWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings file on a real disk.
 *
 * Two promises are kept here and nowhere else, and both are about what does
 * *not* happen. Reading never creates the file, so a machine where nobody has
 * chosen anything stays that way however much the application runs. And a file
 * that cannot be understood is never rewritten — it may be the user's own edit
 * or a newer build's, and overwriting it without being asked destroys the only
 * copy of what it said (PLAN 14.4.12).
 */
class DesktopSettingsStoreTest {
    private lateinit var folder: Path
    private lateinit var settingsFile: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-settings-test")
        settingsFile = folder.resolve("settings.json")
    }

    @AfterTest
    fun deleteFolder() {
        val absolute = folder.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun aStore(writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = ".json.part")) =
        DesktopSettingsStore(settingsFile, writer)

    @Test
    fun `with no file there is a default and still no file`() =
        runBlocking<Unit> {
            val read = aStore().read()

            assertEquals(7, read.automaticBackupCount)
            assertNull(read.problem, "a machine that has chosen nothing has nothing wrong with it")
            assertTrue(Files.notExists(settingsFile), "reading created the settings file")
        }

    @Test
    fun `reading many times still creates nothing`() =
        runBlocking<Unit> {
            repeat(5) { aStore().read() }

            assertTrue(Files.notExists(settingsFile))
            assertEquals(emptyList(), namesIn(folder))
        }

    @Test
    fun `saving creates the file, and it holds what the contract says`() =
        runBlocking<Unit> {
            aStore().write(12)

            assertEquals("""{"formatVersion":1,"automaticBackupCount":12}""", Files.readString(settingsFile))
            assertEquals(12, aStore().read().automaticBackupCount)
            assertNull(aStore().read().problem)
            assertTrue(namesIn(folder).none { it.endsWith(".part") }, "a half-written file was left behind")
        }

    @Test
    fun `every number that may be kept survives the round trip`() =
        runBlocking<Unit> {
            listOf(1, 7, 50).forEach { count ->
                aStore().write(count)

                assertEquals(count, aStore().read().automaticBackupCount, "$count")
            }
        }

    @Test
    fun `a number outside the range never reaches the disk`() =
        runBlocking<Unit> {
            aStore().write(9)
            val before = Files.readAllBytes(settingsFile)

            listOf(0, -1, 51).forEach { count ->
                assertFailsWith<IllegalArgumentException>("$count") { aStore().write(count) }
            }

            assertTrue(before.contentEquals(Files.readAllBytes(settingsFile)), "a refused value changed the file")
        }

    @Test
    fun `a file that cannot be understood is reported and left exactly as it is`() =
        runBlocking<Unit> {
            val cases =
                mapOf(
                    "bunlar benim notlarım" to SettingsProblem.NOT_THE_EXPECTED_SHAPE,
                    "{" to SettingsProblem.NOT_THE_EXPECTED_SHAPE,
                    """{"formatVersion":2,"automaticBackupCount":9}""" to SettingsProblem.VERSION_NOT_SUPPORTED,
                    """{"formatVersion":1,"automaticBackupCount":0}""" to SettingsProblem.VALUE_OUT_OF_RANGE,
                )

            cases.forEach { (text, expected) ->
                Files.writeString(settingsFile, text)

                val read = aStore().read()

                assertEquals(expected, read.problem, text)
                assertEquals(7, read.automaticBackupCount, text)
                // The whole point: their file is still their file.
                assertEquals(text, Files.readString(settingsFile), "the file was rewritten without being asked")
            }
        }

    @Test
    fun `bytes that are not text at all are reported rather than thrown`() =
        runBlocking<Unit> {
            val notText = byteArrayOf(0xC3.toByte(), 0x28, 0xA0.toByte(), 0xA1.toByte())
            Files.write(settingsFile, notText)

            val read = aStore().read()

            assertEquals(SettingsProblem.NOT_THE_EXPECTED_SHAPE, read.problem)
            assertEquals(7, read.automaticBackupCount)
            assertTrue(notText.contentEquals(Files.readAllBytes(settingsFile)))
        }

    @Test
    fun `a saved value replaces a file that could not be read, but only when asked`() =
        runBlocking<Unit> {
            Files.writeString(settingsFile, "bozuk")
            assertEquals(SettingsProblem.NOT_THE_EXPECTED_SHAPE, aStore().read().problem)
            assertEquals("bozuk", Files.readString(settingsFile))

            aStore().write(4)

            assertEquals("""{"formatVersion":1,"automaticBackupCount":4}""", Files.readString(settingsFile))
            assertNull(aStore().read().problem)
        }

    @Test
    fun `a write that fails leaves the old bytes and no litter`() =
        runBlocking<Unit> {
            aStore().write(9)
            val before = Files.readAllBytes(settingsFile)
            val failing =
                AtomicFileWriter(
                    temporarySuffix = ".json.part",
                    writeBytes = { _, _ -> throw IOException("the disk is full") },
                )

            val refused = assertFailsWith<SettingsNotSaved> { aStore(failing).write(21) }

            assertEquals(SettingsWriteFailure.COULD_NOT_WRITE, refused.failure)
            assertTrue(before.contentEquals(Files.readAllBytes(settingsFile)), "the old file was not byte for byte kept")
            assertEquals(9, aStore().read().automaticBackupCount, "the running value moved on a failed write")
            assertTrue(namesIn(folder).none { it.endsWith(".part") }, namesIn(folder).toString())
        }

    @Test
    fun `a folder that will not take a file says so in its own words`() =
        runBlocking<Unit> {
            val failing =
                AtomicFileWriter(
                    temporarySuffix = ".json.part",
                    createTemporary = { throw java.nio.file.AccessDeniedException(it.toString()) },
                )

            val refused = assertFailsWith<SettingsNotSaved> { aStore(failing).write(21) }

            assertEquals(SettingsWriteFailure.NOT_WRITABLE, refused.failure)
            assertTrue(Files.notExists(settingsFile), "a failed first write created a file")
        }

    @Test
    fun `a failure carries nothing a user would be shown`() =
        runBlocking<Unit> {
            val failing =
                AtomicFileWriter(
                    temporarySuffix = ".json.part",
                    writeBytes = { _, _ -> throw IOException("the disk is full") },
                )

            val refused = assertFailsWith<SettingsNotSaved> { aStore(failing).write(21) }

            // PLAN 14.4.5's boundary: what the user is told comes from the typed
            // failure, and the path stays down here with the cause.
            assertTrue(refused.message?.contains(settingsFile.toString()) != true, refused.message.orEmpty())
        }

    @Test
    fun `saves at the same moment are taken one at a time`() =
        runBlocking<Unit> {
            // Two writes overlapping would leave the halves of two documents in
            // one file. PLAN 14.4.12 serialises them, so whatever else is true,
            // the file holds one of the values somebody asked for.
            val store = aStore()

            coroutineScope {
                (1..24).map { count -> async { store.write(count) } }.awaitAll()
            }

            val written = Files.readString(settingsFile)
            val count = store.read().automaticBackupCount
            assertEquals("""{"formatVersion":1,"automaticBackupCount":$count}""", written)
            assertTrue(count in 1..24, written)
            assertTrue(namesIn(folder).none { it.endsWith(".part") }, namesIn(folder).toString())
        }

    @Test
    fun `reads and writes at the same moment never see half a document`() =
        runBlocking<Unit> {
            val store = aStore()
            store.write(5)

            val reads =
                coroutineScope {
                    val writes = (1..12).map { count -> async { store.write(count) } }
                    val reads = (1..12).map { async { store.read() } }
                    writes.awaitAll()
                    reads.awaitAll()
                }

            reads.forEach { read ->
                assertNull(read.problem, "a read caught the file part way through a write")
                assertTrue(read.automaticBackupCount in 1..12, "${read.automaticBackupCount}")
            }
        }

    private fun namesIn(directory: Path): List<String> =
        Files.list(directory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
}
