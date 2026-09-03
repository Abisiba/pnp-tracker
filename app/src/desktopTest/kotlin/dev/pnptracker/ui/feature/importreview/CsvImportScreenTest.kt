package dev.pnptracker.ui.feature.importreview

import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.data.repository.ImportDrafts
import dev.pnptracker.data.repository.SavedImportSummary
import dev.pnptracker.domain.importprep.ImportFileGateway
import dev.pnptracker.domain.importprep.ImportFileHandle
import dev.pnptracker.domain.importprep.ImportSourceReading
import dev.pnptracker.domain.importprep.PreparedImportDraft
import dev.pnptracker.domain.importprep.readCsvWorkbook
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.ImportSourceFormat
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The import screen composed for real, with a CSV behind it.
 *
 * Which separator a file turned out to use is a decision made on the user's
 * behalf, and a decision made silently is one nobody can check. So it is drawn,
 * and this lays the screen out and reads the real text off it rather than
 * trusting that a string exists somewhere in the source.
 */
class CsvImportScreenTest {
    private class CsvHandle(
        override val fileName: String,
        private val text: String,
    ) : ImportFileHandle {
        override suspend fun fingerprint(): String = "%064x".format(text.hashCode().toLong() and 0xffffffffL)

        override suspend fun readWorkbook(): ImportSourceReading {
            val reading = readCsvWorkbook(fileName, text)
            return ImportSourceReading(reading.workbook, ImportSourceFormat.CSV, reading.delimiter)
        }
    }

    private class FixedGateway(
        private val handle: ImportFileHandle?,
    ) : ImportFileGateway {
        override suspend fun chooseFile(): ImportFileHandle? = handle
    }

    private class RecordingStore : ImportDrafts {
        val saved = mutableListOf<PreparedImportDraft>()

        override suspend fun earlierImportsOf(sha256: String): List<EarlierImport> = emptyList()

        override suspend fun save(draft: PreparedImportDraft): SavedImportSummary {
            saved += draft
            return SavedImportSummary(IdGenerator.Random.newId(), draft.fileName, draft.sheetName, draft.rawBlockCount)
        }
    }

    private fun onScreen(
        fileName: String,
        text: String,
        width: Int = 1280,
        height: Int = 900,
        theme: ThemeMode = ThemeMode.LIGHT,
        body: (ComposeSceneHarness, ImportController) -> Unit,
    ) {
        val controller = ImportController(FixedGateway(CsvHandle(fileName, text)), RecordingStore())
        runBlocking { controller.chooseFile() }
        ComposeSceneHarness(width = width, height = height) {
            PnpTrackerTheme(theme) {
                ImportScreen(controller = controller, onOpenReview = {})
            }
        }.use { harness ->
            harness.render()
            harness.render()
            body(harness, controller)
        }
    }

    private fun ComposeSceneHarness.saying(fragment: String): Boolean = writtenText().any { fragment in it }

    private val commaFile = "game,source_type,raw_text\nHarmonies,3d,Kırmızı ev\nWingspan,card,Deste\n"
    private val semicolonFile = "game;source_type;raw_text\nHarmonies;3d;Kırmızı ev\n"

    @Test
    fun `a comma file says so, in words`() {
        onScreen("liste.csv", commaFile) { harness, _ ->
            assertTrue(harness.saying("Virgül"), "the separator was not written out: ${harness.writtenText()}")
            assertTrue(harness.saying("liste.csv"))
        }
    }

    @Test
    fun `a semicolon file says so too`() {
        onScreen("türkçe.csv", semicolonFile) { harness, _ ->
            assertTrue(harness.saying("Noktalı virgül"), "the separator was not written out: ${harness.writtenText()}")
        }
    }

    @Test
    fun `the screen says the source is a CSV, and how many rows it held`() {
        onScreen("liste.csv", commaFile) { harness, _ ->
            assertTrue(harness.saying("CSV"), "the format was never named: ${harness.writtenText()}")
            assertTrue(harness.saying("Kaynak satır sayısı: 2"), harness.writtenText().toString())
        }
    }

    @Test
    fun `a broken file names the line to go and look at, and offers another go`() {
        onScreen("bozuk.csv", "game,source_type,raw_text\nHarmonies,3d,Ev\nWingspan,kartlar,Yuva") { harness, _ ->
            assertTrue(harness.saying("Satır 3"), "the line number never reached the screen: ${harness.writtenText()}")
            assertTrue(harness.saying("source_type"), "the column at fault was not named")
            // The panel stays, with the way out of it on screen.
            assertTrue(harness.saying("Başka dosya seç"))
        }
    }

    @Test
    fun `an error carries no path, no SQL and no exception class`() {
        onScreen("bozuk.csv", "game,source_type\nHarmonies,3d") { harness, _ ->
            val shown = harness.writtenText().joinToString("\n")
            listOf("Exception", "SELECT", "/home/", "java.", "dev.pnptracker").forEach { forbidden ->
                assertTrue(forbidden !in shown, "`$forbidden` reached the screen:\n$shown")
            }
        }
    }

    @Test
    fun `the choose-file action names both formats rather than Excel alone`() {
        onScreen("liste.csv", commaFile) { harness, controller ->
            controller.startOver()
            harness.render()

            val shown = harness.writtenText().joinToString("\n")
            assertTrue("Excel" in shown && "CSV" in shown, "the action does not say which files it takes:\n$shown")
        }
    }

    @Test
    fun `the separator and the error stay inside a narrow window`() {
        onScreen(
            "bozuk.csv",
            "game,source_type,raw_text\nHarmonies,kartlar,Ev",
            width = 720,
            height = 880,
            theme = ThemeMode.DARK,
        ) { harness, _ ->
            assertTrue(harness.saying("Satır 2"))
            harness.nodes().forEach { node ->
                assertTrue(
                    node.boundsInRoot.right <= 721f,
                    "something reached past the right edge of a 720 wide window",
                )
            }
        }
    }

    @Test
    fun `the keyboard can reach the way out of a failure`() {
        onScreen("bozuk.csv", "game,source_type,raw_text\nHarmonies,kartlar,Ev") { harness, _ ->
            assertTrue(harness.focusableNodes().isNotEmpty(), "nothing on a failed screen could be focused")
            harness.tab()
            assertTrue(harness.focusedNode() != null, "the keyboard could not reach anything")
        }
    }

    @Test
    fun `saving is offered once and the draft carries the CSV format`() {
        val store = RecordingStore()
        val controller = ImportController(FixedGateway(CsvHandle("liste.csv", commaFile)), store)
        runBlocking {
            controller.chooseFile()
            controller.saveDraft()
            // A second click after the save has nothing left to save.
            controller.saveDraft()
        }

        assertEquals(1, store.saved.size)
        assertEquals(ImportSourceFormat.CSV, store.saved.single().sourceFormat)
    }
}
