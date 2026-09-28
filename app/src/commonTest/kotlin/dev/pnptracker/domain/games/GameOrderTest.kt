package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The order the user puts their games in, and what it does not touch (PLAN 12.18). */
class GameOrderTest {
    private fun row(name: String) =
        GameTableRow(
            gameId = IdGenerator.Random.newId(),
            gameName = name,
            isCompleted = false,
            cells =
                CellColumnType.entries.map { column ->
                    CellPreview(columnType = column, cellId = IdGenerator.Random.newId(), segments = emptyList())
                },
        )

    private val harmonies = row("Harmonies")
    private val catan = row("Catan")
    private val skyTeam = row("Sky Team")
    private val games = listOf(harmonies, catan, skyTeam)

    private fun GameOrder.names(games: List<GameTableRow>): List<String> {
        val byId = games.associateBy { it.gameId }
        return sequenceFor(games).map { byId.getValue(it).gameName }
    }

    @Test
    fun `with no order yet the games are numbered by name`() {
        // What the table looked like before there was an order at all, so the
        // first start after this changes nothing on screen.
        assertEquals(listOf("Catan", "Harmonies", "Sky Team"), GameOrder().names(games))
        assertEquals(mapOf(catan.gameId to 1, harmonies.gameId to 2, skyTeam.gameId to 3), GameOrder().numbersFor(games))
    }

    @Test
    fun `the example from the request, in both layouts`() {
        // 1 Harmonies, 2 Catan, 3 Sky Team — arranged by moving Harmonies above Catan.
        val mine = GameOrder().moved(harmonies.gameId, catan.gameId, games)
        assertEquals(listOf("Harmonies", "Catan", "Sky Team"), mine.names(games))

        val numbers = mine.numbersFor(games)
        val alphabetical = games.sortedWith(alphabetically).map { "${numbers[it.gameId]} ${it.gameName}" }

        // A–Z moves the rows and leaves every number where it was.
        assertEquals(listOf("2 Catan", "1 Harmonies", "3 Sky Team"), alphabetical)
        // And the order itself is the same object it was: nothing to put back.
        assertEquals(listOf("Harmonies", "Catan", "Sky Team"), mine.names(games))
    }

    @Test
    fun `moving down puts a game after the one it is dropped on, and up before it`() {
        val start = GameOrder(listOf(harmonies.gameId, catan.gameId, skyTeam.gameId))

        assertEquals(listOf("Catan", "Sky Team", "Harmonies"), start.moved(harmonies.gameId, skyTeam.gameId, games).names(games))
        assertEquals(listOf("Sky Team", "Harmonies", "Catan"), start.moved(skyTeam.gameId, harmonies.gameId, games).names(games))
        // The ones nobody touched keep their order relative to each other.
        assertEquals(listOf("Harmonies", "Sky Team", "Catan"), start.moved(catan.gameId, skyTeam.gameId, games).names(games))
    }

    @Test
    fun `dropping a game on itself or on one that is gone changes nothing`() {
        val start = GameOrder(listOf(harmonies.gameId, catan.gameId, skyTeam.gameId))

        assertEquals(start.gameIds, start.moved(catan.gameId, catan.gameId, games).gameIds)
        assertEquals(start.gameIds, start.moved(catan.gameId, IdGenerator.Random.newId(), games).gameIds)
    }

    @Test
    fun `a game added later arrives at the end, not in the middle of an arrangement`() {
        val arranged = GameOrder(listOf(skyTeam.gameId, harmonies.gameId, catan.gameId))
        val azul = row("Azul")

        // `Azul` would be first by name. It is last, because nobody put it anywhere yet.
        assertEquals(listOf("Sky Team", "Harmonies", "Catan", "Azul"), arranged.names(games + azul))
    }

    @Test
    fun `a game that is gone takes its number with it and the rest close up`() {
        val arranged = GameOrder(listOf(harmonies.gameId, catan.gameId, skyTeam.gameId))

        val numbers = arranged.numbersFor(listOf(harmonies, skyTeam))

        assertEquals(mapOf(harmonies.gameId to 1, skyTeam.gameId to 2), numbers)
    }

    @Test
    fun `alphabetical order is the turkish alphabet and not character codes`() {
        val names = listOf("Zombicide", "Çay", "Dixit", "Catan", "İstanbul", "Iğdır", "ılık", "irmik", "Öküz", "Oyun", "Şah", "Sky Team")
        val sorted = names.map(::row).sortedWith(alphabetically).map { it.gameName }

        assertEquals(
            listOf("Catan", "Çay", "Dixit", "Iğdır", "ılık", "irmik", "İstanbul", "Oyun", "Öküz", "Sky Team", "Şah", "Zombicide"),
            sorted,
        )
    }

    @Test
    fun `an order survives being written and read back`() {
        val order = GameOrder(listOf(skyTeam.gameId, harmonies.gameId, catan.gameId))

        val read = gameOrderIn(gameOrderDocumentFor(order))

        assertEquals(order.gameIds, read.gameIds)
        assertNull(read.problem)
    }

    @Test
    fun `a file that is not this document numbers the games by name and says why`() {
        listOf(
            "" to GameOrderProblem.NOT_THE_EXPECTED_SHAPE,
            "{}" to GameOrderProblem.NOT_THE_EXPECTED_SHAPE,
            """{"formatVersion":1,"gameIds":["not-an-id"]}""" to GameOrderProblem.NOT_THE_EXPECTED_SHAPE,
            """{"formatVersion":2,"gameIds":[]}""" to GameOrderProblem.VERSION_NOT_SUPPORTED,
        ).forEach { (text, expected) ->
            val read = gameOrderIn(text)
            assertEquals(expected, read.problem, "`$text`")
            assertTrue(read.gameIds.isEmpty(), "`$text` left an order behind")
            assertEquals(listOf("Catan", "Harmonies", "Sky Team"), read.names(games), "`$text`")
        }
    }

    @Test
    fun `an id in the file that names no game is simply not there`() {
        val gone: EntityId = IdGenerator.Random.newId()
        val order = GameOrder(listOf(gone, catan.gameId, harmonies.gameId))

        assertEquals(listOf("Catan", "Harmonies", "Sky Team"), order.names(games))
        assertEquals(1, order.numbersFor(games)[catan.gameId])
    }
}
