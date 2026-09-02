package dev.pnptracker.domain.search

import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.domain.pools.PoolColor
import dev.pnptracker.domain.pools.PoolModel
import dev.pnptracker.domain.pools.PoolSnapshot
import dev.pnptracker.domain.pools.PoolStage
import dev.pnptracker.domain.pools.PoolTask
import dev.pnptracker.domain.pools.poolModelOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a pool shows once the user has narrowed it.
 *
 * All of it is arithmetic over a list that has already been read, which is the
 * whole point: PLAN 16 will not have the number of queries grow with what
 * somebody is looking at, so every rule here has to hold without a database
 * anywhere near it.
 *
 * The awkward cases are the ones about a task made in several colours. PLAN 12.7
 * gives it one identity and PLAN 12.10 puts it in each of its colour groups, so
 * a filter that matched it must leave it whole — in every group it belongs to,
 * once each, still the same task.
 */
class PoolFilterTest {
    private val grey = PoolColor(IdGenerator.Random.newId(), "Gri", "#808080", sortOrder = 2)
    private val red = PoolColor(IdGenerator.Random.newId(), "Kırmızı", "#C62828", sortOrder = 4)
    private val green = PoolColor(IdGenerator.Random.newId(), "Yeşil", "#2E7D32", sortOrder = 5)

    private fun task(
        name: String = "Kırmızı ev",
        gameName: String = "Harmonies",
        colors: List<PoolColor> = listOf(red),
        isCompleted: Boolean = false,
        needsInfo: Boolean = false,
        needsClassification: Boolean = false,
        isMissing: Boolean = false,
        isBorrowed: Boolean = false,
        missingQuantity: Int = 0,
        failureTotal: Long = 0L,
        stages: List<PoolStage> = emptyList(),
        requiredQuantity: Int? = 10,
        taskId: EntityId = IdGenerator.Random.newId(),
        gameId: EntityId = IdGenerator.Random.newId(),
    ) = PoolTask(
        taskId = taskId,
        segmentId = IdGenerator.Random.newId(),
        cellId = IdGenerator.Random.newId(),
        gameId = gameId,
        gameName = gameName,
        name = name,
        requiredQuantity = requiredQuantity,
        notes = "gizli not",
        trackingMode = TrackingMode.THREE_D_BATCH,
        primaryBatchCompleted = false,
        currentMissingQuantity = missingQuantity,
        isCompleted = isCompleted,
        colors = colors,
        stages = stages,
        failureTotal = failureTotal,
        isMissing = isMissing,
        isBorrowed = isBorrowed,
        needsInfo = needsInfo,
        needsClassification = needsClassification,
    )

    private fun kept(
        tasks: List<PoolTask>,
        filter: PoolFilter,
    ): List<String> = filterPoolTasks(tasks, filter).map { it.name }

    // ------------------------------------------------------------- searching

    @Test
    fun `a task is found by its own name`() {
        val tasks = listOf(task(name = "Kırmızı ev"), task(name = "Mavi çatı"))

        assertEquals(listOf("Kırmızı ev"), kept(tasks, PoolFilter(query = SearchQuery("kırmızı"))))
    }

    @Test
    fun `a task is found by the name of the game it is in`() {
        val tasks = listOf(task(name = "Ev", gameName = "Harmonies"), task(name = "Çatı", gameName = "Wingspan"))

        assertEquals(listOf("Çatı"), kept(tasks, PoolFilter(query = SearchQuery("wingspan"))))
    }

    @Test
    fun `an empty query keeps every active task`() {
        val tasks = listOf(task(name = "Ev"), task(name = "Çatı"))

        assertEquals(listOf("Ev", "Çatı"), kept(tasks, PoolFilter.NONE))
    }

    @Test
    fun `the note and the colour name are not searched`() {
        val tasks = listOf(task(name = "Ev", colors = listOf(red)))

        assertEquals(emptyList(), kept(tasks, PoolFilter(query = SearchQuery("gizli"))), "the note was searched")
        assertEquals(emptyList(), kept(tasks, PoolFilter(query = SearchQuery("kırmızı"))), "the colour name was searched")
    }

    // --------------------------------------------------------------- colours

    @Test
    fun `a colour keeps the tasks made in it and nothing else`() {
        val tasks = listOf(task(name = "Kırmızı ev", colors = listOf(red)), task(name = "Gri zar", colors = listOf(grey)))

        assertEquals(listOf("Kırmızı ev"), kept(tasks, PoolFilter(colorIds = setOf(red.colorId))))
    }

    @Test
    fun `two colours keep the tasks made in either of them`() {
        val tasks =
            listOf(
                task(name = "Kırmızı ev", colors = listOf(red)),
                task(name = "Gri zar", colors = listOf(grey)),
                task(name = "Yeşil ağaç", colors = listOf(green)),
            )

        assertEquals(
            listOf("Kırmızı ev", "Gri zar"),
            kept(tasks, PoolFilter(colorIds = setOf(red.colorId, grey.colorId))),
        )
    }

    @Test
    fun `a task of several colours passes on any one of them`() {
        val several = task(name = "Gri yeşil ev", colors = listOf(grey, green))
        val tasks = listOf(several, task(name = "Kırmızı ev", colors = listOf(red)))

        assertEquals(listOf("Gri yeşil ev"), kept(tasks, PoolFilter(colorIds = setOf(grey.colorId))))
        assertEquals(listOf("Gri yeşil ev"), kept(tasks, PoolFilter(colorIds = setOf(green.colorId))))
    }

    @Test
    fun `a task with no colour at all is found only by asking for it`() {
        val bare = task(name = "Renksiz", colors = emptyList())
        val tasks = listOf(bare, task(name = "Kırmızı ev", colors = listOf(red)))

        assertEquals(listOf("Renksiz"), kept(tasks, PoolFilter(awaitingColor = true)))
        assertEquals(listOf("Kırmızı ev"), kept(tasks, PoolFilter(colorIds = setOf(red.colorId))))
    }

    @Test
    fun `a real colour and no colour together widen the answer`() {
        val tasks =
            listOf(
                task(name = "Renksiz", colors = emptyList()),
                task(name = "Kırmızı ev", colors = listOf(red)),
                task(name = "Gri zar", colors = listOf(grey)),
            )

        assertEquals(
            listOf("Renksiz", "Kırmızı ev"),
            kept(tasks, PoolFilter(colorIds = setOf(red.colorId), awaitingColor = true)),
        )
    }

    // ----------------------------------------------------------- the states

    @Test
    fun `the three states are told apart`() {
        val active = task(name = "Açık")
        val done = task(name = "Bitmiş", isCompleted = true)
        val unknown = task(name = "Bilinmiyor", needsInfo = true)
        val tasks = listOf(active, done, unknown)

        assertEquals(listOf("Açık"), kept(tasks, PoolFilter(state = TaskStateFilter.ACTIVE)))
        assertEquals(listOf("Bitmiş"), kept(tasks, PoolFilter(state = TaskStateFilter.COMPLETED)))
        assertEquals(listOf("Bilinmiyor"), kept(tasks, PoolFilter(state = TaskStateFilter.NEEDS_INFO)))
    }

    @Test
    fun `a finished task that also waits on information is counted as waiting`() {
        val both = task(name = "İkisi de", isCompleted = true, needsInfo = true)

        assertEquals(TaskStateFilter.NEEDS_INFO, TaskStateFilter.of(both))
    }

    /**
     * PLAN 10 keeps an unclassified task out of every pool, so a task that is in
     * one has been classified: the mark is provenance and not a blocker. The
     * pool's own active count has always agreed, and this holds the two together.
     */
    @Test
    fun `a task the user classified by hand is ordinary active work`() {
        val classified = task(name = "Sınıflandırılmış", needsClassification = true)

        assertEquals(TaskStateFilter.ACTIVE, TaskStateFilter.of(classified))
        assertEquals(listOf("Sınıflandırılmış"), kept(listOf(classified), PoolFilter.NONE))
    }

    // ------------------------------------------------------------ the marks

    @Test
    fun `the missing mark keeps only the tasks that carry it`() {
        val tasks = listOf(task(name = "Eksik", isMissing = true), task(name = "Sıradan"))

        assertEquals(listOf("Eksik"), kept(tasks, PoolFilter(flags = setOf(TaskFlagFilter.MISSING))))
    }

    @Test
    fun `the borrowed mark keeps only the tasks that carry it`() {
        val tasks = listOf(task(name = "Ödünç", isBorrowed = true), task(name = "Sıradan"))

        assertEquals(listOf("Ödünç"), kept(tasks, PoolFilter(flags = setOf(TaskFlagFilter.BORROWED))))
    }

    @Test
    fun `both marks together keep a task carrying either one`() {
        val tasks =
            listOf(
                task(name = "Eksik", isMissing = true),
                task(name = "Ödünç", isBorrowed = true),
                task(name = "Sıradan"),
            )

        assertEquals(
            listOf("Eksik", "Ödünç"),
            kept(tasks, PoolFilter(flags = setOf(TaskFlagFilter.MISSING, TaskFlagFilter.BORROWED))),
        )
    }

    @Test
    fun `the missing mark is not the shortage counter`() {
        val marked = task(name = "İşaretli", isMissing = true)
        val owing = task(name = "Borçlu", missingQuantity = 3)

        assertEquals(listOf("İşaretli"), kept(listOf(marked, owing), PoolFilter(flags = setOf(TaskFlagFilter.MISSING))))
        assertFalse(marked.hasCurrentShortage)
        assertTrue(owing.hasCurrentShortage)
    }

    // ----------------------------------------------------------- the stages

    @Test
    fun `a card is matched by the step it is waiting at`() {
        val printing = task(name = "Basılacak", stages = cardStages(0, 0, 0))
        val laminating = task(name = "Lamine edilecek", stages = cardStages(10, 0, 0))
        val tasks = listOf(printing, laminating)

        assertEquals(listOf("Basılacak"), kept(tasks, PoolFilter(stages = setOf(ProductionStage.PRINT))))
        assertEquals(listOf("Lamine edilecek"), kept(tasks, PoolFilter(stages = setOf(ProductionStage.LAMINATE))))
    }

    @Test
    fun `a board piece is matched by its own middle step`() {
        val gluing = task(name = "Yapıştırılacak", stages = boardStages(10, 0, 0))

        assertEquals(listOf("Yapıştırılacak"), kept(listOf(gluing), PoolFilter(stages = setOf(ProductionStage.GLUE))))
        assertEquals(emptyList(), kept(listOf(gluing), PoolFilter(stages = setOf(ProductionStage.LAMINATE))))
    }

    @Test
    fun `two steps together keep a task waiting at either`() {
        val tasks =
            listOf(
                task(name = "Basılacak", stages = cardStages(0, 0, 0)),
                task(name = "Kesilecek", stages = cardStages(10, 10, 0)),
                task(name = "Lamine edilecek", stages = cardStages(10, 0, 0)),
            )

        assertEquals(
            listOf("Basılacak", "Kesilecek"),
            kept(tasks, PoolFilter(stages = setOf(ProductionStage.PRINT, ProductionStage.CUT))),
        )
    }

    @Test
    fun `a task whose whole pipeline is done matches no step`() {
        val done = task(name = "Bitmiş hat", stages = cardStages(10, 10, 10))

        assertEquals(null, done.firstUnfinishedStage)
        ProductionStage.entries.forEach { stage ->
            assertEquals(emptyList(), kept(listOf(done), PoolFilter(stages = setOf(stage))), "$stage matched a finished pipeline")
        }
    }

    // ------------------------------------------------- how they fit together

    @Test
    fun `different kinds of choice narrow together`() {
        val wanted = task(name = "Kırmızı ev", colors = listOf(red), isMissing = true)
        val tasks =
            listOf(
                wanted,
                task(name = "Kırmızı çatı", colors = listOf(red)),
                task(name = "Gri ev", colors = listOf(grey), isMissing = true),
            )

        val filter =
            PoolFilter(
                query = SearchQuery("ev"),
                colorIds = setOf(red.colorId),
                flags = setOf(TaskFlagFilter.MISSING),
            )
        assertEquals(listOf("Kırmızı ev"), kept(tasks, filter))
    }

    @Test
    fun `the ordinary view asks for nothing and counts nothing`() {
        assertFalse(PoolFilter.NONE.isNarrowed)
        assertEquals(0, PoolFilter.NONE.chosenCount)
        assertTrue(PoolFilter(state = TaskStateFilter.COMPLETED).isNarrowed)
        assertEquals(1, PoolFilter(state = TaskStateFilter.COMPLETED).chosenCount)
        assertEquals(3, PoolFilter(colorIds = setOf(red.colorId, grey.colorId), awaitingColor = true).chosenCount)
    }

    // --------------------------------------------- what the layout does after

    @Test
    fun `a filtered multi-colour task keeps its identity and both its groups`() {
        val several = task(name = "Gri yeşil ev", colors = listOf(grey, green))
        val snapshot =
            PoolSnapshot(
                PoolType.THREE_D,
                listOf(several, task(name = "Kırmızı ev", colors = listOf(red))),
            )

        val kept = filterPoolTasks(snapshot.tasks, PoolFilter(colorIds = setOf(grey.colorId)))
        val model = poolModelOf(snapshot.copy(tasks = kept)) as PoolModel.ThreeD

        // In both of its groups, once each, and the same task in both.
        val groups = model.sections.multicolorGroups
        assertEquals(listOf(grey.colorId, green.colorId), groups.map { it.color.colorId })
        groups.forEach { group ->
            assertEquals(listOf(several.taskId), group.tasks.map { it.taskId }, "the task was duplicated or lost")
        }
        assertTrue(model.sections.singleColorGroups.isEmpty(), "a group the filter emptied was still drawn")
    }

    @Test
    fun `a group the filter empties is not drawn at all`() {
        val snapshot =
            PoolSnapshot(
                PoolType.THREE_D,
                listOf(task(name = "Kırmızı ev", colors = listOf(red)), task(name = "Gri zar", colors = listOf(grey))),
            )

        val kept = filterPoolTasks(snapshot.tasks, PoolFilter(colorIds = setOf(red.colorId)))
        val model = poolModelOf(snapshot.copy(tasks = kept)) as PoolModel.ThreeD

        assertEquals(listOf(red.colorId), model.sections.singleColorGroups.map { it.color.colorId })
    }

    @Test
    fun `bringing shortages forward moves rows and keeps the order under them`() {
        // One game, so the task name is what settles the order under the first
        // key rather than an identifier nobody chose.
        val game = IdGenerator.Random.newId()
        val owing = task(name = "Borçlu", colors = listOf(red), missingQuantity = 2, gameId = game)
        val quietA = task(name = "Ada", colors = listOf(red), gameId = game)
        val quietB = task(name = "Bade", colors = listOf(red), gameId = game)
        val snapshot = PoolSnapshot(PoolType.THREE_D, listOf(quietA, quietB, owing))

        val ordinary = poolModelOf(snapshot) as PoolModel.ThreeD
        val brought = poolModelOf(snapshot, shortagesFirst = true) as PoolModel.ThreeD

        // The ordinary order already puts what has gone wrong first, so this pair
        // is about the rest staying exactly where it was.
        assertEquals(
            listOf("Borçlu", "Ada", "Bade"),
            ordinary.sections.singleColorGroups
                .single()
                .tasks
                .map { it.name },
        )
        assertEquals(
            listOf("Borçlu", "Ada", "Bade"),
            brought.sections.singleColorGroups
                .single()
                .tasks
                .map { it.name },
        )
    }

    @Test
    fun `bringing shortages forward lifts a task the ordinary order left behind`() {
        // A task that failed once and was made good owes nothing now, so the
        // ordinary order still brings it forward; asking for current shortages
        // does not.
        val game = IdGenerator.Random.newId()
        val healed = task(name = "Ada", colors = listOf(red), failureTotal = 4L, gameId = game)
        val owing = task(name = "Zeki", colors = listOf(red), missingQuantity = 1, gameId = game)
        val snapshot = PoolSnapshot(PoolType.THREE_D, listOf(healed, owing))

        val ordinary =
            (poolModelOf(snapshot) as PoolModel.ThreeD)
                .sections.singleColorGroups
                .single()
                .tasks
                .map { it.name }
        val brought =
            (poolModelOf(snapshot, shortagesFirst = true) as PoolModel.ThreeD)
                .sections.singleColorGroups
                .single()
                .tasks
                .map { it.name }

        assertEquals(listOf("Ada", "Zeki"), ordinary)
        assertEquals(listOf("Zeki", "Ada"), brought)
    }

    private fun cardStages(
        printed: Int,
        laminated: Int,
        cut: Int,
    ) = listOf(
        PoolStage(ProductionStage.PRINT, printed),
        PoolStage(ProductionStage.LAMINATE, laminated),
        PoolStage(ProductionStage.CUT, cut),
    )

    private fun boardStages(
        printed: Int,
        glued: Int,
        cut: Int,
    ) = listOf(
        PoolStage(ProductionStage.PRINT, printed),
        PoolStage(ProductionStage.GLUE, glued),
        PoolStage(ProductionStage.CUT, cut),
    )
}
