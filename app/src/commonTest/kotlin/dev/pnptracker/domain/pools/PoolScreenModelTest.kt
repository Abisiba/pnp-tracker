package dev.pnptracker.domain.pools

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a pool is laid out, decided without a database.
 *
 * This is where PLAN 12.10's three sections, its colour groups and its order
 * live, so it is where they are measured. Everything here is a pure function of
 * a list of tasks: the same list always lays out the same way, which is what
 * makes the order on screen something a test can hold to rather than something
 * SQLite happened to return.
 */
class PoolScreenModelTest {
    private val red = PoolColor(IdGenerator.Random.newId(), "Kırmızı", "#E53935", sortOrder = 4)
    private val yellow = PoolColor(IdGenerator.Random.newId(), "Sarı", "#FDD835", sortOrder = 5)
    private val black = PoolColor(IdGenerator.Random.newId(), "Siyah", "#111111", sortOrder = 1)

    private fun task(
        name: String = "Token",
        game: String = "Harmonies",
        gameId: EntityId = IdGenerator.Random.newId(),
        taskId: EntityId = IdGenerator.Random.newId(),
        quantity: Int? = 14,
        colors: List<PoolColor> = emptyList(),
        stages: List<PoolStage> = emptyList(),
        missing: Int = 0,
        failures: Long = 0L,
        tracking: TrackingMode = TrackingMode.THREE_D_BATCH,
    ) = PoolTask(
        taskId = taskId,
        segmentId = IdGenerator.Random.newId(),
        cellId = IdGenerator.Random.newId(),
        gameId = gameId,
        gameName = game,
        name = name,
        requiredQuantity = quantity,
        notes = null,
        trackingMode = tracking,
        primaryBatchCompleted = false,
        currentMissingQuantity = missing,
        colors = colors,
        stages = stages,
        failureTotal = failures,
    )

    private fun threeD(vararg tasks: PoolTask) = (poolModelOf(PoolSnapshot(PoolType.THREE_D, tasks.toList())) as PoolModel.ThreeD).sections

    private fun flat(
        poolType: PoolType,
        vararg tasks: PoolTask,
    ) = (poolModelOf(PoolSnapshot(poolType, tasks.toList())) as PoolModel.Flat).tasks

    // --------------------------------------------------------- the sections

    @Test
    fun `a task with no colour is only ever in the first section`() {
        val sections = threeD(task(name = "Meeple"))

        assertEquals(listOf("Meeple"), sections.awaitingColor.tasks.map { it.name })
        assertEquals(emptyList(), sections.singleColorGroups)
        assertEquals(emptyList(), sections.multicolorGroups)
    }

    @Test
    fun `a task with one colour is only ever in the single colour section`() {
        val sections = threeD(task(colors = listOf(red)))

        assertTrue(sections.awaitingColor.isEmpty)
        assertEquals(listOf("Kırmızı"), sections.singleColorGroups.map { it.color.canonicalName })
        assertEquals(emptyList(), sections.multicolorGroups)
    }

    @Test
    fun `a task with two colours is only ever in the several colour section`() {
        val sections = threeD(task(colors = listOf(red, yellow)))

        assertEquals(emptyList(), sections.singleColorGroups, "a several-colour task was listed as a single-colour one")
        assertEquals(listOf("Kırmızı", "Sarı"), sections.multicolorGroups.map { it.color.canonicalName })
    }

    @Test
    fun `a task with three colours is only ever in the several colour section`() {
        val sections = threeD(task(colors = listOf(red, yellow, black)))

        assertEquals(emptyList(), sections.singleColorGroups)
        assertEquals(3, sections.multicolorGroups.size)
    }

    @Test
    fun `the three sections come in the order the plan lists them`() {
        val model =
            poolModelOf(
                PoolSnapshot(
                    PoolType.THREE_D,
                    listOf(task(colors = listOf(red, yellow)), task(colors = listOf(red)), task()),
                ),
            ) as PoolModel.ThreeD

        // PLAN 12.10: colourless first, then single-colour, then several.
        assertTrue(!model.sections.awaitingColor.isEmpty)
        assertEquals(1, model.sections.singleColorGroups.size)
        assertEquals(2, model.sections.multicolorGroups.size)
    }

    // ------------------------------------------------- one task, many groups

    @Test
    fun `a task made in three colours is in three groups under one identity`() {
        // PLAN 12.7 and 12.10: `Yarasa`, red and yellow and black, ×10, one task.
        val yarasa = task(name = "Yarasa", quantity = 10, colors = listOf(red, yellow, black))

        val groups = threeD(yarasa).multicolorGroups

        assertEquals(3, groups.size)
        groups.forEach { group ->
            assertEquals(listOf(yarasa.taskId), group.tasks.map { it.taskId }, "a group holds something else")
        }
    }

    @Test
    fun `no group holds the same task twice`() {
        val yarasa = task(colors = listOf(red, yellow, black))

        threeD(yarasa).multicolorGroups.forEach { group ->
            assertEquals(
                group.tasks.size,
                group.tasks
                    .map { it.taskId }
                    .toSet()
                    .size,
            )
        }
    }

    @Test
    fun `three tasks made from one batch keep three identities`() {
        val a = task(name = "Token", quantity = 14, colors = listOf(black))
        val b = task(name = "Token", quantity = 15, colors = listOf(red))
        val c = task(name = "Token", quantity = 8, colors = listOf(yellow))

        val groups = threeD(a, b, c).singleColorGroups

        assertEquals(
            listOf(a.taskId, b.taskId, c.taskId).toSet(),
            groups.flatMap { group -> group.tasks.map { it.taskId } }.toSet(),
        )
        assertEquals(3, groups.size, "three separate tasks were folded into fewer groups")
    }

    @Test
    fun `losing a colour takes only that membership away`() {
        val before = task(colors = listOf(red, yellow))
        val after = before.copy(colors = listOf(red))

        assertEquals(2, threeD(before).multicolorGroups.size)
        // Down to one colour it is an ordinary single-colour task, in one group.
        assertEquals(emptyList(), threeD(after).multicolorGroups)
        assertEquals(listOf("Kırmızı"), threeD(after).singleColorGroups.map { it.color.canonicalName })
    }

    @Test
    fun `losing the last colour moves the task to the first section`() {
        val stripped = task(name = "Tek renkli", colors = listOf(red)).copy(colors = emptyList())

        val sections = threeD(stripped)

        assertEquals(listOf("Tek renkli"), sections.awaitingColor.tasks.map { it.name })
        assertEquals(emptyList(), sections.singleColorGroups)
    }

    @Test
    fun `a group follows the colour's identity and not its name`() {
        val renamed = red.copy(canonicalName = "Vişne")

        val groups = threeD(task(colors = listOf(renamed))).singleColorGroups

        assertEquals(red.colorId, groups.single().color.colorId, "the group changed when only the name did")
        assertEquals("Vişne", groups.single().color.canonicalName)
    }

    @Test
    fun `two colours written the same way are two groups`() {
        val twin = PoolColor(IdGenerator.Random.newId(), "Duman", red.hex, sortOrder = 99)

        val groups = threeD(task(colors = listOf(red)), task(colors = listOf(twin))).singleColorGroups

        assertEquals(2, groups.size, "two colours were merged because they share a value")
    }

    @Test
    fun `the order the colours are held in does not change which groups a task is in`() {
        val forwards = task(taskId = IdGenerator.Random.newId(), colors = listOf(red, yellow, black))
        val backwards = forwards.copy(colors = listOf(black, yellow, red))

        assertEquals(
            threeD(forwards).multicolorGroups.map { it.color.colorId },
            threeD(backwards).multicolorGroups.map { it.color.colorId },
        )
    }

    // --------------------------------------------------------- the totals

    @Test
    fun `a task made in three colours adds its total to each group once`() {
        val yarasa = task(quantity = 10, colors = listOf(red, yellow, black))

        threeD(yarasa).multicolorGroups.forEach { group ->
            assertEquals(1, group.taskCount)
            assertEquals(10, group.requiredTotal, "one task was counted more than once in its own group")
        }
    }

    @Test
    fun `two tasks that need the same number are both counted`() {
        // The trap a `SUM(DISTINCT)` falls into: two different tasks needing ten
        // each are twenty, not ten.
        val group =
            threeD(task(quantity = 10, colors = listOf(red)), task(quantity = 10, colors = listOf(red)))
                .singleColorGroups
                .single()

        assertEquals(2, group.taskCount)
        assertEquals(20, group.requiredTotal)
    }

    @Test
    fun `a task with no total does not stop the others being added up`() {
        val group =
            threeD(task(quantity = null, colors = listOf(red)), task(quantity = 7, colors = listOf(red)))
                .singleColorGroups
                .single()

        assertEquals(2, group.taskCount)
        assertEquals(7, group.requiredTotal)
    }

    @Test
    fun `what is owed and what went wrong are added up per group`() {
        val group =
            threeD(
                task(colors = listOf(red), missing = 3, failures = 5),
                task(colors = listOf(red), missing = 1, failures = 2),
            ).singleColorGroups
                .single()

        assertEquals(4, group.missingTotal)
        assertEquals(7L, group.failureTotal)
    }

    @Test
    fun `the colourless section adds up the same way`() {
        val section = threeD(task(quantity = 4, missing = 2), task(quantity = 6, failures = 1)).awaitingColor

        assertEquals(2, section.taskCount)
        assertEquals(10, section.requiredTotal)
        assertEquals(2, section.missingTotal)
        assertEquals(1L, section.failureTotal)
    }

    // ---------------------------------------------------------- the order

    @Test
    fun `colour groups come in the user's own colour order`() {
        val groups =
            threeD(
                task(colors = listOf(yellow)),
                task(colors = listOf(black)),
                task(colors = listOf(red)),
            ).singleColorGroups

        assertEquals(listOf("Siyah", "Kırmızı", "Sarı"), groups.map { it.color.canonicalName })
    }

    @Test
    fun `two colours placed at the same spot keep a settled order`() {
        val tied = yellow.copy(colorId = IdGenerator.Random.newId(), canonicalName = "Altın", sortOrder = red.sortOrder)
        val one = threeD(task(colors = listOf(red)), task(colors = listOf(tied))).singleColorGroups
        val other = threeD(task(colors = listOf(tied)), task(colors = listOf(red))).singleColorGroups

        assertEquals(one.map { it.color.colorId }, other.map { it.color.colorId })
    }

    @Test
    fun `what has gone wrong is listed before what has not`() {
        val game = IdGenerator.Random.newId()
        val fine = task(name = "Aaa", gameId = game, colors = listOf(red))
        val short = task(name = "Zzz", gameId = game, colors = listOf(red), missing = 2)
        val failed = task(name = "Yyy", gameId = game, colors = listOf(red), failures = 1)

        val order =
            threeD(fine, short, failed)
                .singleColorGroups
                .single()
                .tasks
                .map { it.name }

        assertEquals(listOf("Yyy", "Zzz", "Aaa"), order)
    }

    @Test
    fun `tasks are settled by game then by name then by identity`() {
        val gameA = IdGenerator.Random.newId()
        val gameB = IdGenerator.Random.newId()
        val first = task(name = "Bbb", game = "Everdell", gameId = gameA, colors = listOf(red))
        val second = task(name = "Aaa", game = "Harmonies", gameId = gameB, colors = listOf(red))

        val order =
            threeD(second, first)
                .singleColorGroups
                .single()
                .tasks
                .map { it.gameName to it.name }

        assertEquals(listOf("Everdell" to "Bbb", "Harmonies" to "Aaa"), order)
    }

    @Test
    fun `two tasks that read the same come out the same way twice`() {
        val gameId = IdGenerator.Random.newId()
        val a = task(name = "Token", gameId = gameId, colors = listOf(red))
        val b = task(name = "Token", gameId = gameId, colors = listOf(red))

        val one =
            threeD(a, b)
                .singleColorGroups
                .single()
                .tasks
                .map { it.taskId }
        val other =
            threeD(b, a)
                .singleColorGroups
                .single()
                .tasks
                .map { it.taskId }

        assertEquals(one, other, "the order of two equal tasks was left to the order they arrived in")
    }

    @Test
    fun `case is not what decides which name comes first`() {
        val game = IdGenerator.Random.newId()
        val lower = task(name = "ağaç", gameId = game, colors = listOf(red))
        val upper = task(name = "Ağaç kütüğü", gameId = game, colors = listOf(red))

        val order =
            threeD(upper, lower)
                .singleColorGroups
                .single()
                .tasks
                .map { it.name }

        assertEquals(listOf("ağaç", "Ağaç kütüğü"), order)
    }

    // ------------------------------------------------------- the pipelines

    @Test
    fun `pipeline tasks with work left come before ones with none`() {
        val done =
            task(
                name = "Bitti",
                quantity = 4,
                stages = listOf(PoolStage(ProductionStage.PRINT, 4), PoolStage(ProductionStage.CUT, 4)),
                tracking = TrackingMode.PIPELINE,
            )
        val waiting =
            task(
                name = "Bekliyor",
                quantity = 4,
                stages = listOf(PoolStage(ProductionStage.PRINT, 0), PoolStage(ProductionStage.CUT, 0)),
                tracking = TrackingMode.PIPELINE,
            )

        val order = flat(PoolType.CARD, done, waiting).map { it.name }

        assertEquals(listOf("Bekliyor", "Bitti"), order)
    }

    @Test
    fun `the earliest unfinished stage comes first`() {
        val toCut =
            task(
                name = "Kesilecek",
                quantity = 4,
                stages =
                    listOf(
                        PoolStage(ProductionStage.PRINT, 4),
                        PoolStage(ProductionStage.LAMINATE, 4),
                        PoolStage(ProductionStage.CUT, 0),
                    ),
                tracking = TrackingMode.PIPELINE,
            )
        val toPrint =
            task(
                name = "Basılacak",
                quantity = 4,
                stages =
                    listOf(
                        PoolStage(ProductionStage.PRINT, 0),
                        PoolStage(ProductionStage.LAMINATE, 0),
                        PoolStage(ProductionStage.CUT, 0),
                    ),
                tracking = TrackingMode.PIPELINE,
            )

        assertEquals(listOf("Basılacak", "Kesilecek"), flat(PoolType.CARD, toCut, toPrint).map { it.name })
    }

    @Test
    fun `the badge names the first stage that is not finished`() {
        val half =
            task(
                quantity = 10,
                stages =
                    listOf(
                        PoolStage(ProductionStage.PRINT, 10),
                        PoolStage(ProductionStage.GLUE, 3),
                        PoolStage(ProductionStage.CUT, 0),
                    ),
            )

        assertEquals(ProductionStage.GLUE, half.firstUnfinishedStage)
    }

    @Test
    fun `a task with every stage finished has no stage left to name`() {
        val done =
            task(
                quantity = 10,
                stages = listOf(PoolStage(ProductionStage.PRINT, 10), PoolStage(ProductionStage.CUT, 10)),
            )

        assertEquals(null, done.firstUnfinishedStage)
    }

    @Test
    fun `a task with no total counts a stage as begun rather than finished`() {
        val started =
            task(
                quantity = null,
                stages = listOf(PoolStage(ProductionStage.PRINT, 2), PoolStage(ProductionStage.CUT, 0)),
            )

        assertEquals(ProductionStage.CUT, started.firstUnfinishedStage)
    }

    // ----------------------------------------------------- the flat pools

    @Test
    fun `the card pool is one list and never colour groups`() {
        val model = poolModelOf(PoolSnapshot(PoolType.CARD, listOf(task(colors = listOf(red)))))

        assertTrue(model is PoolModel.Flat)
    }

    @Test
    fun `the board pool is one list and never colour groups`() {
        assertTrue(poolModelOf(PoolSnapshot(PoolType.BOARD, listOf(task()))) is PoolModel.Flat)
    }

    @Test
    fun `the special pool is one list and never colour groups`() {
        assertTrue(poolModelOf(PoolSnapshot(PoolType.SPECIAL, listOf(task()))) is PoolModel.Flat)
    }

    @Test
    fun `special tasks are ordered by what has gone wrong and then by name`() {
        val fine = task(name = "Aaa", tracking = TrackingMode.CHECKLIST, quantity = null)
        val short = task(name = "Zzz", tracking = TrackingMode.COUNTED, missing = 1)

        assertEquals(listOf("Zzz", "Aaa"), flat(PoolType.SPECIAL, fine, short).map { it.name })
    }

    @Test
    fun `an empty pool says so whatever kind it is`() {
        PoolType.entries.forEach { poolType ->
            assertTrue(poolModelOf(PoolSnapshot(poolType, emptyList())).isEmpty, "$poolType did not read as empty")
        }
    }

    @Test
    fun `the summary the sidebar reads keeps the special pool after its work is done`() {
        // PLAN 9: finishing the last special task does not hide the pool.
        val finished = PoolNavigationSummary(mapOf(PoolType.SPECIAL to PoolCounts(activeCount = 0, taskCount = 3)))

        assertTrue(finished.showsSpecial)
        assertEquals(0, finished.activeCountOf(PoolType.SPECIAL))
    }

    @Test
    fun `the summary hides the special pool only when there is nothing in it`() {
        assertTrue(!PoolNavigationSummary.EMPTY.showsSpecial)
        assertEquals(0, PoolNavigationSummary.EMPTY.activeCountOf(PoolType.THREE_D))
    }
}
