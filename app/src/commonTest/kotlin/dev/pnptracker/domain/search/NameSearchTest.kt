package dev.pnptracker.domain.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What general search really matches, on the text a user can really type.
 *
 * The Turkish alphabet is the whole difficulty. `KIRMIZI` and `Kırmızı` are one
 * word shouted and spoken, and somebody typing the first has to find the second
 * — but `ışık` and `isik` are two different words, and merging them would hand
 * back a list of the wrong thing. The colour catalogue merges all four `i`
 * letters on purpose (`normalizeColorTerm` says why); this must not, and these
 * are what hold the two apart.
 *
 * Everything else is about leaving text alone: a name may hold an accent, an
 * emoji, a skin tone, a joined family or a flag, and a search that quietly
 * rewrote any of them would match things nobody asked for.
 */
class NameSearchTest {
    private fun matches(
        haystack: String,
        needle: String,
    ): Boolean = SearchQuery(needle).matches(haystack)

    // ------------------------------------------------------------ the basics

    @Test
    fun `a word is found inside a longer name`() {
        assertTrue(matches("Büyük kırmızı ev", "kırmızı"))
        assertTrue(matches("Harmonies", "harm"))
    }

    @Test
    fun `a word that is not there is not found`() {
        assertFalse(matches("Harmonies", "Wingspan"))
    }

    @Test
    fun `an empty query matches everything, including empty text`() {
        assertTrue(SearchQuery("").isEmpty)
        assertTrue(matches("Harmonies", ""))
        assertTrue(matches("", ""))
    }

    @Test
    fun `a query of nothing but spaces is still empty`() {
        assertTrue(SearchQuery("   ").isEmpty)
        assertTrue(matches("Harmonies", "   "))
    }

    @Test
    fun `space around the query is ignored and space inside it is not`() {
        assertTrue(matches("Kırmızı ev", "  kırmızı  "))
        // Several words are an ordinary substring, in the order they were typed.
        assertTrue(matches("Büyük kırmızı ev", "kırmızı ev"))
        assertFalse(matches("Büyük kırmızı ev", "ev kırmızı"))
    }

    @Test
    fun `punctuation in the name is matched as it was written`() {
        assertTrue(matches("Kutu: 30×30 (kapaklı)", "30×30"))
        assertTrue(matches("Kutu: 30×30 (kapaklı)", "(kapaklı)"))
    }

    // ------------------------------------------------- the Turkish alphabet

    @Test
    fun `a shouted dotless i finds a spoken one`() {
        assertTrue(matches("Kırmızı ev", "KIRMIZI"))
        assertTrue(matches("Işıklı direk", "IŞIK"))
        assertTrue(matches("ışık", "IŞIK"))
    }

    @Test
    fun `a shouted dotted i finds a spoken one`() {
        assertTrue(matches("isim", "İSİM"))
        assertTrue(matches("İsim listesi", "isim"))
    }

    @Test
    fun `the dotted and the dotless i are not the same letter`() {
        assertFalse(matches("ışık", "isik"), "`ı` was folded into `i`")
        assertFalse(matches("isim", "ısım"), "`i` was folded into `ı`")
        assertFalse(matches("Kılıç", "kilic"))
    }

    @Test
    fun `the other Turkish letters keep their own identity`() {
        assertTrue(matches("güçlük", "GÜÇLÜK"))
        assertTrue(matches("Şeker", "şeker"))
        assertFalse(matches("güçlük", "guclok"))
    }

    // ------------------------------------------------------------- Unicode

    @Test
    fun `text written composed and decomposed matches either way`() {
        val composed = "\u0130sim"
        val decomposed = "I\u0307sim"

        assertEquals(4, composed.length, "the fixture is not the composed form")
        assertEquals(5, decomposed.length, "the fixture is not the decomposed form")

        assertTrue(matches(composed, decomposed), "a decomposed query did not find composed text")
        assertTrue(matches(decomposed, composed), "a composed query did not find decomposed text")
        assertEquals(searchFold(composed), searchFold(decomposed))
    }

    @Test
    fun `an accent is not stripped away`() {
        assertTrue(matches("café", "café"))
        assertFalse(matches("café", "cafe"), "an accent was dropped from the name")
    }

    @Test
    fun `an emoji written as a surrogate pair is matched whole`() {
        assertTrue(matches("Gülen yüz 😀 parçası", "😀"))
        assertTrue(matches("😀", "😀"))
    }

    @Test
    fun `an emoji and its skin tone stay together`() {
        val thumb = "👍🏽"

        assertTrue(matches("Onay $thumb işareti", thumb))
        assertEquals(thumb, searchFold(thumb), "the skin tone was rewritten")
    }

    @Test
    fun `a family joined by zero width joiners is left alone`() {
        val family = "👨‍👩‍👧‍👦"

        assertTrue(matches("$family aile figürü", family))
        assertEquals(family, searchFold(family), "a joiner was dropped")
    }

    @Test
    fun `a flag is left alone`() {
        val turkey = "🇹🇷"

        assertTrue(matches("$turkey bayrak çıkartması", turkey))
        assertEquals(turkey, searchFold(turkey), "the flag was taken apart")
    }

    @Test
    fun `folding a query twice is folding it once`() {
        listOf("KIRMIZI", "İSİM", "güçlük", "👍🏽", "Kutu: 30×30").forEach { text ->
            assertEquals(searchFold(text), searchFold(searchFold(text)), "folding `$text` was not stable")
        }
    }

    @Test
    fun `any of several names is enough`() {
        val query = SearchQuery("wingspan")

        assertTrue(query.matchesAny(listOf("Kırmızı ev", "Wingspan")))
        assertFalse(query.matchesAny(listOf("Kırmızı ev", "Harmonies")))
        assertTrue(SearchQuery("").matchesAny(emptyList()), "an empty query stopped matching")
    }
}
