package dev.pnptracker.ui.feature.history

import dev.pnptracker.domain.history.HistoryChange
import dev.pnptracker.domain.history.HistoryPeriod
import dev.pnptracker.domain.model.ProductionStage
import dev.pnptracker.domain.time.LocalMoment
import dev.pnptracker.domain.time.localMomentOf
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.navigation.Screen
import dev.pnptracker.ui.stageNameOf
import dev.pnptracker.ui.textsOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import java.nio.file.Path
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the history screen says, and what it is not allowed to be.
 *
 * Half of this reads the real Turkish catalogue, because the screen is almost
 * entirely words: a sentence per thing that happened. The other half reads the
 * screen's own source — no Compose test dependency may be added to look at a
 * window from outside — pinned to the properties that would make the section
 * something other than a reading.
 */
class HistoryScreenLayoutTest {
    private fun textOf(
        resource: StringResource,
        vararg arguments: Any,
    ): String = runBlocking { getString(resource, *arguments) }

    private fun read(path: String): String = Files.readString(Path.of(path))

    private val screen = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/history/HistoryScreen.kt")
    private val controller = read("src/commonMain/kotlin/dev/pnptracker/ui/feature/history/HistoryController.kt")
    private val dao = read("src/commonMain/kotlin/dev/pnptracker/data/database/dao/HistoryDao.kt")
    private val scaffold = read("src/commonMain/kotlin/dev/pnptracker/ui/navigation/AppScaffold.kt")

    /** Every kind of thing the history can say happened. */
    private val everyChange =
        listOf(
            HistoryChange.StageMoved(ProductionStage.LAMINATE, previousQuantity = 3, newQuantity = 9),
            HistoryChange.TaskCompleted,
            HistoryChange.TaskReopened,
            HistoryChange.TaskDeleted,
            HistoryChange.TaskRestored,
            HistoryChange.TaskConvertedToText,
            HistoryChange.GameDeleted,
            HistoryChange.GameRestored,
            HistoryChange.ShortageReported(quantity = 4),
            HistoryChange.ShortageResolved(quantity = 2),
        )

    private fun sentenceOf(change: HistoryChange): String {
        val stageName = (change as? HistoryChange.StageMoved)?.let { textOf(stageNameOf(it.stage)) }
        return textOf(
            historySentenceOf(change),
            *historyArgumentsOf(change, taskName = "Gri token", stageName = stageName).toTypedArray(),
        )
    }

    // --------------------------------------------------------------- the words

    @Test
    fun `every kind of thing that can happen has a sentence of its own`() {
        val sentences = everyChange.map(::sentenceOf)

        assertEquals(sentences.size, sentences.toSet().size, "two events read the same way: $sentences")
        sentences.forEach { assertTrue(it.isNotBlank(), "an event has nothing to say for itself") }
    }

    @Test
    fun `no sentence shows an enum name, an identity or a table`() {
        // What the user reads must be Turkish. A branch that fell through to a
        // name would show `TASK_CONVERTED_TO_TEXT` or a uuid on the screen.
        val forbidden =
            everyChange.map { change -> change::class.simpleName.orEmpty() } +
                listOf("TASK_", "GAME_", "SHORTAGE_", "FAILURE_", "history_events", "progress_events", "SELECT")

        everyChange.map(::sentenceOf).forEach { sentence ->
            forbidden.forEach { word ->
                assertFalse(word in sentence, "the sentence `$sentence` is showing `$word`")
            }
            assertFalse(
                Regex("[0-9a-f]{8}-[0-9a-f]{4}").containsMatchIn(sentence),
                "the sentence `$sentence` is showing an identity",
            )
        }
    }

    @Test
    fun `the task's own name is what a task line is about`() {
        val aboutATask = everyChange - HistoryChange.GameDeleted - HistoryChange.GameRestored

        aboutATask.forEach { change ->
            assertTrue("Gri token" in sentenceOf(change), "$change does not name the task it happened to")
        }
    }

    @Test
    fun `a line about the game itself names no task`() {
        listOf(HistoryChange.GameDeleted, HistoryChange.GameRestored).forEach { change ->
            assertTrue(historyArgumentsOf(change, taskName = "Gri token").isEmpty())
            assertFalse("Gri token" in sentenceOf(change), "$change put a task into a line about the game")
        }
    }

    @Test
    fun `a moved step says which step and both counts`() {
        val sentence = sentenceOf(HistoryChange.StageMoved(ProductionStage.LAMINATE, 3, 9))

        assertTrue(textOf(stageNameOf(ProductionStage.LAMINATE)) in sentence, sentence)
        assertTrue("3" in sentence && "9" in sentence, sentence)
    }

    @Test
    fun `a shortage says how many pieces it was about`() {
        assertTrue("4" in sentenceOf(HistoryChange.ShortageReported(quantity = 4)))
        assertTrue("2" in sentenceOf(HistoryChange.ShortageResolved(quantity = 2)))
    }

    @Test
    fun `only what the user really wrote becomes a detail line`() {
        assertTrue(historyDetailsOf(HistoryChange.ShortageReported(quantity = 4)).isEmpty())
        assertTrue(historyDetailsOf(HistoryChange.TaskCompleted).isEmpty())

        val detailed =
            historyDetailsOf(
                HistoryChange.ShortageReported(
                    quantity = 4,
                    note = "Kenarları kıvrıldı",
                    cardReference = "12-13",
                    stage = ProductionStage.CUT,
                ),
            )

        assertEquals(
            listOf(
                HistoryDetail.Card("12-13"),
                HistoryDetail.Stage(ProductionStage.CUT),
                HistoryDetail.Note("Kenarları kıvrıldı"),
            ),
            detailed,
        )
        detailed.forEach { assertTrue(textOf(historyDetailLabelOf(it), "x").isNotBlank()) }
    }

    @Test
    fun `every date range has a name of its own`() {
        val names = HistoryPeriod.entries.map { textOf(historyPeriodNameOf(it)) }

        assertEquals(names.size, names.toSet().size, "two ranges are called the same thing: $names")
        names.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `the section is named and described in the sidebar and over the screen`() {
        val texts = textsOf(Screen.History)

        assertEquals("Geçmiş", textOf(texts.navigationLabel))
        assertTrue(textOf(texts.title).isNotBlank())
        // PLAN 12.15's screen is a reading, and the sentence under the heading is
        // where the user is told so.
        assertTrue("okunur" in textOf(texts.description), textOf(texts.description))
    }

    @Test
    fun `an empty history and an empty filter say different things`() {
        assertTrue(textOf(Strings.History.empty) != textOf(Strings.History.emptyFiltered))
        assertTrue(textOf(Strings.History.emptyFilteredHint).isNotBlank())
    }

    // -------------------------------------------------------------- the moment

    @Test
    fun `a moment is written out in full, padded so the column lines up`() {
        val moment = LocalMoment(year = 2026, month = 9, dayOfMonth = 6, hour = 9, minute = 5)

        assertEquals("06.09.2026 09:05", textOf(Strings.History.moment, *momentArgumentsOf(moment).toTypedArray()))
    }

    @Test
    fun `a stored instant is read in the machine's own zone`() {
        // 2026-09-06T12:00:00Z. What a person calls it depends on where they
        // are, so the answer is compared against the same conversion the JVM
        // makes rather than against a fixed string.
        val instant = Instant.fromEpochMilliseconds(1_788_696_000_000L)
        val expected =
            java.time.Instant
                .ofEpochMilli(1_788_696_000_000L)
                .atZone(TimeZone.getDefault().toZoneId())

        val moment = localMomentOf(instant)

        assertEquals(expected.year, moment.year)
        assertEquals(expected.monthValue, moment.month)
        assertEquals(expected.dayOfMonth, moment.dayOfMonth)
        assertEquals(expected.hour, moment.hour)
        assertEquals(expected.minute, moment.minute)
    }

    // ---------------------------------------------------------- what it is not

    @Test
    fun `nothing on this screen can write`() {
        // The section is handed a source that only observes, and neither the
        // screen nor the controller reaches past it. A save here would make the
        // history a second record, free to disagree with what happened.
        listOf("insert", "update", "delete", "softDelete", "Store(", "Dao(").forEach { verb ->
            assertFalse(verb in controller, "the history controller is doing something with `$verb`")
            assertFalse(verb in screen, "the history screen is doing something with `$verb`")
        }
        assertFalse("Insert" in dao, "the history DAO has grown a way to write")
        assertFalse("UPDATE history_events" in dao)
        assertFalse("DELETE FROM history_events" in dao)
    }

    @Test
    fun `the reads are ordered newest first and settled by identity`() {
        // Both of them, and in the same direction: the two lists are merged
        // afterwards, and a merge of two differently ordered lists is not an
        // order at all.
        assertEquals(
            2,
            Regex("ORDER BY [a-z_]+\\.(occurred_at|recorded_at) DESC, [a-z_]+\\.id DESC").findAll(dao).count(),
            "a history read is not ordered newest first with identity settling ties",
        )
    }

    @Test
    fun `the sidebar reaches the history and the screen is drawn for it`() {
        assertTrue(Screen.History in Screen.all)
        assertTrue("Screen.History -> HistoryScreen(historyController)" in scaffold)
    }

    @Test
    fun `the history sits where PLAN 12 1 puts it, between import and colours`() {
        val order = Screen.all
        assertEquals(order.indexOf(Screen.Import) + 1, order.indexOf(Screen.History))
        assertEquals(order.indexOf(Screen.History) + 1, order.indexOf(Screen.Colors))
    }

    @Test
    fun `the list can be reached and read with the keyboard alone`() {
        // The only thing to do on this screen is read it, so moving through the
        // reading is the action PLAN 17 asks to be reachable. A focusable lazy
        // list takes the keyboard and then answers none of the keys somebody
        // would try, so every one of them is handled by name.
        assertTrue(".focusable()" in screen, "the list of lines is not a focus stop")
        assertTrue("focusOutline" in screen, "there is no visible sign of where the keyboard is")
        listOf("Key.DirectionDown", "Key.DirectionUp", "Key.PageDown", "Key.PageUp", "Key.MoveHome", "Key.MoveEnd")
            .forEach { key -> assertTrue(key in screen, "the list does not answer $key") }
        assertTrue("closesFilterPanelOnEscape" in screen, "the filter panel is a keyboard trap")
        assertTrue("focusRecall" in screen, "the keyboard is not handed back when the panel closes")
    }

    @Test
    fun `a whole row is one thing to a reader`() {
        // The moment, the sentence and the game are one announcement. Read
        // separately they would be three, and the moment would arrive before
        // there was anything for it to be the moment of.
        assertTrue("clearAndSetSemantics" in screen)
        assertTrue("contentDescription = spoken" in screen)
    }
}
