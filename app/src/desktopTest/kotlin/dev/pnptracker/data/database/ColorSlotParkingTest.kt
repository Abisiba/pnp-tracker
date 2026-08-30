package dev.pnptracker.data.database

import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The slots a task keeps when one of its colours goes, at the sizes a fixed
 * offset could not survive.
 *
 * Removing a colour lifts the slots of every task using it out of the way,
 * deletes the row, and puts the rest back one place closer. Lifting them by
 * subtracting a fixed number quietly makes that number a ceiling: a slot at the
 * number itself lands on zero, is no longer below zero, and is never brought
 * back — the task keeps a slot that was never restored and the gap never closes.
 * A ceiling on how many colours a task may be produced in is not something this
 * application gets to invent, and `Int` is the only limit there is.
 *
 * **What these fixtures are.** Real tasks do not hold a colour at slot nine
 * hundred thousand; slots are dense from zero and stay that way. These fixtures
 * are deliberately not that. They exist to put the arithmetic of the lift under
 * a real unique index at the sizes where a fixed offset breaks, which is the
 * only place the difference between the two can be seen at all. So they assert
 * what the lift promises — every remaining slot comes back to exactly what it
 * was, less one if the colour that left was in front of it, in the same order —
 * rather than the dense `0..N-1` a real catalogue would also have.
 */
class ColorSlotParkingTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ColorCatalogueStore
    private var realDatabaseExistedBefore = false

    /** The ceiling the old lift had, and the two values either side of it. */
    private val oldCeiling = 1_000_000

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
        store = ColorCatalogueStore(database.colorDao())
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun colorNamed(name: String) = assertNotNull(database.colorDao().resolve(name))

    /**
     * A task holding each colour at the slot it is paired with.
     *
     * The pairs are inserted in the order they are given, so a test can decide
     * what order the rows sit in on disk — which is the closest anything can get
     * to choosing the order an `UPDATE` will visit them in.
     */
    private suspend fun aTaskHolding(
        placed: List<Pair<EntityId, Int>>,
        gameName: String = "Harmonies",
    ): EntityId {
        val task =
            insertGameCellAndTask(database, gameName = gameName, columnType = CellColumnType.THREE_D) {
                aTask(name = "Token")
            }
        placed.forEach { (colorId, slot) ->
            database.taskColorDao().insert(TaskColorEntity(taskId = task.id, colorId = colorId, slotIndex = slot))
        }
        return task.id
    }

    /**
     * What the task holds now, in slot order.
     *
     * Read through the entity on purpose: it refuses a negative slot, so a lift
     * left behind inside the row would come back here as a failure rather than
     * as a number nobody looked at.
     */
    private suspend fun heldBy(taskId: EntityId): List<Pair<EntityId, Int>> =
        database.taskColorDao().colorsOfTask(taskId).map { it.colorId to it.slotIndex }

    // ------------------------------------------------- around the old ceiling

    @Test
    fun `a colour leaving from just under the old ceiling closes its gap`() =
        runBlocking<Unit> {
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val task =
                aTaskHolding(
                    listOf(white.id to oldCeiling - 1, red.id to oldCeiling, blue.id to oldCeiling + 1),
                )

            store.deleteColor(red.id)

            assertEquals(listOf(white.id to oldCeiling - 1, blue.id to oldCeiling), heldBy(task))
        }

    @Test
    fun `a colour leaving from the old ceiling itself closes its gap`() =
        runBlocking<Unit> {
            // The counterexample. Lifting by a million put these three at 0, 1
            // and 2, left them there because none of them was below zero any
            // more, and deleted the middle one — leaving 0 and 2, a gap that
            // never closed and two slots that silently changed by a million.
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val task =
                aTaskHolding(
                    listOf(white.id to oldCeiling, red.id to oldCeiling + 1, blue.id to oldCeiling + 2),
                )

            store.deleteColor(red.id)

            assertEquals(listOf(white.id to oldCeiling, blue.id to oldCeiling + 1), heldBy(task))
        }

    @Test
    fun `a colour leaving from above the old ceiling closes its gap`() =
        runBlocking<Unit> {
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val task =
                aTaskHolding(
                    listOf(white.id to oldCeiling + 1, red.id to oldCeiling + 2, blue.id to oldCeiling + 3),
                )

            store.deleteColor(red.id)

            assertEquals(listOf(white.id to oldCeiling + 1, blue.id to oldCeiling + 2), heldBy(task))
        }

    @Test
    fun `slots on both sides of the old ceiling are lifted by the same rule`() =
        runBlocking<Unit> {
            // One task spanning the boundary. Under a fixed offset the first two
            // come back and the last two do not, so the task ends up renumbered
            // by two different rules at once.
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val green = colorNamed("Yeşil")
            val task =
                aTaskHolding(
                    listOf(
                        white.id to 0,
                        red.id to 1,
                        blue.id to oldCeiling,
                        green.id to oldCeiling + 1,
                    ),
                )

            store.deleteColor(red.id)

            assertEquals(
                listOf(white.id to 0, blue.id to oldCeiling - 1, green.id to oldCeiling),
                heldBy(task),
            )
        }

    // ------------------------------------------------------- at the very top

    @Test
    fun `a colour leaving from the largest slots there are does not overflow`() =
        runBlocking<Unit> {
            // Lifting these is where a fixed offset stops being merely wrong and
            // an unbounded one would wrap: the lift has to land inside `Int`
            // both ways. `-1 - Int.MAX_VALUE` is exactly `Int.MIN_VALUE`, and
            // there is nothing below it that it needs.
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val task =
                aTaskHolding(
                    listOf(
                        white.id to Int.MAX_VALUE - 2,
                        red.id to Int.MAX_VALUE - 1,
                        blue.id to Int.MAX_VALUE,
                    ),
                )

            store.deleteColor(red.id)

            assertEquals(
                listOf(white.id to Int.MAX_VALUE - 2, blue.id to Int.MAX_VALUE - 1),
                heldBy(task),
            )
        }

    @Test
    fun `the largest slot there is comes back untouched when nothing in front of it left`() =
        runBlocking<Unit> {
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val task = aTaskHolding(listOf(white.id to 0, red.id to Int.MAX_VALUE))

            store.deleteColor(red.id)

            assertEquals(listOf(white.id to 0), heldBy(task), "the slot in front of the one that left moved")
        }

    @Test
    fun `every slot a task may hold survives the lift and comes back as itself`() =
        runBlocking<Unit> {
            // One task carrying every boundary at once, losing the colour at the
            // very bottom, so each of the others has to come back exactly one
            // place lower than it was. This is the round trip: lift, delete, put
            // back, with nothing lost and nothing rounded.
            val leaving = colorNamed("Beyaz")
            val rest =
                listOf("Kırmızı", "Mavi", "Yeşil", "Sarı", "Mor", "Turuncu").map { colorNamed(it) }
            val slots =
                listOf(1, 2, oldCeiling - 1, oldCeiling, oldCeiling + 1, Int.MAX_VALUE)
            val task = aTaskHolding(listOf(leaving.id to 0) + rest.map { it.id }.zip(slots))

            store.deleteColor(leaving.id)

            assertEquals(rest.map { it.id }.zip(slots.map { it - 1 }), heldBy(task))
        }

    // ------------------------------------------------- order independence

    @Test
    fun `the answer is the same however the rows were laid down`() =
        runBlocking<Unit> {
            // SQLite promises nothing about the order an `UPDATE` visits rows
            // in, and the only handle a test has on it is the order the rows
            // were written. The same three slots are laid down six ways; every
            // one of them has to come out the same, under the real unique index
            // on (task_id, slot_index).
            val white = colorNamed("Beyaz")
            val red = colorNamed("Kırmızı")
            val blue = colorNamed("Mavi")
            val placed =
                listOf(white.id to oldCeiling, red.id to oldCeiling + 1, blue.id to oldCeiling + 2)
            val orders = permutationsOf(placed)
            val tasks = orders.mapIndexed { index, order -> order to aTaskHolding(order, gameName = "Oyun $index") }

            store.deleteColor(red.id)

            tasks.forEach { (order, task) ->
                assertEquals(
                    listOf(white.id to oldCeiling, blue.id to oldCeiling + 1),
                    heldBy(task),
                    "written as $order it came out differently",
                )
            }
        }

    @Test
    fun `many tasks at these sizes are all closed up in the same pass`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val white = colorNamed("Beyaz")
            val blue = colorNamed("Mavi")
            val tasks =
                (0 until 42).map { index ->
                    aTaskHolding(
                        listOf(
                            white.id to oldCeiling + index,
                            red.id to oldCeiling + index + 1,
                            blue.id to oldCeiling + index + 2,
                        ),
                        gameName = "Oyun $index",
                    )
                }

            store.deleteColor(red.id)

            tasks.forEachIndexed { index, task ->
                assertEquals(
                    listOf(white.id to oldCeiling + index, blue.id to oldCeiling + index + 1),
                    heldBy(task),
                )
            }
        }

    // -------------------------------------------------- nothing left behind

    @Test
    fun `no task is left holding a lifted slot`() =
        runBlocking<Unit> {
            val red = colorNamed("Kırmızı")
            val white = colorNamed("Beyaz")
            val high = aTaskHolding(listOf(white.id to oldCeiling, red.id to oldCeiling + 1), gameName = "Bir")
            val low = aTaskHolding(listOf(red.id to 0, white.id to 1), gameName = "Iki")
            val top = aTaskHolding(listOf(red.id to Int.MAX_VALUE - 1, white.id to Int.MAX_VALUE), gameName = "Uc")

            store.deleteColor(red.id)

            // Reading these through the entity is the check: it refuses a
            // negative slot, so a row still down where it was lifted to would
            // come back as a failure rather than as a number.
            listOf(high, low, top).forEach { task ->
                assertTrue(heldBy(task).all { it.second >= 0 }, "a lifted slot was left behind")
            }
            assertEquals(listOf(white.id to oldCeiling), heldBy(high))
            assertEquals(listOf(white.id to 0), heldBy(low))
            assertEquals(listOf(white.id to Int.MAX_VALUE - 1), heldBy(top))
        }

    @Test
    fun `a lift that is refused leaves the largest slots exactly as they were`() =
        runBlocking<Unit> {
            val trap = FailingSqliteDriver()
            val other = TemporaryDatabaseDirectory()
            val onTheTrap = DatabaseFactory(driver = trap).open(other.databaseFile)
            try {
                val onTrapStore = ColorCatalogueStore(onTheTrap.colorDao())
                val red = assertNotNull(onTheTrap.colorDao().resolve("Kırmızı"))
                val white = assertNotNull(onTheTrap.colorDao().resolve("Beyaz"))
                val task =
                    insertGameCellAndTask(onTheTrap, gameName = "Harmonies", columnType = CellColumnType.THREE_D) {
                        aTask(name = "Token")
                    }
                listOf(white.id to Int.MAX_VALUE - 1, red.id to Int.MAX_VALUE).forEach { (colorId, slot) ->
                    onTheTrap.taskColorDao().insert(TaskColorEntity(task.id, colorId, slot))
                }

                trap.failOn { it.trimStart().uppercase().startsWith("DELETE FROM TASK_COLORS") }
                assertFailsWith<ColorSetupException> { onTrapStore.deleteColor(red.id) }
                trap.disarm()

                assertEquals(
                    listOf(white.id to Int.MAX_VALUE - 1, red.id to Int.MAX_VALUE),
                    onTheTrap.taskColorDao().colorsOfTask(task.id).map { it.colorId to it.slotIndex },
                    "the lift was rolled back to something other than what it started as",
                )
            } finally {
                trap.disarm()
                onTheTrap.close()
                other.delete()
            }
        }

    // -------------------------------------------------------- the last guard

    @Test
    fun `the lift is not written as a subtraction of some chosen number`() =
        runBlocking<Unit> {
            // A second guard, not the argument. The tests above are what proves
            // the arithmetic; this only keeps a fixed offset from being put back
            // in a form they would still pass by luck on small fixtures.
            val dao = Files.readString(Path.of("src/commonMain/kotlin/dev/pnptracker/data/database/dao/ColorDao.kt"))
            val lifting =
                dao
                    .substringAfter(
                        "UPDATE task_colors SET slot_index",
                    ).substringBefore("suspend fun deleteColorTheUserHasConfirmed")

            assertTrue(":offset" !in lifting, "the lift takes a number to shift the slots by again")
            assertTrue(
                Regex("""slot_index [-+] \d""").find(lifting) == null,
                "the lift shifts the slots by a literal number again",
            )
        }

    private fun <T> permutationsOf(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.flatMap { head -> permutationsOf(items - head).map { listOf(head) + it } }
        }
}
