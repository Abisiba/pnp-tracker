package dev.pnptracker.ui.feature.colors

import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.WheelPoint
import dev.pnptracker.domain.colors.baseColors
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

    /** The catalogue as a new database has it, so the squares are really there. */
    private fun seeded(): FakeCatalogue =
        FakeCatalogue().apply {
            colors.value = baseColors.map { ColorSummary(it.id, it.canonicalName, it.hex, it.sortOrder) }
        }

    private fun ColorCatalogueController.fillIn(name: String = "Lacivert") {
        startComposer()
        editName(name)
    }

    /** Puts the form on an exact colour, the way clicking one of the squares does. */
    private fun ColorCatalogueController.takeColorOf(name: String) {
        val id =
            assertIs<ColorCatalogueState.Content>(state.catalogue)
                .colors
                .first { it.canonicalName == name }
                .id
        chooseBaseColor(id)
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
    fun `giving up on the form takes the refusal down with it`() {
        val catalogue = FakeCatalogue()
        catalogue.colors.value = listOf(aColor("Gri", "#808080"))
        catalogue.failWith = ColorSetupFailure.NAME_ALREADY_USED
        withCatalogue(catalogue) { controller ->
            val before = assertIs<ColorCatalogueState.Content>(controller.state.catalogue).colors
            controller.fillIn(name = "GRİ")
            controller.save()
            assertEquals(ColorSetupFailure.NAME_ALREADY_USED, controller.state.failure, "the refusal was never shown")

            controller.cancelComposer()

            assertNull(controller.state.composer, "the form stayed open")
            assertNull(
                controller.state.failure,
                "the refusal outlived the form it belonged to, so it now sits above a form that is not there",
            )
            assertEquals(before, assertIs<ColorCatalogueState.Content>(controller.state.catalogue).colors)
            assertEquals(0, catalogue.created.size, "giving up wrote a colour")
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
    fun `the form opens on the first base colour the catalogue still has`() {
        val catalogue = seeded()
        withCatalogue(catalogue) { controller ->
            controller.startComposer()

            val composer = assertNotNull(controller.state.composer)
            assertEquals("#FFFFFF", composer.hex, "the wheel did not open where the first square is")
            assertEquals("", composer.name, "a name was offered that the user did not choose")
        }
    }

    @Test
    fun `a form opened on an empty catalogue still has a colour to show`() {
        withCatalogue(FakeCatalogue()) { controller ->
            controller.startComposer()

            // Every colour can be removed, the base ones included (PLAN 5.7), so
            // this is a state the picker has to be able to open in.
            assertEquals(ColorComposer.FALLBACK_START, assertNotNull(controller.state.composer).hex)
        }
    }

    @Test
    fun `a square takes the whole colour, and the preview says the same`() {
        val catalogue = seeded()
        withCatalogue(catalogue) { controller ->
            controller.fillIn()

            controller.takeColorOf("Mavi")

            assertEquals("#1E88E5", assertNotNull(controller.state.composer).hex)
        }
    }

    @Test
    fun `the brightness control moves the brightness and nothing else`() {
        val catalogue = seeded()
        withCatalogue(catalogue) { controller ->
            controller.fillIn()
            controller.takeColorOf("Mavi")
            val before = assertNotNull(controller.state.composer).color

            controller.setBrightness(0.4f)

            val after = assertNotNull(controller.state.composer).color
            assertEquals(before.hue, after.hue, "the place on the wheel moved")
            assertEquals(before.saturation, after.saturation, "the saturation moved")
            assertEquals(0.4f, after.brightness)
        }
    }

    @Test
    fun `turning the wheel asks storage nothing at all`() {
        val catalogue = seeded()
        withCatalogue(catalogue) { controller ->
            controller.fillIn()

            repeat(300) { step ->
                controller.moveOnWheel(WheelPoint(x = step % 40 - 20f, y = 20f - step % 40), radius = 50f)
                controller.setBrightness(step / 300f)
            }

            assertEquals(emptyList(), catalogue.hexLookups, "a drag went to the database")
            assertEquals(0, catalogue.created.size)
        }
    }

    @Test
    fun `the name is stored with the spaces at its ends left behind`() {
        val catalogue = seeded()
        withCatalogue(catalogue) { controller ->
            controller.fillIn(name = "  Lacivert  ")

            controller.save()

            assertEquals("Lacivert", catalogue.created.single().canonicalName)
        }
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
            controller.fillIn(name = "Lacivert")

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
            controller.fillIn(name = "Gri")

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
            controller.fillIn(name = "Gri Ton")

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
