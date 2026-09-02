package dev.pnptracker.domain.importreview

import dev.pnptracker.domain.importhint.ColorVocabulary
import dev.pnptracker.domain.importhint.ColorVocabularyEntry
import dev.pnptracker.domain.importhint.ImportHintAnalyzer
import dev.pnptracker.domain.model.HintDecision
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.domain.spreadsheet.CellSnapshot
import dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a new draft starts out saying, and — just as much — what it refuses to
 * say on the user's behalf.
 *
 * The analyzer is run for real here rather than stubbed, because the whole point
 * of this layer is that the suggestions written onto a draft are the ones the
 * detectors actually found.
 */
class DraftSuggestionTest {
    private val vocabulary =
        ColorVocabulary.of(
            listOf(
                ColorVocabularyEntry("Mavi"),
                ColorVocabularyEntry("Açık Mavi"),
                ColorVocabularyEntry("Kırmızı", listOf("KIRMIZI")),
            ),
        )

    private fun analyze(
        text: String,
        columnIndex: Int,
    ) = ImportHintAnalyzer(vocabulary).analyze(
        CellSnapshot(rowIndex = 1, columnIndex = columnIndex, rawText = text, kind = SpreadsheetCellKind.TEXT),
        requireNotNull(
            dev.pnptracker.domain.importhint.ReferenceColumnLayout
                .suggestionFor(columnIndex),
        ).sourceColumnType,
    )

    private fun draftFrom(
        text: String,
        columnIndex: Int,
        start: Int = 0,
        end: Int = text.length,
    ) = initialDraftFromSelection(columnIndex, selectTaskNameIn(text, start, end))

    // -------------------------------------------------- what really is found

    @Test
    fun `a line opening with a count suggests that count and the pending marker`() {
        val draft = draftFrom("15 KIRMIZI**", THREE_D_COLUMN)

        assertEquals("15 KIRMIZI", draft.name)
        assertEquals(15, draft.requiredQuantity)
        assertEquals(HintDecision.PENDING, draft.completionHint, "a marker is a question, never an answer")
        assertEquals(PoolType.THREE_D, draft.suggestedPoolType)
        assertTrue(!draft.needsInfo, "the count was written down, so nothing is missing")
    }

    @Test
    fun `the analyzer really reports that count and that marker`() {
        val analysis = analyze("15 KIRMIZI**", THREE_D_COLUMN)

        assertEquals(15, analysis.quantity?.value)
        assertEquals(1, analysis.completionMarkers.size)
        assertEquals(HintDecision.PENDING, analysis.completionMarkers.single().decision)
    }

    @Test
    fun `an ambiguous colour phrase becomes no colour at all`() {
        val draft = draftFrom("12 Mavi/Açık Mavi ev", THREE_D_COLUMN)

        // PLAN 11.6: this is a choice the user has not made yet, and a draft is
        // born colourless whatever the words say.
        assertEquals(12, draft.requiredQuantity)
        val analysis = analyze("12 Mavi/Açık Mavi ev", THREE_D_COLUMN)
        assertEquals(1, analysis.alternativeColors.size, "the phrase is reported as a choice")
        assertEquals(
            listOf("Mavi", "Açık Mavi"),
            analysis.alternativeColors
                .single()
                .options
                .map { it.canonicalName },
        )
        assertEquals(
            HintDecision.PENDING,
            analysis.alternativeColors.single().decision,
            "a colour choice is offered, never taken",
        )
    }

    @Test
    fun `the missing column suggests the missing mark and no pool`() {
        val draft = draftFrom("kayıp meeple", MISSING_COLUMN)

        assertTrue(draft.isMissing)
        assertTrue(!draft.isBorrowed)
        assertTrue(draft.needsClassification, "PLAN 10 leaves the pool to the user")
        assertNull(draft.suggestedPoolType)
    }

    @Test
    fun `the borrowed column suggests the borrowed mark and no pool`() {
        val draft = draftFrom("ödünç zar", BORROWED_COLUMN)

        assertTrue(draft.isBorrowed)
        assertTrue(!draft.isMissing)
        assertTrue(draft.needsClassification)
        assertNull(draft.suggestedPoolType)
    }

    @Test
    fun `a line with no count leaves the total unknown and asks for information`() {
        val draft = draftFrom("sayısına bakılacak", THREE_D_COLUMN)

        assertNull(draft.requiredQuantity, "no stand-in number is invented")
        assertTrue(draft.needsInfo)
    }

    @Test
    fun `a number inside the words is not read as a count`() {
        val draft = draftFrom("Ticket to Ride 1910", CARD_COLUMN)

        assertNull(draft.requiredQuantity)
        assertTrue(draft.needsInfo)
    }

    @Test
    fun `a count of nought is not a count`() {
        assertNull(draftFrom("0 token", THREE_D_COLUMN).requiredQuantity)
    }

    // ------------------------------------------------------- typed by hand

    @Test
    fun `a task typed by hand carries no selection and reads nothing out of the words`() {
        val draft = initialDraftByHand(THREE_D_COLUMN, "  15 kırmızı token  ")

        assertEquals("15 kırmızı token", draft.name, "the ends are trimmed and the middle is kept")
        assertNull(draft.selectionStartIndex)
        assertNull(draft.selectionEndIndex)
        assertNull(draft.requiredQuantity)
        assertTrue(!draft.needsInfo, "there is no unfinished source sentence to have left anything out")
        assertEquals(HintDecision.NONE, draft.completionHint)
    }

    @Test
    fun `a task typed by hand still carries its column's marks`() {
        val draft = initialDraftByHand(BORROWED_COLUMN, "ödünç zar")

        assertTrue(draft.isBorrowed)
        assertTrue(draft.needsClassification)
    }

    @Test
    fun `a name that says nothing is refused`() {
        assertEquals(
            ImportReviewFailure.TASK_NAME_EMPTY,
            assertFailsWith<ImportReviewException> { initialDraftByHand(THREE_D_COLUMN, "   ") }.failure,
        )
    }

    // ------------------------------------------------------------ selections

    @Test
    fun `a draft cut out of the middle keeps the offsets it was cut at`() {
        val text = "40 token ×14"
        val draft = draftFrom(text, THREE_D_COLUMN, start = 2, end = 9)

        assertEquals("token", draft.name)
        assertEquals(3, draft.selectionStartIndex)
        assertEquals(8, draft.selectionEndIndex)
        assertNull(draft.requiredQuantity, "the count belongs to the words that were left out")
    }

    @Test
    fun `two overlapping cuts describe two independent drafts`() {
        val text = "kırmızı token"
        val whole = draftFrom(text, THREE_D_COLUMN, 0, 13)
        val part = draftFrom(text, THREE_D_COLUMN, 8, 13)

        assertEquals("kırmızı token", whole.name)
        assertEquals("token", part.name)
        assertEquals(0 to 13, whole.selectionStartIndex to whole.selectionEndIndex)
        assertEquals(8 to 13, part.selectionStartIndex to part.selectionEndIndex)
    }

    @Test
    fun `no suggestion ever arrives already agreed to`() {
        val draft = draftFrom("15 KIRMIZI**", THREE_D_COLUMN)

        assertTrue(draft.completionHint != HintDecision.ACCEPTED)
        // The column's guess is a suggestion; what the user selects is stored
        // separately and starts out unset.
        assertEquals(PoolType.THREE_D, draft.suggestedPoolType)
    }

    @Test
    fun `a game name cell suggests no pool at all`() {
        val draft = draftFrom("Wingspan", GAME_COLUMN)

        assertNull(draft.suggestedPoolType)
        assertEquals(SourceColumnType.GAME, requireNotNull(columnOf(GAME_COLUMN)).sourceColumnType)
    }

    private fun columnOf(columnIndex: Int) =
        dev.pnptracker.domain.importhint.ReferenceColumnLayout
            .suggestionFor(columnIndex)

    private companion object {
        const val GAME_COLUMN = 0
        const val THREE_D_COLUMN = 1
        const val CARD_COLUMN = 2
        const val MISSING_COLUMN = 5
        const val BORROWED_COLUMN = 6
    }
}
