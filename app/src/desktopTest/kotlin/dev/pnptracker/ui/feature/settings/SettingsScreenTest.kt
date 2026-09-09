package dev.pnptracker.ui.feature.settings

import androidx.compose.ui.input.key.Key
import androidx.sqlite.SQLiteException
import dev.pnptracker.AppInfo
import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupException
import dev.pnptracker.domain.backup.BackupFailure
import dev.pnptracker.domain.backup.BackupFileGateway
import dev.pnptracker.domain.backup.BackupFileHandle
import dev.pnptracker.domain.backup.BackupSnapshot
import dev.pnptracker.domain.backup.BackupSource
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.ui.ComposeSceneHarness
import dev.pnptracker.ui.contentDescriptions
import dev.pnptracker.ui.theme.PnpTrackerTheme
import dev.pnptracker.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val MOMENT = Instant.fromEpochMilliseconds(1_757_320_364_031)

private class SceneClock(
    private val fixed: Instant,
) : Clock {
    override fun now(): Instant = fixed
}

private val emptyData =
    BackupData(
        colors = emptyList(),
        colorAliases = emptyList(),
        importBatches = emptyList(),
        games = emptyList(),
        gameCells = emptyList(),
        rawImportBlocks = emptyList(),
        tasks = emptyList(),
        cellSegments = emptyList(),
        taskColors = emptyList(),
        taskStages = emptyList(),
        progressEvents = emptyList(),
        historyEvents = emptyList(),
        importBatchCells = emptyList(),
        draftTasks = emptyList(),
        draftTaskColors = emptyList(),
    )

private class SceneSource : BackupSource {
    override suspend fun snapshot(): BackupSnapshot = BackupSnapshot(sourceSchemaVersion = 8, data = emptyData)
}

/** A database that will not answer, in the one way the caller asked for. */
private class RefusingSource(
    private val refusal: () -> Throwable,
) : BackupSource {
    override suspend fun snapshot(): BackupSnapshot = throw refusal()
}

private class SceneFile(
    override val fileName: String = "pnp-yedek-2026-09-08.json",
    private val alreadyThere: Boolean = false,
    private val refuse: BackupFailure? = null,
) : BackupFileHandle {
    var writes = 0
        private set

    override suspend fun exists(): Boolean = alreadyThere

    override suspend fun write(bytes: ByteArray) {
        refuse?.let { throw BackupException(it) }
        writes++
    }
}

private class SceneGateway(
    private val handle: BackupFileHandle?,
    private val refuse: BackupFailure? = null,
) : BackupFileGateway {
    var asked = 0
        private set
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun chooseDestination(suggestedName: String): BackupFileHandle? {
        asked++
        gate?.await()
        refuse?.let { throw BackupException(it) }
        return handle
    }
}

/**
 * The settings screen composed for real, with real strokes.
 *
 * Nothing here reads the source. What it is for is the parts a source search
 * cannot see: whether the action can be started from the keyboard, whether the
 * question can be answered without the destructive button being one stray Enter
 * away, whether the outcome names only the file, and whether any of it spills
 * off the edge of a narrow window.
 */
class SettingsScreenTest {
    private fun controller(
        gateway: SceneGateway,
        source: BackupSource = SceneSource(),
    ) = BackupController(
        gateway = gateway,
        exporter = DatabaseBackupExporter(source, AppInfo.Current, SceneClock(MOMENT)),
        clock = SceneClock(MOMENT),
    )

    private fun harness(
        controller: BackupController,
        width: Int = 900,
        height: Int = 700,
        restore: RestoreController = aRestoreController(FakeSourceGateway(aRealBackupFile())),
    ) = ComposeSceneHarness(width = width, height = height) {
        PnpTrackerTheme(ThemeMode.LIGHT) { SettingsScreen(controller, restore) }
    }

    private fun ComposeSceneHarness.text(): String = writtenText().joinToString(" | ")

    @Test
    fun `the screen names itself and says what a backup holds`() {
        harness(controller(SceneGateway(SceneFile()))).use { harness ->
            val text = harness.text()
            assertTrue("Ayarlar" in text, text)
            assertTrue("Yedekleme" in text, text)
            assertTrue("Yedek oluştur" in text, text)
            // The two questions somebody actually has: what is in it, and where
            // does it go.
            assertTrue("görev" in text && "geçmiş" in text && "içe aktarma" in text, text)
            assertTrue("seçtiğiniz konuma" in text, text)
        }
    }

    @Test
    fun `both of the actions the plan names are on the screen`() {
        harness(controller(SceneGateway(SceneFile()))).use { harness ->
            val text = harness.text()
            assertTrue("Yedek oluştur" in text, text)
            assertTrue("Yedekten geri yükle" in text, text)
            // What a restore does and what it does first, said before anybody
            // presses anything (PLAN 12.16).
            assertTrue("yerine geçer" in text, text)
            assertTrue("güvenlik yedeği" in text.lowercase(), text)
            assertFalse("yakında" in text.lowercase(), text)
        }
    }

    @Test
    fun `no technical detail of the format reaches the screen`() {
        harness(controller(SceneGateway(SceneFile()))).use { harness ->
            val text = harness.text()
            listOf("formatVersion", "sha256", "SHA-256", "schema", "UTF-8", "checksum").forEach {
                assertFalse(it in text, "'$it' is this application's own business, not the user's: $text")
            }
        }
    }

    @Test
    fun `the action can be reached and started from the keyboard alone`() {
        val gateway = SceneGateway(SceneFile())
        val controller = controller(gateway)
        harness(controller).use { harness ->
            assertTrue(harness.tabTo("Bütün verinizi tek bir JSON dosyasına kaydeder"), "the button cannot be tabbed to")
            harness.press(Key.Enter)
            harness.render()

            assertEquals(1, gateway.asked)
        }
    }

    @Test
    fun `a held Enter starts one backup and not a stream of them`() {
        // The dialog is held open, which is where a repeated key does damage: a
        // key repeat arrives while the first press is still being answered.
        val gateway = SceneGateway(SceneFile())
        gateway.gate = CompletableDeferred()
        val controller = controller(gateway)
        harness(controller).use { harness ->
            harness.tabTo("Bütün verinizi tek bir JSON dosyasına kaydeder")
            repeat(4) {
                harness.press(Key.Enter)
                harness.render()
            }
            assertEquals(1, gateway.asked, "a repeated key opened the dialog more than once")
            gateway.gate?.complete(Unit)
        }
    }

    @Test
    fun `the button is closed while something is running`() {
        val gateway = SceneGateway(SceneFile())
        gateway.gate = CompletableDeferred()
        val controller = controller(gateway)
        harness(controller).use { harness ->
            harness.click("Bütün verinizi tek bir JSON dosyasına kaydeder")
            harness.render()

            assertTrue("Kaydedilecek yer seçiliyor…" in harness.text(), harness.text())
            harness.click("Bütün verinizi tek bir JSON dosyasına kaydeder")
            harness.render()
            assertEquals(1, gateway.asked, "a second press got through while the dialog was open")

            gateway.gate?.complete(Unit)
        }
    }

    @Test
    fun `the question about an existing file offers both answers and starts on neither`() {
        val file = SceneFile("pnp-yedek-2026-09-08.json", alreadyThere = true)
        val controller = controller(SceneGateway(file))
        harness(controller).use { harness ->
            harness.click("Bütün verinizi tek bir JSON dosyasına kaydeder")
            harness.render()

            val text = harness.text()
            assertTrue("Bu dosya zaten var" in text, text)
            assertTrue("pnp-yedek-2026-09-08.json dosyasının üzerine yazılsın mı?" in text, text)
            assertTrue("Üzerine yaz" in text && "Vazgeç" in text, text)
            // The panel holds the focus, so a stray Enter answers neither.
            assertTrue(
                harness.focusedNode()?.contentDescriptions()?.contains("Bu dosya zaten var") == true,
                "the keyboard landed on a button rather than on the question",
            )
            assertEquals(0, file.writes)
        }
    }

    @Test
    fun `escape answers the question with a no and gives the keyboard back`() {
        val file = SceneFile("yedek.json", alreadyThere = true)
        val controller = controller(SceneGateway(file))
        harness(controller).use { harness ->
            harness.click("Bütün verinizi tek bir JSON dosyasına kaydeder")
            harness.render()

            harness.press(Key.Escape)
            harness.render()
            harness.render()

            assertFalse("Bu dosya zaten var" in harness.text(), harness.text())
            assertEquals(0, file.writes, "escape replaced the file")
            assertTrue(
                harness.focusedNode()?.contentDescriptions()?.contains("Bütün verinizi tek bir JSON dosyasına kaydeder") == true,
                "the keyboard did not go back to the button that asked",
            )
        }
    }

    @Test
    fun `agreeing replaces the file and says so with the name alone`() {
        val file = SceneFile("pnp-yedek-2026-09-08.json", alreadyThere = true)
        val controller = controller(SceneGateway(file))
        harness(controller).use { harness ->
            harness.click("Bütün verinizi tek bir JSON dosyasına kaydeder")
            harness.render()
            harness.click("Üzerine yaz")
            harness.render()

            assertEquals(1, file.writes)
            val text = harness.text()
            assertTrue("Yedek oluşturuldu: pnp-yedek-2026-09-08.json" in text, text)
            assertFalse("/" in text.substringAfter("Yedek oluşturuldu:").substringBefore("|"), "a path reached the screen: $text")
        }
    }

    @Test
    fun `every failure there is becomes its own Turkish sentence on screen`() {
        // Every value, driven through the path it really arrives by: the two
        // from storage through a source that refuses, the two about the
        // destination through a dialog that refuses, and the rest through a
        // file that will not be written.
        val sentences = mutableSetOf<String>()
        BackupFailure.entries.forEach { failure ->
            val driven =
                when (failure) {
                    BackupFailure.COULD_NOT_READ_DATABASE ->
                        controller(SceneGateway(SceneFile()), RefusingSource { SQLiteException("no") })

                    BackupFailure.COULD_NOT_BUILD_DOCUMENT ->
                        controller(SceneGateway(SceneFile()), RefusingSource { SerializationException("no") })

                    BackupFailure.NO_DESTINATION, BackupFailure.UNSUPPORTED_FILE_TYPE ->
                        controller(SceneGateway(null, refuse = failure))

                    else -> controller(SceneGateway(SceneFile(refuse = failure)))
                }
            harness(driven).use { harness ->
                harness.click("Bütün verinizi tek bir JSON dosyasına kaydeder")
                harness.render()

                val text = harness.text()
                assertTrue("Yedek oluşturulamadı" in text, "$failure showed no problem at all: $text")
                assertFalse(failure.name in text, "$failure leaked its raw name: $text")
                val sentence = text.substringAfter("Yedek oluşturulamadı | ").substringBefore(" | Tamam")
                assertTrue(sentence.length > 20, "$failure showed a title and no explanation: $text")
                sentences += sentence
            }
        }
        assertEquals(
            BackupFailure.entries.size,
            sentences.size,
            "two failures share a sentence, so one of them cannot be told apart",
        )
    }

    @Test
    fun `a problem names no enum, no identifier, no query and no path`() {
        val controller = controller(SceneGateway(SceneFile(refuse = BackupFailure.NOT_WRITABLE)))
        harness(controller).use { harness ->
            harness.click("Bütün verinizi tek bir JSON dosyasına kaydeder")
            harness.render()

            val text = harness.text()
            listOf("NOT_WRITABLE", "SELECT", "INSERT", "Exception", "null", "/home", "/tmp").forEach {
                assertFalse(it in text, "'$it' reached the user: $text")
            }
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}").find(text) == null, "an identifier reached the user: $text")
        }
    }

    @Test
    fun `the outcome can be dismissed and another backup taken`() {
        val controller = controller(SceneGateway(SceneFile()))
        harness(controller).use { harness ->
            harness.click("Bütün verinizi tek bir JSON dosyasına kaydeder")
            harness.render()
            assertTrue("Yedek oluşturuldu" in harness.text())

            harness.click("Tamam")
            harness.render()
            assertFalse("Yedek oluşturuldu" in harness.text(), harness.text())
        }
    }

    @Test
    fun `the action and the question stay on screen in a narrow window`() {
        val file = SceneFile("pnp-yedek-2026-09-08.json", alreadyThere = true)
        val controller = controller(SceneGateway(file))
        harness(controller, width = 640, height = 620).use { harness ->
            assertTrue(harness.boundsOf("Bütün verinizi tek bir JSON dosyasına kaydeder") != null)

            harness.click("Bütün verinizi tek bir JSON dosyasına kaydeder")
            harness.render()

            val question = harness.boundsOf("Bu dosya zaten var")
            assertTrue(question != null, "the question is not on screen at all")
            assertTrue(question.right <= 640f, "the question runs off the right edge: $question")
            assertTrue("Üzerine yaz" in harness.text() && "Vazgeç" in harness.text(), harness.text())
        }
    }
}
