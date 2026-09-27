package dev.pnptracker.platform.settings

import dev.pnptracker.domain.games.MINIMUM_COLUMN_WIDTH_DP
import dev.pnptracker.domain.games.TableColumn
import dev.pnptracker.domain.games.TableSizes
import dev.pnptracker.domain.games.TableSizesProblem
import dev.pnptracker.domain.model.IdGenerator
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The table's sizes on a real disk.
 *
 * The same two promises the settings file makes (PLAN 14.4.12, PLAN 12.17), kept
 * here for a file nobody is ever told about: reading never creates it, and a file
 * that cannot be understood is left exactly as it is rather than repaired.
 *
 * And one promise of its own. A layout that will not save is nobody's
 * emergency — the table is already drawn the way it was dragged — so a refused
 * write does not travel out of here as an exception.
 */
class DesktopTableSizesStoreTest {
    private lateinit var folder: Path
    private lateinit var sizesFile: Path
    private val harmonies = IdGenerator.Random.newId()

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-table-sizes-test")
        sizesFile = folder.resolve("table-sizes.json")
    }

    @AfterTest
    fun deleteFolder() {
        val absolute = folder.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun aStore(writer: AtomicFileWriter = AtomicFileWriter(temporarySuffix = ".json.part")) =
        DesktopTableSizesStore(sizesFile, writer)

    @Test
    fun `with no file the table is drawn at its defaults and there is still no file`() =
        runBlocking<Unit> {
            val read = aStore().read()

            assertTrue(read.isDefault)
            assertNull(read.problem, "a machine that has dragged nothing has nothing wrong with it")
            assertTrue(Files.notExists(sizesFile), "reading created the sizes file")
        }

    @Test
    fun `reading many times still creates nothing`() =
        runBlocking<Unit> {
            repeat(5) { aStore().read() }

            assertEquals(emptyList(), namesIn(folder), "reading left something behind")
        }

    @Test
    fun `what one drag wrote the next start reads`() =
        runBlocking<Unit> {
            val sizes = TableSizes.Default.withColumn(TableColumn.NOTES, 420f).withRow(harmonies, 220f)

            aStore().write(sizes)

            assertEquals(sizes, aStore().read())
            assertEquals(listOf("table-sizes.json"), namesIn(folder), "the write left more than the file behind")
        }

    @Test
    fun `a file that is not the document this writes is left exactly as it is`() =
        runBlocking<Unit> {
            val byHand = """{"formatVersion":1,"columnWidths":{"NOT_A_COLUMN":10.0}}"""
            Files.writeString(sizesFile, byHand)

            val read = aStore().read()

            assertEquals(TableSizesProblem.NOT_THE_EXPECTED_SHAPE, read.problem)
            assertTrue(read.isDefault, "an unusable file changed what the table is drawn at")
            assertEquals(byHand, Files.readString(sizesFile), "the file was repaired behind the user's back")
        }

    @Test
    fun `bytes that are not text at all are a shape problem and are kept`() =
        runBlocking<Unit> {
            val notText = byteArrayOf(-1, -2, -3, 0, 65)
            Files.write(sizesFile, notText)

            val read = aStore().read()

            assertEquals(TableSizesProblem.NOT_THE_EXPECTED_SHAPE, read.problem)
            assertTrue(notText.contentEquals(Files.readAllBytes(sizesFile)), "the file was rewritten")
        }

    @Test
    fun `a size the user changes afterwards is written, because they asked for it`() =
        runBlocking<Unit> {
            // PLAN 12.17: the file is never repaired *by reading it*. A drag is an
            // instruction, and an instruction writes the document afresh.
            Files.writeString(sizesFile, "not our document")
            val store = aStore()
            store.read()

            store.write(TableSizes.Default.withColumn(TableColumn.CARD, 300f))

            assertEquals(300f, store.read().widthOf(TableColumn.CARD))
        }

    @Test
    fun `a write that fails is not raised, and leaves the file it could not replace`() =
        runBlocking<Unit> {
            aStore().write(TableSizes.Default.withColumn(TableColumn.CARD, 300f))
            val before = Files.readAllBytes(sizesFile)
            val failing =
                AtomicFileWriter(
                    temporarySuffix = ".json.part",
                    writeBytes = { _, _ -> throw IOException("the disk is full") },
                )

            // Nothing is thrown: a layout that could not be remembered is not
            // worth interrupting anybody for.
            aStore(failing).write(TableSizes.Default.withColumn(TableColumn.CARD, 480f))

            assertTrue(before.contentEquals(Files.readAllBytes(sizesFile)), "the old file was not byte for byte kept")
            assertEquals(300f, aStore().read().widthOf(TableColumn.CARD), "the failed write changed what is remembered")
        }

    @Test
    fun `a failed first write leaves no file at all`() =
        runBlocking<Unit> {
            val failing =
                AtomicFileWriter(
                    temporarySuffix = ".json.part",
                    createTemporary = { throw java.nio.file.AccessDeniedException(it.toString()) },
                )

            aStore(failing).write(TableSizes.Default.withColumn(TableColumn.CARD, 300f))

            assertTrue(Files.notExists(sizesFile), "a failed first write created a file")
        }

    @Test
    fun `two drags at once leave one whole document`() =
        runBlocking<Unit> {
            val store = aStore()

            coroutineScope {
                listOf(300f, 420f)
                    .map { width -> async { store.write(TableSizes.Default.withColumn(TableColumn.CARD, width)) } }
                    .awaitAll()
            }

            val read = store.read()
            assertNull(read.problem, "the file holds the halves of two writes")
            assertTrue(read.widthOf(TableColumn.CARD) in listOf(300f, 420f), "the file holds neither of the two widths")
        }

    @Test
    fun `a size below the smallest is brought inside the bounds when it is read back`() =
        runBlocking<Unit> {
            Files.writeString(sizesFile, """{"formatVersion":1,"columnWidths":{"CARD":2.0},"rowHeights":{}}""")

            assertEquals(MINIMUM_COLUMN_WIDTH_DP, aStore().read().widthOf(TableColumn.CARD))
        }

    private fun namesIn(directory: Path): List<String> =
        Files.list(directory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
}
