package dev.pnptracker.data.database

import androidx.room3.useReaderConnection
import dev.pnptracker.data.database.entity.TaskColorEntity
import dev.pnptracker.domain.model.ColorRelation
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tasks, their colors, and the three color models the plan describes. */
class TaskAndColorRelationTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(directory.databaseFile)
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    private suspend fun colorId(name: String) = assertNotNull(database.colorDao().resolve(name)).id

    @Test
    fun `an archived task leaves the active list but stays on disk`() =
        runBlocking<Unit> {
            val task = insertGameItemAndTask(database)

            assertEquals(1, database.taskDao().archive(task.id, updatedAt))

            assertEquals(emptyList(), database.taskDao().activeTasks())
            assertNull(database.taskDao().activeTaskById(task.id))
            val stored = assertNotNull(database.taskDao().taskByIdIncludingArchivedAndDeleted(task.id))
            assertTrue(stored.isArchived)
            assertEquals(updatedAt, stored.updatedAt)
        }

    @Test
    fun `deleting a task twice changes no timestamp`() =
        runBlocking<Unit> {
            val task = insertGameItemAndTask(database)
            database.taskDao().softDelete(task.id, deletedAt)

            val changedRows = database.taskDao().softDelete(task.id, createdAt)

            assertEquals(0, changedRows)
            val stored = assertNotNull(database.taskDao().taskByIdIncludingArchivedAndDeleted(task.id))
            assertEquals(deletedAt, stored.deletedAt)
            assertEquals(deletedAt, stored.updatedAt)
        }

    @Test
    fun `deleting the item or the game takes the task out of the active list`() =
        runBlocking<Unit> {
            val task = insertGameItemAndTask(database)
            val item = assertNotNull(database.itemDao().itemByIdIncludingDeleted(task.itemId))

            database.itemDao().softDelete(item.id, deletedAt)

            assertEquals(emptyList(), database.taskDao().activeTasks())
            assertNotNull(database.taskDao().taskByIdIncludingArchivedAndDeleted(task.id))

            val secondTask = insertGameItemAndTask(database, gameName = "Wingspan", itemName = "Bird Cards")
            assertEquals(listOf(secondTask.id), database.taskDao().activeTasks().map { it.id })
            val secondItem = assertNotNull(database.itemDao().itemByIdIncludingDeleted(secondTask.itemId))
            database.gameDao().softDelete(secondItem.gameId, deletedAt)

            assertEquals(emptyList(), database.taskDao().activeTasks())
            assertNotNull(database.taskDao().taskByIdIncludingArchivedAndDeleted(secondTask.id))
        }

    @Test
    fun `tasks can be listed per item and per pool`() =
        runBlocking<Unit> {
            val threeD = insertGameItemAndTask(database)
            val cards =
                insertGameItemAndTask(database, gameName = "Wingspan", itemName = "Bird Cards") { itemId ->
                    aTask(
                        itemId = itemId,
                        poolType = PoolType.CARD,
                        trackingMode = TrackingMode.PIPELINE,
                        name = "Bird Cards",
                        requiredQuantity = 170,
                    )
                }

            assertEquals(listOf(threeD.id), database.taskDao().activeTasksOfItem(threeD.itemId).map { it.id })
            assertEquals(
                listOf(threeD.id),
                database.taskDao().activeTasksInPool(PoolType.THREE_D).map { it.id },
            )
            assertEquals(listOf(cards.id), database.taskDao().activeTasksInPool(PoolType.CARD).map { it.id })
            assertEquals(emptyList(), database.taskDao().activeTasksInPool(PoolType.SPECIAL))
        }

    @Test
    fun `an unknown required quantity is allowed but zero or less is not`() =
        runBlocking<Unit> {
            val task =
                insertGameItemAndTask(database) { itemId -> aTask(itemId = itemId, requiredQuantity = null) }

            assertNull(assertNotNull(database.taskDao().activeTaskById(task.id)).requiredQuantity)
            assertFailsWith<IllegalArgumentException> { aTask(itemId = task.itemId, requiredQuantity = 0) }
            assertFailsWith<IllegalArgumentException> { aTask(itemId = task.itemId, requiredQuantity = -3) }
        }

    @Test
    fun `a blank task name is rejected`() =
        runBlocking<Unit> {
            val task = insertGameItemAndTask(database)

            assertFailsWith<IllegalArgumentException> { aTask(itemId = task.itemId, name = "  ") }
        }

    @Test
    fun `enums are stored as text names`() =
        runBlocking<Unit> {
            val task = insertGameItemAndTask(database)
            database.taskColorDao().addRelation(TaskColorEntity.required(task.id, colorId("Gri")))

            val stored =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared(
                        "SELECT typeof(pool_type), pool_type, typeof(tracking_mode), tracking_mode FROM tasks",
                    ) { statement ->
                        statement.step()
                        listOf(
                            statement.getText(0).uppercase(),
                            statement.getText(1),
                            statement.getText(2).uppercase(),
                            statement.getText(3),
                        )
                    }
                }
            val relation =
                database.useReaderConnection { transactor ->
                    transactor.usePrepared("SELECT typeof(relation), relation FROM task_colors") { statement ->
                        statement.step()
                        statement.getText(0).uppercase() to statement.getText(1)
                    }
                }

            assertEquals(listOf("TEXT", "THREE_D", "TEXT", "THREE_D_BATCH"), stored)
            assertEquals("TEXT" to "REQUIRED", relation)
        }

    @Test
    fun `color variants are three tasks each with its own quantity`() =
        runBlocking<Unit> {
            val grey = insertGameItemAndTask(database) { itemId -> aTask(itemId, name = "Gri token", requiredQuantity = 14) }
            val yellow = aTask(itemId = grey.itemId, name = "Sarı token", requiredQuantity = 15)
            val green = aTask(itemId = grey.itemId, name = "Yeşil token", requiredQuantity = 15)
            database.taskDao().insert(yellow)
            database.taskDao().insert(green)
            database.taskColorDao().addRelation(TaskColorEntity.required(grey.id, colorId("Gri")))
            database.taskColorDao().addRelation(TaskColorEntity.required(yellow.id, colorId("Sarı")))
            database.taskColorDao().addRelation(TaskColorEntity.required(green.id, colorId("Yeşil")))

            val tasks = database.taskDao().activeTasksOfItem(grey.itemId)

            assertEquals(3, tasks.size)
            assertEquals(listOf(14, 15, 15), tasks.sortedBy { it.name }.map { it.requiredQuantity })
            tasks.forEach { task ->
                val colors = database.taskColorDao().colorsOfTask(task.id)
                assertEquals(1, colors.size)
                assertEquals(ColorRelation.REQUIRED, colors.single().relation)
                assertTrue(colors.single().isSelected)
            }
        }

    @Test
    fun `a multi colored model is one task with two required colors and one quantity`() =
        runBlocking<Unit> {
            val sword =
                insertGameItemAndTask(database, itemName = "Kılıç") { itemId ->
                    aTask(itemId = itemId, name = "Kılıç", requiredQuantity = 10)
                }
            database.taskColorDao().addRelation(TaskColorEntity.required(sword.id, colorId("Gri")))
            database.taskColorDao().addRelation(TaskColorEntity.required(sword.id, colorId("Siyah")))

            val colors = database.taskColorDao().colorsOfTask(sword.id)

            assertEquals(1, database.taskDao().activeTasksOfItem(sword.itemId).size)
            assertEquals(10, assertNotNull(database.taskDao().activeTaskById(sword.id)).requiredQuantity)
            assertEquals(2, colors.size)
            assertTrue(colors.all { it.relation == ColorRelation.REQUIRED && it.isSelected })
        }

    @Test
    fun `alternative colors start unselected and picking one leaves exactly one selection`() =
        runBlocking<Unit> {
            val whale =
                insertGameItemAndTask(database, itemName = "Whale") { itemId ->
                    aTask(itemId = itemId, name = "Whale", requiredQuantity = 5)
                }
            val blue = colorId("Mavi")
            val lightBlue = colorId("Açık Mavi")
            database.taskColorDao().addRelation(TaskColorEntity.alternative(whale.id, blue))
            database.taskColorDao().addRelation(TaskColorEntity.alternative(whale.id, lightBlue))

            assertNull(database.taskColorDao().selectedAlternativeOfTask(whale.id))

            database.taskColorDao().selectAlternativeColor(whale.id, lightBlue)

            assertEquals(lightBlue, assertNotNull(database.taskColorDao().selectedAlternativeOfTask(whale.id)).colorId)
            assertEquals(1, database.taskColorDao().selectedColorsOfTask(whale.id).size)

            database.taskColorDao().selectAlternativeColor(whale.id, blue)

            assertEquals(blue, assertNotNull(database.taskColorDao().selectedAlternativeOfTask(whale.id)).colorId)
            assertEquals(1, database.taskColorDao().selectedColorsOfTask(whale.id).size)
        }

    @Test
    fun `picking a color that is not an alternative of the task rolls the whole change back`() =
        runBlocking<Unit> {
            val whale =
                insertGameItemAndTask(database, itemName = "Whale") { itemId ->
                    aTask(itemId = itemId, name = "Whale", requiredQuantity = 5)
                }
            val blue = colorId("Mavi")
            val lightBlue = colorId("Açık Mavi")
            database.taskColorDao().addRelation(TaskColorEntity.alternative(whale.id, blue))
            database.taskColorDao().addRelation(TaskColorEntity.alternative(whale.id, lightBlue))
            database.taskColorDao().selectAlternativeColor(whale.id, blue)

            assertFailsWith<IllegalArgumentException> {
                database.taskColorDao().selectAlternativeColor(whale.id, colorId("Pembe"))
            }

            assertEquals(blue, assertNotNull(database.taskColorDao().selectedAlternativeOfTask(whale.id)).colorId)
            assertEquals(1, database.taskColorDao().selectedColorsOfTask(whale.id).size)
        }

    @Test
    fun `a task cannot mix required and alternative colors`() =
        runBlocking<Unit> {
            val task = insertGameItemAndTask(database)
            database.taskColorDao().addRelation(TaskColorEntity.required(task.id, colorId("Gri")))

            assertFailsWith<IllegalArgumentException> {
                database.taskColorDao().addRelation(TaskColorEntity.alternative(task.id, colorId("Mavi")))
            }

            assertEquals(1, database.taskColorDao().colorsOfTask(task.id).size)
            assertEquals(
                emptyList(),
                database.taskColorDao().colorsOfTaskByRelation(task.id, ColorRelation.ALTERNATIVE),
            )
        }

    @Test
    fun `a required color cannot be stored as unselected`() {
        assertFailsWith<IllegalArgumentException> {
            TaskColorEntity(
                taskId = seedColors.first().id,
                colorId = seedColors.last().id,
                relation = ColorRelation.REQUIRED,
                isSelected = false,
            )
        }
    }
}
