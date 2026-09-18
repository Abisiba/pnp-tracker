package dev.pnptracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import dev.pnptracker.data.repository.CellTextStore
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.data.repository.GameSetupStore
import dev.pnptracker.data.repository.RefusingCatalogue
import dev.pnptracker.data.repository.RefusingPool
import dev.pnptracker.data.repository.RefusingTable
import dev.pnptracker.data.repository.TaskEditStore
import dev.pnptracker.data.repository.TaskFromTextStore
import dev.pnptracker.data.repository.TaskProgressStore
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.ui.feature.colors.ColorCatalogueController
import dev.pnptracker.ui.feature.colors.ColorCatalogueScreen
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.games.GameTableScreen
import dev.pnptracker.ui.feature.pools.PoolController
import dev.pnptracker.ui.feature.pools.PoolScreen
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the three screens of Dilim 3 actually say when storage will not answer
 * them (PLAN 14.7.6, PLAN 17).
 *
 * The behaviour is settled in the diagnostics tests, against a real database
 * that refuses a real statement. What is asked here is the half only a drawn
 * screen can answer: that the words are Turkish and act-on-able, that they never
 * claim the thing is empty, that the way to try again is there and named, and
 * that nothing of the exception — its class, its message, the SQL in it, the
 * path under it — reaches the screen. Each is drawn twice: at the size the
 * window opens at, and in the smallest window `Main` allows with the text scaled
 * up (§17's two hardest views).
 *
 * Only the reading refuses. Everything the screens write through is the real
 * store on a real temporary database, so nothing here is a screen talking to
 * nothing at all.
 */
class UnreadableScreensTest {
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

    /** A refusal carrying everything a screen must not repeat back. */
    private fun aRefusal() = SQLiteException("SELECT * FROM games failed at /home/birisi/.local/share/pnp-tracker/pnp.db")

    private fun tableController() =
        GameTableController(
            table = RefusingTable(aRefusal()),
            setup = GameSetupStore(database.gameDao(), database.gameCellDao()),
            cells = CellTextStore(database.cellSegmentDao()),
            colors = RefusingCatalogue(aRefusal()),
            taskCreation = TaskFromTextStore(database.taskFromTextDao()),
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = TaskProgressStore(database.taskProgressDao()),
        )

    private fun poolController() =
        PoolController(
            poolType = PoolType.THREE_D,
            pools = RefusingPool(aRefusal()),
            colors = RefusingCatalogue(aRefusal()),
            taskEditing = TaskEditStore(database.taskEditDao()),
            taskProgress = TaskProgressStore(database.taskProgressDao()),
        )

    private fun catalogueController() = ColorCatalogueController(RefusingCatalogue(aRefusal()))

    /** The colours are real here; only the reading of them is refused. */
    private fun realCatalogue() = ColorCatalogueStore(database.colorDao())

    private fun harnessOf(
        wide: Boolean,
        content: @Composable () -> Unit,
    ) = ComposeSceneHarness(
        width = if (wide) 1100 else 640,
        height = if (wide) 720 else 460,
        density = if (wide) Density(1f) else Density(2f, fontScale = 1.3f),
    ) { PnpTrackerTheme(ThemeMode.LIGHT) { content() } }

    private fun ComposeSceneHarness.text(): String = writtenText().joinToString(" | ")

    /** What PLAN 14.4.5 keeps off a screen, and the words of this particular refusal. */
    private fun assertNothingTechnical(text: String) {
        listOf(
            "SQLiteException",
            "Exception",
            "SELECT",
            "PRAGMA",
            "/home",
            "pnp.db",
            "androidx",
            "dev.pnptracker",
            "STORAGE_READ_FAILED",
            "GAME_TABLE",
            "THREE_D",
        ).forEach { leak -> assertFalse(leak in text, "`$leak` reached the screen: $text") }
    }

    private fun assertSaysItCouldNotBeReadAndOffersAnotherGo(
        text: String,
        ownWords: String,
    ) {
        assertTrue(ownWords in text, "the screen does not say what could not be read: $text")
        assertTrue("Yeniden dene" in text, "there is no way to try again: $text")
        // Nothing was read, so nothing may be said about how much there is. These
        // are the sentences the genuinely empty versions of these screens use.
        assertFalse("Henüz oyun yok" in text, "a refused reading was drawn as an empty library: $text")
        assertFalse("Bu havuzda aktif görev yok" in text, "a refused reading was drawn as an empty pool: $text")
        assertNothingTechnical(text)
    }

    @Test
    fun `a game table that could not be read says so in both windows`() {
        listOf(true, false).forEach { wide ->
            harnessOf(wide) { GameTableScreen(tableController()) }.use { harness ->
                assertSaysItCouldNotBeReadAndOffersAnotherGo(harness.text(), "Oyun tablosu okunamadı")
            }
        }
    }

    @Test
    fun `a colour catalogue that could not be read says so in both windows`() {
        listOf(true, false).forEach { wide ->
            harnessOf(wide) { ColorCatalogueScreen(catalogueController()) }.use { harness ->
                assertSaysItCouldNotBeReadAndOffersAnotherGo(harness.text(), "Renk kataloğu okunamadı")
            }
        }
    }

    @Test
    fun `a pool that could not be read says so in both windows`() {
        listOf(true, false).forEach { wide ->
            harnessOf(wide) { PoolScreen(poolController()) }.use { harness ->
                assertSaysItCouldNotBeReadAndOffersAnotherGo(harness.text(), "Havuz okunamadı")
            }
        }
    }

    @Test
    fun `a catalogue that reads says nothing about being unreadable`() {
        harnessOf(wide = true) { ColorCatalogueScreen(ColorCatalogueController(realCatalogue())) }.use { harness ->
            val text = harness.text()
            assertFalse("okunamadı" in text, "a catalogue that read fine claimed it had not: $text")
            assertFalse("Yeniden dene" in text, "a catalogue that read fine offered another go: $text")
        }
    }
}
