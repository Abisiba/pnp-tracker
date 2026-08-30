package dev.pnptracker.ui.feature.colors

import dev.pnptracker.data.repository.ColorCatalogue
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.BaseColorRestoreBlock
import dev.pnptracker.domain.colors.BaseColorRestorePlan
import dev.pnptracker.domain.colors.BlockedBaseColor
import dev.pnptracker.domain.colors.ColorRemoval
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.ColorSummary
import dev.pnptracker.domain.colors.ColorUsage
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the fake was asked to create. */
private data class CreatedColor(
    val canonicalName: String,
    val hex: String,
)

/** What the fake was asked to change. */
private data class EditedColor(
    val id: EntityId,
    val expectedName: String,
    val expectedHex: String,
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

    val edited = mutableListOf<EditedColor>()
    val deleted = mutableListOf<EntityId>()
    val usageLookups = mutableListOf<EntityId>()
    var previews = 0
    var restores = 0
    var usage: ColorUsage = ColorUsage.UNUSED
    var removal: ColorRemoval = ColorRemoval(0, 0, 0, 0)
    var plan: BaseColorRestorePlan = BaseColorRestorePlan(emptyList(), emptyList())
    var restoreOutcome: BaseColorRestore = BaseColorRestore.NothingMissing

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

    override suspend fun editColor(
        id: EntityId,
        expectedName: String,
        expectedHex: String,
        canonicalName: String,
        hex: String,
    ) {
        heldSave?.await()
        breakWith?.let { throw it }
        failWith?.let { throw ColorSetupException(it) }
        edited += EditedColor(id, expectedName, expectedHex, canonicalName, hex)
        colors.value =
            colors.value.map { color ->
                if (color.id == id) color.copy(canonicalName = canonicalName, hex = hex) else color
            }
    }

    override suspend fun usageOf(id: EntityId): ColorUsage {
        usageLookups += id
        return usage
    }

    override suspend fun deleteColor(id: EntityId): ColorRemoval {
        heldSave?.await()
        failWith?.let { throw ColorSetupException(it) }
        deleted += id
        colors.value = colors.value.filterNot { it.id == id }
        return removal
    }

    override suspend fun previewBaseColorRestore(): BaseColorRestorePlan {
        previews += 1
        return plan
    }

    override suspend fun restoreMissingBaseColors(): BaseColorRestore {
        heldSave?.await()
        restores += 1
        failWith?.let { throw ColorSetupException(it) }
        return restoreOutcome
    }
}

class ColorCatalogueControllerTest {
    private fun aColor(
        name: String,
        hex: String,
        sortOrder: Int = 0,
    ) = ColorSummary(IdGenerator.Random.newId(), name, hex, sortOrder)

    /**
     * The storage the controller under test is talking to.
     *
     * Kept here so a test can read what was written without having to thread the
     * fake through every line; only one runs at a time.
     */
    private var storage: FakeCatalogue? = null

    private fun catalogueOf(controller: ColorCatalogueController): FakeCatalogue {
        assertNotNull(controller)
        return assertNotNull(storage, "no catalogue is open")
    }

    private fun colorsOf(controller: ColorCatalogueController): List<ColorSummary> =
        assertIs<ColorCatalogueState.Content>(controller.state.catalogue).colors

    private fun withCatalogue(
        catalogue: FakeCatalogue,
        body: suspend CoroutineScope.(ColorCatalogueController) -> Unit,
    ) = runBlocking {
        storage = catalogue
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

            controller.cancel()

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

            controller.cancel()

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

    // ------------------------------------------------------------------ editing

    private fun ColorCatalogueController.idOf(name: String) =
        assertIs<ColorCatalogueState.Content>(state.catalogue).colors.first { it.canonicalName == name }.id

    @Test
    fun `editing opens the same picker on the colour as it stands`() =
        withCatalogue(seeded()) { controller ->
            controller.startEditing(controller.idOf("Gri"))

            val work = assertIs<ColorWork.Editing>(controller.state.work)
            assertEquals("Gri", work.composer.name, "the name did not start where the colour is")
            assertEquals("#808080", work.composer.hex)
            assertEquals(work.colorId, work.composer.editing)
            assertFalse(work.composer.isTouched, "a form nobody has touched said it had changes")
        }

    @Test
    fun `a base colour is offered for editing like any other`() =
        withCatalogue(seeded()) { controller ->
            controller.startEditing(controller.idOf("Siyah"))

            assertIs<ColorWork.Editing>(controller.state.work)
        }

    @Test
    fun `saving an edit hands over the name, the value and what they were`() =
        withCatalogue(seeded()) { catalogue ->
            val controller = catalogue
            controller.startEditing(controller.idOf("Gri"))
            controller.editName("Duman")
            controller.setBrightness(0.5f)
            val hex = assertNotNull(controller.state.composer).hex

            controller.save()

            assertEquals(1, catalogueOf(controller).edited.size)
            val edit = catalogueOf(controller).edited.single()
            assertEquals("Duman", edit.canonicalName)
            assertEquals(hex, edit.hex)
            assertEquals("Gri", edit.expectedName, "the form did not say what it was opened on")
            assertEquals("#808080", edit.expectedHex)
            assertNull(controller.state.work, "the form stayed open after it saved")
        }

    @Test
    fun `saving an edit that changes nothing writes nothing`() =
        withCatalogue(seeded()) { controller ->
            controller.startEditing(controller.idOf("Gri"))

            controller.save()

            assertEquals(emptyList(), catalogueOf(controller).edited, "a colour that did not change was written")
            assertNull(controller.state.work)
        }

    @Test
    fun `changing only the letters of a name is a change worth saving`() =
        withCatalogue(seeded()) { controller ->
            controller.startEditing(controller.idOf("Gri"))
            controller.editName("GRİ")

            controller.save()

            assertEquals("GRİ", catalogueOf(controller).edited.single().canonicalName)
        }

    @Test
    fun `a refused edit keeps the form, the name and the colour exactly`() =
        withCatalogue(seeded()) { controller ->
            catalogueOf(controller).failWith = ColorSetupFailure.NAME_ALREADY_USED
            controller.startEditing(controller.idOf("Gri"))
            controller.editName("Mavi")
            val before = assertNotNull(controller.state.composer)

            controller.save()

            val work = assertIs<ColorWork.Editing>(controller.state.work)
            assertEquals(before, work.composer, "the refusal took the answers the user had given")
            assertEquals(ColorSetupFailure.NAME_ALREADY_USED, work.failure)
            assertFalse(work.isSaving, "the form was left frozen")
        }

    @Test
    fun `a colour that went while the form was open is refused in its own words`() =
        withCatalogue(seeded()) { controller ->
            catalogueOf(controller).failWith = ColorSetupFailure.COLOR_CHANGED_MEANWHILE
            controller.startEditing(controller.idOf("Gri"))
            controller.editName("Duman")

            controller.save()

            assertEquals(ColorSetupFailure.COLOR_CHANGED_MEANWHILE, assertNotNull(controller.state.work).failure)
        }

    @Test
    fun `the same value warning never counts the colour being edited`() =
        withCatalogue(seeded()) { controller ->
            controller.startEditing(controller.idOf("Gri"))

            val sharing = assertNotNull(controller.state.composer).sharedWith(colorsOf(controller))

            assertEquals(emptyList(), sharing, "the colour was told it looks like itself")
        }

    @Test
    fun `taking another colour's value is remarked on but not refused`() =
        withCatalogue(seeded()) { controller ->
            controller.startEditing(controller.idOf("Gri"))
            controller.takeColorOf("Mavi")

            val composer = assertNotNull(controller.state.composer)
            assertEquals(listOf("Mavi"), composer.sharedWith(colorsOf(controller)).map { it.canonicalName })
            assertTrue(composer.canSave, "a shared value stood in the way of saving")
        }

    @Test
    fun `two saves of one edit hand it over once`() =
        withCatalogue(seeded()) { controller ->
            val held = CompletableDeferred<Unit>()
            catalogueOf(controller).heldSave = held
            controller.startEditing(controller.idOf("Gri"))
            controller.editName("Duman")

            val first = launch { controller.save() }
            yield()
            controller.save()
            held.complete(Unit)
            first.join()

            assertEquals(1, catalogueOf(controller).edited.size, "the same change was saved twice")
        }

    @Test
    fun `a colour arriving from the database leaves an open edit alone`() =
        withCatalogue(seeded()) { controller ->
            controller.startEditing(controller.idOf("Gri"))
            controller.editName("Duman")
            val before = assertNotNull(controller.state.composer)

            catalogueOf(controller).colors.value =
                catalogueOf(controller).colors.value + aColor("Bordo", "#7B1F2B", sortOrder = 12)
            yield()

            assertEquals(before, assertNotNull(controller.state.composer), "the list arriving wiped out the form")
        }

    @Test
    fun `making a colour still behaves exactly as it did`() =
        withCatalogue(seeded()) { controller ->
            controller.fillIn("Lacivert")
            controller.takeColorOf("Mavi")

            controller.save()

            assertEquals(listOf("Lacivert" to "#1E88E5"), catalogueOf(controller).created.map { it.canonicalName to it.hex })
            assertNull(controller.state.work)
            assertEquals(emptyList(), catalogueOf(controller).edited, "creating a colour reached the editing path")
        }

    // ----------------------------------------------------------------- deleting

    @Test
    fun `asking about a colour opens a confirmation carrying what it costs`() =
        withCatalogue(seeded()) { controller ->
            catalogueOf(controller).usage =
                ColorUsage(taskCount = 3, unfinishedTaskCount = 2, gameCount = 2, tasksLosingTheirLastColor = 1, samples = emptyList())

            controller.startDeleting(controller.idOf("Gri"))

            val work = assertIs<ColorWork.Deleting>(controller.state.work)
            assertEquals("Gri", work.color.canonicalName)
            assertEquals(3, work.usage.taskCount)
            assertEquals(listOf(controller.idOf("Gri")), catalogueOf(controller).usageLookups)
        }

    @Test
    fun `a colour nobody uses is confirmed too`() =
        withCatalogue(seeded()) { controller ->
            controller.startDeleting(controller.idOf("Pembe"))

            assertIs<ColorWork.Deleting>(controller.state.work)
            assertEquals(emptyList(), catalogueOf(controller).deleted, "an unused colour went without being asked about")
        }

    @Test
    fun `confirming removes the colour and says what went`() =
        withCatalogue(seeded()) { controller ->
            catalogueOf(controller).removal =
                ColorRemoval(taskCount = 3, removedRelationCount = 4, removedAliasCount = 1, tasksLeftWithoutAColor = 2)
            val grey = controller.idOf("Gri")
            controller.startDeleting(grey)

            controller.save()

            assertEquals(listOf(grey), catalogueOf(controller).deleted)
            val notice = assertIs<ColorNotice.Removed>(controller.state.notice)
            assertEquals("Gri", notice.colorName)
            assertEquals(2, notice.removal.tasksLeftWithoutAColor)
            assertNull(controller.state.work)
        }

    @Test
    fun `giving up on a confirmation removes nothing`() =
        withCatalogue(seeded()) { controller ->
            controller.startDeleting(controller.idOf("Gri"))

            controller.cancel()

            assertNull(controller.state.work)
            assertEquals(emptyList(), catalogueOf(controller).deleted)
        }

    @Test
    fun `two confirmations of one removal only remove it once`() =
        withCatalogue(seeded()) { controller ->
            val held = CompletableDeferred<Unit>()
            catalogueOf(controller).heldSave = held
            controller.startDeleting(controller.idOf("Gri"))

            val first = launch { controller.save() }
            yield()
            controller.save()
            held.complete(Unit)
            first.join()

            assertEquals(1, catalogueOf(controller).deleted.size, "the colour was removed twice")
        }

    @Test
    fun `a colour that is already gone is said so rather than shown as a saving problem`() =
        withCatalogue(seeded()) { controller ->
            controller.startDeleting(controller.idOf("Gri"))
            catalogueOf(controller).failWith = ColorSetupFailure.COLOR_NO_LONGER_EXISTS

            controller.save()

            val work = assertIs<ColorWork.Deleting>(controller.state.work)
            assertEquals(ColorSetupFailure.COLOR_NO_LONGER_EXISTS, work.failure)
            assertFalse(work.isSaving)
        }

    @Test
    fun `the keyboard goes to the next colour once one is gone`() =
        withCatalogue(seeded()) { controller ->
            val grey = controller.idOf("Gri")
            val brown = controller.idOf("Kahverengi")
            controller.startDeleting(grey)

            controller.save()

            assertEquals(brown, controller.focusTarget, "the keyboard was left on a row that is not there")
        }

    @Test
    fun `the keyboard falls back to the row before when the last one goes`() =
        withCatalogue(seeded()) { controller ->
            val pink = controller.idOf("Pembe")
            val purple = controller.idOf("Mor")
            controller.startDeleting(pink)

            controller.save()

            assertEquals(purple, controller.focusTarget)
        }

    // ---------------------------------------------------------------- restoring

    @Test
    fun `restoring shows what is missing before anything is written`() =
        withCatalogue(seeded()) { controller ->
            catalogueOf(controller).plan = BaseColorRestorePlan(missing = listOf(baseColors[0]), blocked = emptyList())

            controller.startRestoring()

            val work = assertIs<ColorWork.Restoring>(controller.state.work)
            assertEquals(listOf("Beyaz"), work.plan.missing.map { it.canonicalName })
            assertEquals(0, catalogueOf(controller).restores, "the preview wrote something")
        }

    @Test
    fun `nothing missing is said and nothing is written`() =
        withCatalogue(seeded()) { controller ->
            controller.startRestoring()

            assertNull(controller.state.work, "a confirmation was opened with nothing to confirm")
            assertEquals(ColorNotice.NothingMissing, controller.state.notice)
            assertEquals(0, catalogueOf(controller).restores)
        }

    @Test
    fun `a preview with something in the way will not offer to go ahead`() =
        withCatalogue(seeded()) { controller ->
            catalogueOf(controller).plan =
                BaseColorRestorePlan(
                    missing = listOf(baseColors[0]),
                    blocked = listOf(BlockedBaseColor("Beyaz", "#FFFFFF", BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR)),
                )

            controller.startRestoring()
            controller.save()

            assertFalse(assertIs<ColorWork.Restoring>(controller.state.work).plan.canRestore)
            assertEquals(0, catalogueOf(controller).restores, "a blocked restore was sent anyway")
        }

    @Test
    fun `confirming a restore says which colours came back`() =
        withCatalogue(seeded()) { controller ->
            catalogueOf(controller).plan = BaseColorRestorePlan(missing = listOf(baseColors[0], baseColors[1]), blocked = emptyList())
            catalogueOf(controller).restoreOutcome = BaseColorRestore.Restored(listOf("Beyaz", "Siyah"))

            controller.startRestoring()
            controller.save()

            assertEquals(ColorNotice.Restored(listOf("Beyaz", "Siyah")), controller.state.notice)
            assertNull(controller.state.work)
        }

    @Test
    fun `a restore that comes back blocked stays open and shows every reason`() =
        withCatalogue(seeded()) { controller ->
            catalogueOf(controller).plan = BaseColorRestorePlan(missing = listOf(baseColors[0]), blocked = emptyList())
            catalogueOf(controller).restoreOutcome =
                BaseColorRestore.Blocked(
                    listOf(BlockedBaseColor("Beyaz", "#FFFFFF", BaseColorRestoreBlock.APPEARED_MEANWHILE)),
                )

            controller.startRestoring()
            controller.save()

            val work = assertIs<ColorWork.Restoring>(controller.state.work)
            assertEquals(
                listOf(BaseColorRestoreBlock.APPEARED_MEANWHILE),
                work.plan.blocked.map { it.reason },
            )
            assertFalse(work.isSaving)
            assertNull(controller.state.notice, "a blocked restore was reported as something that happened")
        }

    @Test
    fun `two confirmations of one restore only run it once`() =
        withCatalogue(seeded()) { controller ->
            val held = CompletableDeferred<Unit>()
            catalogueOf(controller).heldSave = held
            catalogueOf(controller).plan = BaseColorRestorePlan(missing = listOf(baseColors[0]), blocked = emptyList())

            controller.startRestoring()
            val first = launch { controller.save() }
            yield()
            controller.save()
            held.complete(Unit)
            first.join()

            assertEquals(1, catalogueOf(controller).restores, "the restore ran twice")
        }

    @Test
    fun `one surface is open at a time and a second is not allowed over it`() =
        withCatalogue(seeded()) { controller ->
            controller.startComposer()
            controller.editName("Lacivert")

            controller.startDeleting(controller.idOf("Gri"))
            controller.startRestoring()

            val open = assertIs<ColorWork.Creating>(controller.state.work)
            assertEquals("Lacivert", open.composer.name, "a half typed form was pushed aside")
            assertEquals(emptyList(), catalogueOf(controller).usageLookups, "a surface nobody could see asked the database")
            assertEquals(0, catalogueOf(controller).previews)

            controller.cancel()
            controller.startDeleting(controller.idOf("Gri"))
            assertIs<ColorWork.Deleting>(controller.state.work)
        }
}
