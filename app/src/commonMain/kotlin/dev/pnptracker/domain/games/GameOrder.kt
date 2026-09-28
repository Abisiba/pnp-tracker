package dev.pnptracker.domain.games

import dev.pnptracker.domain.model.EntityId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The version of the game order document, which is nobody else's (PLAN 12.18). */
const val GAME_ORDER_FORMAT_VERSION: Int = 1

/**
 * How the table lays its games out.
 *
 * Two ways and only two, and neither changes the other. [MINE] is the order the
 * user arranged; [ALPHABETICAL] is the same games in the order of their names.
 * The number a game carries is its place in [MINE] whichever of the two is on
 * screen, so reading the table alphabetically never renumbers anything (PLAN
 * 12.18).
 */
enum class GameArrangement {
    MINE,
    ALPHABETICAL,
}

/** Why the order in use is the default rather than what the file said. */
enum class GameOrderProblem {
    COULD_NOT_READ,
    NOT_THE_EXPECTED_SHAPE,
    VERSION_NOT_SUPPORTED,
}

/**
 * The order the user put their games in, as it was last written down.
 *
 * A list of game ids and nothing else. It is never the whole truth about the
 * games on its own — a game may have been added since, or removed — so it is
 * always read *against* the games that exist, by [sequenceFor]: the ones it names
 * keep their places, the ones it does not are placed after them, and the ones it
 * names that are gone are simply not there.
 */
data class GameOrder(
    val gameIds: List<EntityId> = emptyList(),
    val problem: GameOrderProblem? = null,
) {
    /**
     * Every game in [games], in the user's order.
     *
     * A game the order has never seen — a new one, or every game on the first day
     * — goes after the ones it has, in alphabetical order. So the first time the
     * table is numbered it is numbered by name, which is what it was drawn in
     * before there was an order at all, and a game added later arrives at the end
     * rather than jumping into the middle of somebody's arrangement.
     */
    fun sequenceFor(games: List<GameTableRow>): List<EntityId> {
        val existing = games.associateBy { it.gameId }
        val placed = gameIds.filter { it in existing }.distinct()
        val known = placed.toSet()
        val newcomers =
            games
                .filter { it.gameId !in known }
                .sortedWith(alphabetically)
                .map { it.gameId }
        return placed + newcomers
    }

    /** Each game's number: its place in [sequenceFor], counted from one. */
    fun numbersFor(games: List<GameTableRow>): Map<EntityId, Int> = sequenceFor(games).withIndex().associate { (at, id) -> id to at + 1 }

    /**
     * The order with [gameId] moved to where [target] is now.
     *
     * Moving down puts it after the target and moving up puts it before, which is
     * what dropping a row onto another row means in both directions: the one being
     * carried ends up where the one it was dropped on used to be. Every other game
     * keeps its order relative to the rest, so nothing is shuffled that nobody
     * touched. Moving a game onto itself, or onto one that is not there, is no
     * change at all.
     */
    fun moved(
        gameId: EntityId,
        target: EntityId,
        games: List<GameTableRow>,
    ): GameOrder {
        val sequence = sequenceFor(games)
        val from = sequence.indexOf(gameId)
        val to = sequence.indexOf(target)
        if (from < 0 || to < 0 || from == to) return copy(gameIds = sequence, problem = null)
        val without = sequence.toMutableList().apply { removeAt(from) }
        val at = without.indexOf(target) + if (from < to) 1 else 0
        without.add(at, gameId)
        return GameOrder(gameIds = without)
    }
}

/**
 * Game names in Turkish alphabetical order.
 *
 * Not the order of the characters' codes, which puts `Çay` after `Zombicide` and
 * every `İ` after every `Z`: the letters are ranked as the Turkish alphabet ranks
 * them, capital and small alike, and `I` is the capital of `ı` rather than of `i`.
 * The id breaks a tie between two games of the same name, so the same library is
 * always laid out the same way.
 */
val alphabetically: Comparator<GameTableRow> =
    Comparator<GameTableRow> { a, b -> compareTurkish(a.gameName, b.gameName) }
        .thenBy { it.gameId.toString() }

/** The Turkish alphabet, with the three letters it does not use placed where they fall in English. */
private const val ALPHABET = "abcçdefgğhıijklmnoöpqrsştuüvwxyz"

private fun rankOf(character: Char): Int {
    val small =
        when (character) {
            'I' -> 'ı'
            'İ' -> 'i'
            else -> character.lowercaseChar()
        }
    val letter = ALPHABET.indexOf(small)
    return when {
        small.isWhitespace() -> -1
        small in '0'..'9' -> small - '0'
        letter >= 0 -> LETTERS_START + letter
        else -> OTHERS_START + small.code
    }
}

private const val LETTERS_START = 20
private const val OTHERS_START = 100

internal fun compareTurkish(
    first: String,
    second: String,
): Int {
    val a = first.trim()
    val b = second.trim()
    for (at in 0 until minOf(a.length, b.length)) {
        val difference = rankOf(a[at]).compareTo(rankOf(b[at]))
        if (difference != 0) return difference
    }
    return a.length.compareTo(b.length)
}

/**
 * Where the order lives, seen from the side that has no files.
 *
 * Read when the table opens and written when somebody moves a game. A failure to
 * write is not raised: the table is already in the order they gave it, and the
 * only loss is that the next start forgets — the same bargain the table's sizes
 * make (PLAN 12.17, 12.18).
 */
interface GameOrderStore {
    suspend fun read(): GameOrder

    suspend fun write(order: GameOrder)

    companion object {
        /** A table with nowhere to remember its order: numbered by name, every move forgotten. */
        val Forgetful: GameOrderStore =
            object : GameOrderStore {
                override suspend fun read(): GameOrder = GameOrder()

                override suspend fun write(order: GameOrder) = Unit
            }
    }
}

@Serializable
private data class GameOrderDocumentV1(
    val formatVersion: Int,
    val gameIds: List<String>,
)

/** Lenient about fields a newer build adds, for the reason every document here is (PLAN 14.4.12). */
private val gameOrderJson: Json =
    Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        isLenient = false
        allowComments = false
        allowTrailingComma = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

/**
 * Reads [text] as a game order, and says what to use.
 *
 * A file that cannot be understood answers with no order at all — the games are
 * then numbered by name — and a reason, and it is left exactly as it is.
 */
fun gameOrderIn(text: String): GameOrder {
    val document =
        try {
            gameOrderJson.decodeFromString<GameOrderDocumentV1>(text)
        } catch (notOurDocument: Exception) {
            // Deliberately broad and deliberately only here, as in the other two
            // documents: several unrelated exception types, one question.
            return GameOrder(problem = GameOrderProblem.NOT_THE_EXPECTED_SHAPE)
        }
    if (document.formatVersion != GAME_ORDER_FORMAT_VERSION) {
        return GameOrder(problem = GameOrderProblem.VERSION_NOT_SUPPORTED)
    }
    val ids =
        document.gameIds.map { raw ->
            runCatching { EntityId.parse(raw) }.getOrNull()
                ?: return GameOrder(problem = GameOrderProblem.NOT_THE_EXPECTED_SHAPE)
        }
    return GameOrder(gameIds = ids)
}

/** The document this writes for [order], as the file will hold it. */
fun gameOrderDocumentFor(order: GameOrder): String =
    gameOrderJson.encodeToString(
        GameOrderDocumentV1(
            formatVersion = GAME_ORDER_FORMAT_VERSION,
            gameIds = order.gameIds.map { it.toString() },
        ),
    )
