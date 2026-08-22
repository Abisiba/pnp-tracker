package dev.pnptracker.ui.feature.colors

import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the fake was asked to create. */
private data class CreatedColor(
    val canonicalName: String,
    val hex: String,
)

/** Storage a test can push new catalogues through, so the controller can be watched. */
private class FakeCatalogue : ColorCatalogue {
    val colors = MutableStateFlow<List<ColorSummary>>(emptyList())
    val created = mutableListOf<CreatedColor>()
    val hexLookups = mutableListOf<String>()
    var failWith: ColorSetupFailure? = null
    var breakWith: Throwable? = null

    /** Held open so a test can look at the controller while a save is in flight. */
    var heldSave: CompletableDeferred<Unit>? = null

    /** Held open so a test can type on while a value is still being looked up. */
    var heldLookup: CompletableDeferred<Unit>? = null

    override fun observeColors(): Flow<List<ColorSummary>> = colors

    override suspend fun colorsUsingHex(hex: String): List<ColorSummary> {
        hexLookups += hex
        heldLookup?.await()
        return colors.value.filter { it.hex.equals(hex, ignoreCase = true) }
    }

    override suspend fun createColor(
        canonicalName: String,
        hex: String,
    ): EntityId {
        heldSave?.await()
        breakWith?.let { throw it }
        failWith?.let { throw ColorSetupException(it) }
        created += CreatedColor(canonicalName, hex)
        val id = IdGenerator.Random.newId()
        colors.value = colors.value + ColorSummary(id, canonicalName.trim(), hex, colors.value.size)
        return id
    }
}

class ColorCatalogueControllerTest {
    private fun aColor(
        name: String,
        hex: String,
        sortOrder: Int = 0,
    ) = ColorSummary(IdGenerator.Random.newId(), name, hex, sortOrder)

    private fun withCatalogue(
        catalogue: FakeCatalogue,
        body: suspend CoroutineScope.(ColorCatalogueController) -> Unit,
    ) = runBlocking {
        val controller = ColorCatalogueController(catalogue)
        val job = launch { controller.observeColors() }
        yield()
        try {
            body(controller)
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun ColorCatalogueController.fillIn(
        name: String = "Lacivert",
        hex: String = "#1A237E",
    ) {
        startComposer()
        editName(name)
        editHex(hex)
    }

    @Test
    fun `it starts out loading`() {
        assertIs<ColorCatalogueState.Loading>(ColorCatalogueController(FakeCatalogue()).state.catalogue)
    }

    @Test
    fun `the catalogue arrives in the order storage gave it`() {
        val catalogue = FakeCatalogue()
        catalogue.colors.value = listOf(aColor("Beyaz", "#FFFFFF", 0), aColor("Siyah", "#111111", 1))
        withCatalogue(catalogue) { controller ->
            val content = assertIs<ColorCatalogueState.Content>(controller.state.catalogue)

            assertEquals(listOf("Beyaz", "Siyah"), content.colors.map { it.canonicalName })
        }
    }

    @Test
    fun `a catalogue with nothing in it is still content rather than a state of its own`() {
        withCatalogue(FakeCatalogue()) { controller ->
            // Seeding means the application cannot produce this, so there is no
            // invitation to write the first colour and no empty case to draw.
            val content = assertIs<ColorCatalogueState.Content>(controller.state.catalogue)
            assertEquals(emptyList(), content.colors)
        }
    }

    @Test
    fun `the form starts closed`() {
        withCatalogue(FakeCatalogue()) { controller ->
            assertNull(controller.state.composer)
        }
    }

    @Test
    fun `giving up on the form writes nothing`() {
        val catalogue = FakeCatalogue()
        withCatalogue(catalogue) { controller ->
            controller.fillIn()

            controller.cancelComposer()

            assertNull(controller.state.composer)
            assertEquals(0, catalogue.created.size, "a colour was written by giving up on the form")
        }
    }

    @Test
    fun `a blank name cannot be saved and writes nothing`() {
        val catalogue = FakeCatalogue()
        withCatalogue(catalogue) { controller ->
            controller.fillIn(name = "   ")

            val composer = assertNotNull(controller.state.composer)
            assertTrue(!composer.isNameUsable)
            assertTrue(!composer.canSave)
            controller.save()

            assertEquals(0, catalogue.created.size)
            assertNotNull(controller.state.composer, "the form closed on a colour that was never saved")
        }
    }

    @Test
    fun `a value that is not written as RRGGBB cannot be saved`() {
        val catalogue = FakeCatalogue()
        withCatalogue(catalogue) { controller ->
            listOf("1A237E", "#1A237", "#1A237EE", "#GGGGGG").forEach { attempt ->
                controller.fillIn(hex = attempt)

                val composer = assertNotNull(controller.state.composer)
                assertTrue(!composer.isHexUsable, "'$attempt' was accepted")
                assertTrue(!composer.canSave)
                controller.save()
            }

            assertEquals(0, catalogue.created.size)
        }
    }

    @Test
    fun `the preview waits until what is typed is a whole value`() {
        withCatalogue(FakeCatalogue()) { controller ->
            controller.startComposer()

            controller.editHex("#1A2")
            assertNull(assertNotNull(controller.state.composer).previewHex, "a half typed value was drawn")

            controller.editHex("#1A237E")
            assertEquals("#1A237E", assertNotNull(controller.state.composer).previewHex)

            controller.editHex("  #1A237E  ")
            assertEquals("#1A237E", assertNotNull(controller.state.composer).previewHex, "spacing stopped the preview")
        }
    }

    @Test
    fun `a value another colour already carries is remarked on`() {
        val catalogue = FakeCatalogue()
        catalogue.colors.value = listOf(aColor("Gri", "#808080"))
        withCatalogue(catalogue) { controller ->
            controller.fillIn(name = "Duman", hex = "#808080")

            val composer = assertNotNull(controller.state.composer)
            assertTrue(composer.sharesHexWithAnotherColor)
            assertEquals(listOf("Gri"), composer.colorsSharingHex.map { it.canonicalName })
        }
    }

    @Test
    fun `the remark does not stand in the way of saving`() {
        val catalogue = FakeCatalogue()
        catalogue.colors.value = listOf(aColor("Gri", "#808080"))
        withCatalogue(catalogue) { controller ->
            controller.fillIn(name = "Duman", hex = "#808080")
            assertTrue(assertNotNull(controller.state.composer).canSave)

            controller.save()

            assertEquals(CreatedColor("Duman", "#808080"), catalogue.created.single())
            assertNull(controller.state.composer, "the form stayed open after a colour was saved")
            assertNull(controller.state.failure)
        }
    }

    @Test
    fun `a value nobody carries is remarked on by nobody`() {
        val catalogue = FakeCatalogue()
        catalogue.colors.value = listOf(aColor("Gri", "#808080"))
        withCatalogue(catalogue) { controller ->
            controller.fillIn(hex = "#1A237E")

            assertTrue(!assertNotNull(controller.state.composer).sharesHexWithAnotherColor)
        }
    }

    @Test
    fun `a half typed value is not taken to the catalogue at all`() {
        val catalogue = FakeCatalogue()
        withCatalogue(catalogue) { controller ->
            controller.startComposer()

            controller.editHex("#1A2")

            assertEquals(emptyList(), catalogue.hexLookups, "storage was asked about something that is not a value")
        }
    }

    @Test
    fun `an earlier remark is dropped the moment the value changes`() {
        val catalogue = FakeCatalogue()
        catalogue.colors.value = listOf(aColor("Gri", "#808080"))
        withCatalogue(catalogue) { controller ->
            controller.fillIn(name = "Duman", hex = "#808080")
            assertTrue(assertNotNull(controller.state.composer).sharesHexWithAnotherColor)

            controller.editHex("#80808")

            assertTrue(
                !assertNotNull(controller.state.composer).sharesHexWithAnotherColor,
                "a remark was left standing next to a value it was not about",
            )
        }
    }

    @Test
    fun `an answer that arrives after the user has typed on is discarded`() =
        runBlocking {
            val catalogue = FakeCatalogue()
            catalogue.colors.value = listOf(aColor("Gri", "#808080"))
            val held = CompletableDeferred<Unit>()
            catalogue.heldLookup = held
            val controller = ColorCatalogueController(catalogue)
            controller.startComposer()

            // Unconfined, so the lookup has really started by the time it suspends.
            val slow = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.editHex("#808080") }
            catalogue.heldLookup = null
            controller.editHex("#1A237E")
            held.complete(Unit)
            slow.join()

            val composer = assertNotNull(controller.state.composer)
            assertEquals("#1A237E", composer.hex)
            assertTrue(
                !composer.sharesHexWithAnotherColor,
                "a stale answer was attached to a value the user had already replaced",
            )
        }

    @Test
    fun `a second click while the first save is still going writes only one colour`() =
        runBlocking {
            val catalogue = FakeCatalogue()
            val held = CompletableDeferred<Unit>()
            catalogue.heldSave = held
            val controller = ColorCatalogueController(catalogue)
            controller.fillIn()

            val first = CoroutineScope(Job() + Dispatchers.Unconfined).launch { controller.save() }
            assertTrue(controller.isSaving, "the first save is not in flight")

            controller.save()

            held.complete(Unit)
            first.join()
            assertEquals(1, catalogue.created.size, "one insistent click became two colours")
            assertTrue(!controller.isSaving)
        }

    @Test
    fun `a saved colour closes the form and turns up in the list`() {
        val catalogue = FakeCatalogue()
        withCatalogue(catalogue) { controller ->
            controller.fillIn(name = "Lacivert", hex = "#1A237E")

            controller.save()
            yield()

            assertNull(controller.state.composer)
            val content = assertIs<ColorCatalogueState.Content>(controller.state.catalogue)
            assertEquals(listOf("Lacivert"), content.colors.map { it.canonicalName })
        }
    }

    @Test
    fun `a name that is already a colour's is reported as that and leaves the form open`() {
        val catalogue = FakeCatalogue()
        catalogue.failWith = ColorSetupFailure.NAME_ALREADY_USED
        withCatalogue(catalogue) { controller ->
            controller.fillIn(name = "Gri", hex = "#1A237E")

            controller.save()

            assertEquals(ColorSetupFailure.NAME_ALREADY_USED, controller.state.failure)
            assertEquals("Gri", assertNotNull(controller.state.composer).name)
            assertTrue(!controller.isSaving)
        }
    }

    @Test
    fun `a name that is another colour's alias is told apart from a name that is taken`() {
        val catalogue = FakeCatalogue()
        catalogue.failWith = ColorSetupFailure.NAME_IS_ANOTHER_COLORS_ALIAS
        withCatalogue(catalogue) { controller ->
            controller.fillIn(name = "Gri Ton", hex = "#1A237E")

            controller.save()

            assertEquals(ColorSetupFailure.NAME_IS_ANOTHER_COLORS_ALIAS, controller.state.failure)
        }
    }

    @Test
    fun `a colour that did not save is reported as a saving problem`() {
        val catalogue = FakeCatalogue()
        catalogue.failWith = ColorSetupFailure.COULD_NOT_SAVE
        withCatalogue(catalogue) { controller ->
            controller.fillIn()

            controller.save()

            assertEquals(ColorSetupFailure.COULD_NOT_SAVE, controller.state.failure)
        }
    }

    @Test
    fun `a saved colour clears the error the last attempt left`() {
        val catalogue = FakeCatalogue()
        catalogue.failWith = ColorSetupFailure.NAME_ALREADY_USED
        withCatalogue(catalogue) { controller ->
            controller.fillIn()
            controller.save()
            assertNotNull(controller.state.failure)

            catalogue.failWith = null
            controller.save()

            assertNull(controller.state.failure)
        }
    }

    @Test
    fun `opening the form clears the error the last attempt left`() {
        val catalogue = FakeCatalogue()
        catalogue.failWith = ColorSetupFailure.NAME_ALREADY_USED
        withCatalogue(catalogue) { controller ->
            controller.fillIn()
            controller.save()
            assertNotNull(controller.state.failure)

            controller.startComposer()

            assertNull(controller.state.failure)
        }
    }

    @Test
    fun `an error nobody expected is not dressed up as a saving problem`() {
        val catalogue = FakeCatalogue()
        catalogue.breakWith = IllegalStateException("a rule the code got wrong")
        withCatalogue(catalogue) { controller ->
            controller.fillIn()

            assertFailsWith<IllegalStateException> { controller.save() }

            assertNull(controller.state.failure, "a programming fault was shown to the user as a saving problem")
            assertTrue(!controller.isSaving, "the saving flag was left stuck on")
        }
    }
}
