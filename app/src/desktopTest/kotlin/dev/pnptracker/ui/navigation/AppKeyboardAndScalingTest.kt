package dev.pnptracker.ui.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.LiveBackupRestorer
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameSetupStore
import dev.pnptracker.data.repository.GameTableStore
import dev.pnptracker.data.repository.HistoryStore
import dev.pnptracker.data.repository.ImportConfirmationStore
import dev.pnptracker.data.repository.ImportDraftStore
import dev.pnptracker.data.repository.ImportReviewStore
import dev.pnptracker.data.repository.ImportRollbackStore
import dev.pnptracker.data.repository.PoolStore
import dev.pnptracker.data.repository.TaskEditStore
import dev.pnptracker.data.repository.TaskExportStore
import dev.pnptracker.data.repository.TaskFromTextStore
import dev.pnptracker.data.repository.TaskProgressStore
import dev.pnptracker.data.repository.UnfinishedImportsStore
import dev.pnptracker.domain.backup.BackupFileGateway
import dev.pnptracker.domain.backup.BackupFileHandle
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.automatic.VerifiedSnapshotTaker
import dev.pnptracker.domain.backup.restore.BackupInput
import dev.pnptracker.domain.backup.restore.BackupSourceGateway
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.backup.retention.AutomaticBackupRotation
import dev.pnptracker.domain.backup.retention.SettingsDrivenHousekeeping
import dev.pnptracker.domain.export.ExportFileGateway
import dev.pnptracker.domain.export.ExportFileHandle
import dev.pnptracker.domain.importprep.ImportFileGateway
import dev.pnptracker.domain.importprep.ImportFileHandle
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import dev.pnptracker.platform.backupfiles.DesktopBackupDirectory
import dev.pnptracker.platform.backupfiles.DesktopImportSnapshotWriter
import dev.pnptracker.platform.backupfiles.DesktopSafetyBackupWriter
import dev.pnptracker.platform.settings.DesktopSettingsStore
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.feature.colors.ColorCatalogueController
import dev.pnptracker.ui.feature.export.ExportController
import dev.pnptracker.ui.feature.export.exportNames
import dev.pnptracker.ui.feature.games.CellWork
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.history.HistoryController
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.importworkspace.ImportReviewController
import dev.pnptracker.ui.feature.importworkspace.ImportRollbackController
import dev.pnptracker.ui.feature.importworkspace.UnfinishedImportsController
import dev.pnptracker.ui.feature.pools.PoolCardKey
import dev.pnptracker.ui.feature.pools.PoolControllers
import dev.pnptracker.ui.feature.pools.PoolWork
import dev.pnptracker.ui.feature.settings.BackupController
import dev.pnptracker.ui.feature.settings.RestoreController
import dev.pnptracker.ui.feature.settings.RetentionController
import dev.pnptracker.ui.reads
import dev.pnptracker.ui.textsOf
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * PLAN 17 across the whole application: every screen the sidebar offers, composed
 * together as `Main` composes them, over real stores on a real temporary
 * database, driven by real key strokes at four sizes.
 *
 * The feature tests already prove each surface on its own (the matrix is in the
 * master context, §17). What none of them could see is the application as one
 * thing: whether the sidebar reaches every screen from the keyboard, whether a
 * walk with Tab on each screen comes back out rather than getting stuck, whether
 * every stop it lands on is drawn inside the window once it has been scrolled
 * to, whether Shift+Tab retraces Tab, and whether anything technical is written
 * anywhere — at the size `Main` opens, at its narrowest window at twice the
 * density, and with the text scaled up.
 *
 * Nothing here compares pixels. What is compared is the semantics tree: what can
 * take the keyboard, where it is drawn, and what it says.
 */
class AppKeyboardAndScalingTest {
    private lateinit var home: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private var realDatabaseExistedBefore = false
    private var gameId: EntityId = IdGenerator.Random.newId()
    private val taskOf = mutableMapOf<PoolType, EntityId>()

    private object NoImportFile : ImportFileGateway {
        override suspend fun chooseFile(): ImportFileHandle? = null
    }

    private object NoExportFile : ExportFileGateway {
        override suspend fun chooseDestination(suggestedName: String): ExportFileHandle? = null
    }

    private object NoBackupFile : BackupFileGateway {
        override suspend fun chooseDestination(suggestedName: String): BackupFileHandle? = null
    }

    private object NoBackupSource : BackupSourceGateway {
        override suspend fun chooseSource(): BackupInput? = null
    }

    private class Wiring(
        val navigation: AppNavigationState,
        val importController: ImportController,
        val reviewController: ImportReviewController,
        val confirmationController: ImportConfirmationController,
        val rollbackController: ImportRollbackController,
        val unfinishedController: UnfinishedImportsController,
        val gameTableController: GameTableController,
        val exportController: ExportController,
        val backupController: BackupController,
        val restoreController: RestoreController,
        val retentionController: RetentionController,
        val colorController: ColorCatalogueController,
        val poolControllers: PoolControllers,
        val historyController: HistoryController,
    )

    @BeforeTest
    fun setUp() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        home = TemporaryDatabaseDirectory()
        database = DatabaseFactory().open(home.databaseFile)
        runBlocking { seed() }
    }

    @AfterTest
    fun tearDown() {
        database.close()
        home.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        home.delete()
    }

    private suspend fun seed() {
        val game = aGame(name = "Işıklı Şövalyeler ve Ağaçların Gölgesindeki Üzüm Bağları: Genişletilmiş Sürüm")
        database.gameDao().insert(game)
        gameId = game.id
        val cells =
            CellColumnType.entries.associateWith { column ->
                aCell(gameId = game.id, columnType = column).also { database.gameCellDao().insert(it) }.id
            }
        val colour =
            database
                .colorDao()
                .allColors()
                .first()
                .id
        listOf(
            PoolType.THREE_D to TrackingMode.THREE_D_BATCH,
            PoolType.CARD to TrackingMode.PIPELINE,
            PoolType.BOARD to TrackingMode.PIPELINE,
            PoolType.SPECIAL to TrackingMode.CHECKLIST,
        ).forEach { (pool, mode) ->
            val task =
                aTask(poolType = pool, trackingMode = mode, name = "Çok uzun bir görev adı: büyük ağaç şövalyesi figürü ${pool.ordinal}")
            database.taskDao().addTaskToCell(task, cells.getValue(CellColumnType.of(pool)), IdGenerator.Random.newId(), createdAt)
            taskOf[pool] = task.id
            if (pool == PoolType.THREE_D) {
                database.taskColorDao().addColorToTask(task.id, colour)
                database.taskProgressDao().reportFailure(IdGenerator.Random.newId(), task.id, 2, StoppedClock(updatedAt))
            }
        }
    }

    private fun wiring(initial: Screen): Wiring {
        val data = home.root.resolve("data")
        val backups = data.resolve("backups")
        Files.createDirectories(backups)
        val settings = DesktopSettingsStore(home.root.resolve("config/settings.json"))
        val housekeeping = SettingsDrivenHousekeeping(settings, AutomaticBackupRotation(DesktopBackupDirectory(backups)))
        val exporter = DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System)
        val colorCatalogue = ColorCatalogueStore(database.colorDao())
        val taskProgress = TaskProgressStore(database.taskProgressDao())
        return Wiring(
            navigation = AppNavigationState(initial),
            importController = ImportController(NoImportFile, ImportDraftStore(database.importDao())),
            reviewController = ImportReviewController(ImportReviewStore(database.importDao(), database.gameDao(), database.colorDao())),
            confirmationController =
                ImportConfirmationController(
                    ImportConfirmationStore(
                        database = database,
                        importDao = database.importDao(),
                        gameCellDao = database.gameCellDao(),
                        gameDao = database.gameDao(),
                        snapshots =
                            VerifiedSnapshotTaker(
                                exporter = exporter,
                                writer = DesktopImportSnapshotWriter(backups),
                                reader = UntrustedBackupReader(TemporaryBackupProbe()),
                                clock = Clock.System,
                            ),
                        housekeeping = housekeeping,
                    ),
                ),
            rollbackController = ImportRollbackController(ImportRollbackStore(database.importDao())),
            unfinishedController = UnfinishedImportsController(UnfinishedImportsStore(database, database.importDao())),
            gameTableController =
                GameTableController(
                    table = GameTableStore(database.gameDao(), database.gameCellDao(), database.gameTableDao()),
                    setup = GameSetupStore(database.gameDao(), database.gameCellDao()),
                    cells = CellTextStore(database.cellSegmentDao()),
                    colors = colorCatalogue,
                    taskCreation = TaskFromTextStore(database.taskFromTextDao()),
                    taskEditing = TaskEditStore(database.taskEditDao()),
                    taskProgress = taskProgress,
                ),
            exportController = ExportController(NoExportFile, TaskExportStore(database.taskExportDao()), ::exportNames),
            backupController = BackupController(NoBackupFile, exporter, Clock.System),
            restoreController =
                RestoreController(
                    sources = NoBackupSource,
                    reader = UntrustedBackupReader(TemporaryBackupProbe()),
                    exporter = exporter,
                    safety = DesktopSafetyBackupWriter(backups),
                    restorer = LiveBackupRestorer(database),
                    housekeeping = housekeeping,
                    clock = Clock.System,
                ),
            retentionController = RetentionController(settings),
            colorController = ColorCatalogueController(colorCatalogue),
            poolControllers =
                PoolControllers(
                    pools = PoolStore(database.poolDao()),
                    colors = colorCatalogue,
                    taskEditing = TaskEditStore(database.taskEditDao()),
                    taskProgress = taskProgress,
                ),
            historyController = HistoryController(HistoryStore(database.historyDao())),
        )
    }

    private class Viewport(
        val name: String,
        val widthDp: Int,
        val heightDp: Int,
        val density: Float,
        val fontScale: Float,
    ) {
        val width get() = (widthDp * density).toInt()
        val height get() = (heightDp * density).toInt()
    }

    /** The window `Main` opens, its narrowest allowed window at twice the density, and larger text on both. */
    private val viewports =
        listOf(
            Viewport("1100×720 dp, 1×", 1100, 720, 1f, 1f),
            Viewport("640×460 dp (Main's minimum), 2×", 640, 460, 2f, 1f),
            Viewport("1100×720 dp, text ×1.5", 1100, 720, 1f, 1.5f),
            Viewport("640×460 dp, 2×, text ×1.3", 640, 460, 2f, 1.3f),
        )

    private fun onApp(
        wiring: Wiring,
        viewport: Viewport,
        body: (ComposeSceneHarness) -> Unit,
    ) {
        ComposeSceneHarness(viewport.width, viewport.height, Density(viewport.density, viewport.fontScale)) {
            PnpTrackerTheme(ThemeMode.LIGHT) {
                AppScaffold(
                    appInfo = AppInfo.Current,
                    navigation = wiring.navigation,
                    themeMode = ThemeMode.LIGHT,
                    onToggleTheme = {},
                    importController = wiring.importController,
                    reviewController = wiring.reviewController,
                    confirmationController = wiring.confirmationController,
                    rollbackController = wiring.rollbackController,
                    unfinishedController = wiring.unfinishedController,
                    gameTableController = wiring.gameTableController,
                    exportController = wiring.exportController,
                    backupController = wiring.backupController,
                    restoreController = wiring.restoreController,
                    retentionController = wiring.retentionController,
                    colorCatalogueController = wiring.colorController,
                    poolControllers = wiring.poolControllers,
                    historyController = wiring.historyController,
                )
            }
        }.use { harness ->
            settle(harness)
            body(harness)
        }
    }

    /**
     * Renders until what is written stops changing.
     *
     * The screens read through database streams that answer on Room's own
     * threads, so a single frame can come before the answer; and a scroll to a
     * newly focused stop is animated over several frames.
     */
    private fun settle(harness: ComposeSceneHarness) {
        var last = emptyList<String>()
        var stable = 0
        repeat(400) {
            harness.render()
            val now = harness.writtenText() + listOfNotNull(harness.keyboardHolder()?.boundsInRoot?.toString())
            if (now == last) stable++ else stable = 0
            last = now
            if (stable >= 5) return
            Thread.sleep(3)
        }
    }

    /**
     * The node that really holds the keyboard.
     *
     * The last one flagged, because a popup is a composition of its own and is
     * listed after the window: while it holds the keyboard, the window keeps the
     * flag on whatever it last focused.
     */
    private fun ComposeSceneHarness.keyboardHolder(): SemanticsNode? = nodes().lastOrNull { it.reads(SemanticsProperties.Focused) == true }

    private fun SemanticsNode.words(): List<String> =
        contentDescriptions() + reads(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.words() }

    /**
     * What one stop is called, for telling stops apart.
     *
     * The first thing it says, rather than all of it: a search box starts
     * showing its placeholder once it holds the keyboard, and that is the same
     * stop saying one more thing, not another stop.
     */
    private fun nameOf(node: SemanticsNode?): String = node?.words()?.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun textOf(resource: StringResource): String = runBlocking { getString(resource) }

    private fun Rect.drawnInside(viewport: Viewport): Boolean =
        width > 0f && height > 0f && left >= -1f && top >= -1f && right <= viewport.width + 1f && bottom <= viewport.height + 1f

    /** Presses Tab until the sidebar entry of [screen] holds the keyboard. */
    private fun ComposeSceneHarness.tabToEntryOf(screen: Screen): Boolean {
        val entry = textOf(textsOf(screen).navigationLabel)
        repeat(60) {
            if (keyboardHolder()?.words()?.contains(entry) == true) return true
            tab()
        }
        return keyboardHolder()?.words()?.contains(entry) == true
    }

    // ---------------------------------------------------------- the keyboard

    @Test
    fun `every screen is reached, walked and walked back with the keyboard alone, at every size`() {
        val problems = mutableListOf<String>()
        viewports.forEach { viewport ->
            Screen.all.forEachIndexed { index, screen ->
                val wiring = wiring(if (screen == Screen.Home) Screen.Games else Screen.Home)
                onApp(wiring, viewport) { harness ->
                    val where = "${viewport.name} / $screen"
                    if (!harness.tabToEntryOf(screen)) {
                        problems += "$where: Tab never reached the sidebar entry"
                        return@onApp
                    }
                    // Enter on one entry, Space on the next: both are how a
                    // keyboard presses a button.
                    harness.press(if (index % 2 == 0) Key.Enter else Key.Spacebar)
                    settle(harness)
                    if (wiring.navigation.currentScreen != screen) {
                        problems += "$where: the entry did not open the screen (${wiring.navigation.currentScreen})"
                        return@onApp
                    }
                    if (textOf(textsOf(screen).title) !in harness.writtenText()) {
                        problems += "$where: the screen's own title is not written"
                    }

                    // Walk forward until the keyboard is back on this screen's
                    // own sidebar entry: everything in between was reachable,
                    // and coming back at all means nothing held the keyboard.
                    val entry = textOf(textsOf(screen).navigationLabel)
                    val stops = mutableListOf<String>()
                    var cameBack = false
                    var walked = 0
                    while (walked < 150) {
                        harness.tab()
                        settle(harness)
                        walked++
                        val focused = harness.keyboardHolder()
                        if (focused == null) {
                            problems += "$where: stop $walked holds nothing"
                            break
                        }
                        if (entry in focused.words() && stops.isNotEmpty()) {
                            cameBack = true
                            break
                        }
                        stops += nameOf(focused)
                        if (!focused.boundsInRoot.drawnInside(viewport)) {
                            problems += "$where: stop `${nameOf(focused)}` is not drawn inside the window (${focused.boundsInRoot})"
                        }
                    }
                    if (!cameBack) problems += "$where: Tab did not come back to the sidebar within 150 stops"

                    // Shift+Tab retraces the last stops Tab took, one for one.
                    val retraced = mutableListOf<String>()
                    repeat(minOf(8, stops.size)) {
                        harness.shiftTab()
                        settle(harness)
                        retraced += nameOf(harness.keyboardHolder())
                    }
                    val expected = stops.reversed().take(retraced.size)
                    if (retraced != expected) problems += "$where: Shift+Tab went $retraced where Tab came $expected"

                    harness.nodes().filter { it.boundsInRoot.width > 0f && it.boundsInRoot.right > viewport.width + 1f }.forEach {
                        problems += "$where: `${nameOf(it)}` reaches past the right edge (${it.boundsInRoot})"
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // -------------------------------------------------------- the words

    private val technical =
        listOf(
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") to "an identifier",
            Regex("Exception|java\\.|kotlin\\.|dev\\.pnptracker|androidx") to "a class name",
            Regex("\\bSELECT\\b|\\bFROM\\b|\\bsqlite\\b", RegexOption.IGNORE_CASE) to "a query",
            Regex("/tmp/|/home/|\\.db\\b|\\.lck\\b") to "a path",
            Regex(
                "\\b(THREE_D|CARD|BOARD|SPECIAL|NOTES|THREE_D_BATCH|PIPELINE|CHECKLIST|COUNTED|DRAFT|CONFIRMED|" +
                    "ROLLED_BACK|NEEDS_INFO|FAILURE_REPORTED|SHORTAGE_RESOLVED|PRINT|LAMINATE|GLUE|CUT)\\b",
            ) to "an enum name",
        )

    @Test
    fun `no screen writes an identifier, a class name, a query, a path or an enum name`() {
        val problems = mutableListOf<String>()
        Screen.all.forEach { screen ->
            val wiring = wiring(screen)
            onApp(wiring, viewports.first()) { harness ->
                val said =
                    harness.nodes().flatMap {
                        it.contentDescriptions() +
                            it.reads(SemanticsProperties.Text).orEmpty().map { t -> t.text }
                    }
                said.forEach { words ->
                    technical.forEach { (pattern, what) ->
                        if (pattern.containsMatchIn(words)) problems += "$screen shows $what: `$words`"
                    }
                }
                if (said.none { it.contains("Işıklı Şövalyeler") } && screen in listOf(Screen.Games, Screen.History)) {
                    problems += "$screen did not draw the library it was given"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `a table cell is one stop, and Enter or F2 on it opens its editor`() {
        viewports.take(2).forEach { viewport ->
            listOf(Key.Enter, Key.F2).forEach { key ->
                val wiring = wiring(Screen.Games)
                onApp(wiring, viewport) { harness ->
                    // "<column> hücresi boş", without the column: every empty cell says it.
                    val emptyCell = textOf(Strings.Table.cellEmptyDescription).replace("%1\$s", "")
                    var reached = false
                    repeat(40) {
                        if (!reached) {
                            harness.tab()
                            settle(harness)
                            val holder = harness.keyboardHolder()
                            assertNotNull(holder, "${viewport.name}: a tab stop on the table holds nothing a reader can hear")
                            reached = nameOf(holder).endsWith(emptyCell)
                        }
                    }
                    assertTrue(reached, "${viewport.name}: Tab never reached an empty cell")
                    harness.press(key)
                    settle(harness)
                    assertIs<CellWork.WritingText>(wiring.gameTableController.state.work, "${viewport.name}: $key did not open the editor")
                }
            }
        }
    }

    // ------------------------------------------- questions that cannot be undone

    private fun ComposeSceneHarness.focusedSays(): List<String> = keyboardHolder()?.words().orEmpty()

    @Test
    fun `deleting a colour asks with the keyboard on the way out, and Enter keeps the colour`() {
        val colour = runBlocking { database.colorDao().allColors().last() }
        val wiring = wiring(Screen.Colors)
        viewports.forEach { viewport ->
            onApp(wiring, viewport) { harness ->
                runBlocking { wiring.colorController.startDeleting(colour.id) }
                settle(harness)
                assertEquals(
                    listOf(textOf(Strings.Colors.discard)),
                    harness.focusedSays(),
                    "${viewport.name}: the deletion question did not start on the way out",
                )
                assertTrue(harness.keyboardHolder()!!.boundsInRoot.drawnInside(viewport), "${viewport.name}: the way out is off screen")
                harness.press(Key.Enter)
                settle(harness)
                assertNull(wiring.colorController.state.work, "${viewport.name}: Enter did not close the question")
                assertNotNull(runBlocking { database.colorDao().colorById(colour.id) }, "${viewport.name}: Enter deleted the colour")
            }
        }
    }

    @Test
    fun `turning a task in the table into text asks with the keyboard on the way out, and Enter keeps the task`() {
        val task = taskOf.getValue(PoolType.THREE_D)
        viewports.forEach { viewport ->
            val wiring = wiring(Screen.Games)
            onApp(wiring, viewport) { harness ->
                wiring.gameTableController.openTaskMenu(gameId, CellColumnType.THREE_D, task)
                settle(harness)
                wiring.gameTableController.beginConvertToText()
                settle(harness)
                assertEquals(
                    listOf(textOf(Strings.TaskConvert.cancel)),
                    harness.focusedSays().distinct(),
                    "${viewport.name}: the question did not start on the way out",
                )
                harness.press(Key.Enter)
                settle(harness)
                assertNotNull(runBlocking { database.taskDao().activeTaskById(task) }, "${viewport.name}: Enter turned the task into text")
                // `Vazgeç` closes only the question and goes back to the menu it came from.
                assertIs<CellWork.TaskMenu>(wiring.gameTableController.state.work, "${viewport.name}: Enter did not close the question")
            }
        }
    }

    @Test
    fun `turning a task in a pool into text asks with the keyboard on the way out, and Enter keeps the task`() {
        val task = taskOf.getValue(PoolType.CARD)
        viewports.forEach { viewport ->
            val wiring = wiring(Screen.Pool(PoolType.CARD))
            onApp(wiring, viewport) { harness ->
                val pool = wiring.poolControllers.of(PoolType.CARD)
                pool.openTaskMenu(PoolCardKey(task))
                settle(harness)
                pool.beginConvertToText()
                settle(harness)
                assertEquals(
                    listOf(textOf(Strings.TaskConvert.cancel)),
                    harness.focusedSays().distinct(),
                    "${viewport.name}: the question did not start on the way out",
                )
                harness.press(Key.Enter)
                settle(harness)
                assertNotNull(runBlocking { database.taskDao().activeTaskById(task) }, "${viewport.name}: Enter turned the task into text")
                assertIs<PoolWork.Menu>(pool.state.work, "${viewport.name}: Enter did not close the question")
            }
        }
    }

    /**
     * The way back for a task finished by one tap, found in real use (PLAN 12.10).
     *
     * Asked from the pool's `Tamamlandı` list, in every window size and text
     * size: the question starts on the way out, so an Enter pressed out of habit
     * leaves the task finished, and the question closes back to the menu. Where
     * it is drawn is not asserted, as for the other pool questions: a menu
     * opened here from code sits on a card that may be below the fold, and the
     * pool does not yet scroll a card into view when its menu opens.
     */
    @Test
    fun `making a finished task active again asks with the keyboard on the way out, and Enter keeps it finished`() {
        val task = taskOf.getValue(PoolType.CARD)
        viewports.forEach { viewport ->
            val wiring = wiring(Screen.Pool(PoolType.CARD))
            onApp(wiring, viewport) { harness ->
                runBlocking { TaskProgressStore(database.taskProgressDao()).completeTask(task, IdGenerator.Random.newId()) }
                val pool = wiring.poolControllers.of(PoolType.CARD)
                pool.showState(dev.pnptracker.domain.search.TaskStateFilter.COMPLETED)
                settle(harness)
                pool.openTaskMenu(PoolCardKey(task))
                settle(harness)
                pool.beginReopen()
                settle(harness)
                assertIs<PoolWork.ConfirmingReopen>(pool.state.work, "${viewport.name}: the finished list did not ask")
                assertEquals(
                    listOf(textOf(Strings.TaskReopen.cancel)),
                    harness.focusedSays().distinct(),
                    "${viewport.name}: the question did not start on the way out",
                )
                harness.press(Key.Enter)
                settle(harness)
                assertTrue(
                    runBlocking { database.taskDao().activeTaskById(task) }!!.isCompleted,
                    "${viewport.name}: Enter made the task active",
                )
                assertIs<PoolWork.Menu>(pool.state.work, "${viewport.name}: Enter did not close the question")
            }
        }
    }
}
