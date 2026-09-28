package dev.pnptracker.platform.settings

import dev.pnptracker.domain.games.GameOrder
import dev.pnptracker.domain.games.GameOrderProblem
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.platform.files.PlatformFileRules
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The order the games are in, on a real disk (PLAN 12.18). */
class DesktopGameOrderStoreTest {
    private lateinit var folder: Path
    private lateinit var orderFile: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-game-order-test")
        orderFile = folder.resolve("game-order.json")
    }

    @AfterTest
    fun deleteFolder() {
        val absolute = folder.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(absolute.startsWith(temporary) && absolute != temporary) { "refusing to delete $absolute" }
        PlatformFileRules.letWritingBack(absolute)
        Files.walk(absolute).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `with no file there is no order, and still no file`() =
        runBlocking<Unit> {
            val read = DesktopGameOrderStore(orderFile).read()

            assertTrue(read.gameIds.isEmpty())
            assertNull(read.problem)
            assertTrue(Files.notExists(orderFile), "reading created the file")
        }

    @Test
    fun `the order written is the order the next start reads`() =
        runBlocking<Unit> {
            val order = GameOrder(List(3) { IdGenerator.Random.newId() })

            DesktopGameOrderStore(orderFile).write(order)

            assertEquals(order.gameIds, DesktopGameOrderStore(orderFile).read().gameIds)
        }

    @Test
    fun `a file that is not this document is reported and left exactly as it was`() =
        runBlocking<Unit> {
            val hand = "sırası elle yazılmış"
            Files.writeString(orderFile, hand)

            val read = DesktopGameOrderStore(orderFile).read()

            assertEquals(GameOrderProblem.NOT_THE_EXPECTED_SHAPE, read.problem)
            assertEquals(hand, Files.readString(orderFile), "the file was rewritten")
        }

    @Test
    fun `a folder that will not take the file costs the next start its order and nothing else`() =
        runBlocking<Unit> {
            PlatformFileRules.refuseWriting(folder)

            // Not raised: the table is already in the order the user gave it.
            DesktopGameOrderStore(orderFile).write(GameOrder(listOf(IdGenerator.Random.newId())))

            PlatformFileRules.letWritingBack(folder)
            assertTrue(Files.notExists(orderFile), "a refused write left a file behind")
        }
}
