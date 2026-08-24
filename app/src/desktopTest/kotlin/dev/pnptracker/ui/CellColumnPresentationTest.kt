package dev.pnptracker.ui

import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.PoolType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What a column is called on screen, and that it is never called `THREE_D`.
 *
 * `CellColumnType` is an English constant and the application is Turkish, so a
 * column printed straight into a sentence puts the wrong language in front of
 * the user. It went unnoticed once because nothing between the enum and the
 * screen had an opinion about it: `stringResource` takes `Any` and calls
 * `toString`, which compiles and reads as if it were working.
 *
 * These are checked without drawing anything. A resource is compared by its key,
 * and the words themselves are read out of the file the build compiles, so the
 * chain from column to Turkish word is followed end to end with no UI test
 * dependency in the way.
 */
class CellColumnPresentationTest {
    private val strings: String by lazy {
        Files.readString(resourceFile())
    }

    private fun resourceFile(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .map { it.resolve("src/commonMain/composeResources/values/strings.xml") }
                .firstOrNull { Files.isRegularFile(it) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("Could not locate strings.xml from ${Path.of("").toAbsolutePath()}")
    }

    private fun valueOf(key: String): String {
        val match =
            Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(strings)
                ?: fail("There is no string resource called $key.")
        return match.groupValues[1]
    }

    @Test
    fun `every column has a Turkish name`() {
        val expected =
            mapOf(
                CellColumnType.THREE_D to "3D Baskı",
                CellColumnType.CARD to "Kart",
                CellColumnType.BOARD to "Mukavva",
                CellColumnType.SPECIAL to "Özel",
                CellColumnType.NOTES to "Notlar",
            )
        assertEquals(
            CellColumnType.entries.toSet(),
            expected.keys,
            "a column was added without being given a name the user can read",
        )
        expected.forEach { (columnType, word) ->
            assertEquals(word, valueOf(columnNameOf(columnType).key), "the name of $columnType")
        }
    }

    @Test
    fun `no column is ever shown under its own constant`() {
        CellColumnType.entries.forEach { columnType ->
            val shown = valueOf(columnNameOf(columnType).key)
            assertTrue(
                columnType.name !in shown,
                "$columnType reaches the screen as the constant '${columnType.name}', not as a word",
            )
        }
    }

    @Test
    fun `a column borrows the name of the pool it feeds`() {
        // PLAN 5.4: the four production columns share their names with the pools,
        // so a user reading `Mukavva` in the table and `Mukavva` in the pool is
        // reading about the same thing.
        PoolType.entries.forEach { poolType ->
            assertEquals(
                poolNameOf(poolType).key,
                columnNameOf(CellColumnType.of(poolType)).key,
                "the $poolType column and pool are named apart",
            )
        }
    }

    @Test
    fun `the notes column is named on its own, since no pool stands behind it`() {
        val notes = columnNameOf(CellColumnType.NOTES).key
        assertTrue(
            PoolType.entries.none { poolNameOf(it).key == notes },
            "the notes column borrowed a pool's name",
        )
    }

    /**
     * An argument handed to `stringResource` as a bare `.columnType`, `.poolType`
     * or `.trackingMode` — which is the shape that prints the constant. Reaching
     * one through `columnNameOf` or `labelOf` first is the correct form and does
     * not match, because what follows the comma is then a call and not a path.
     */
    private val printedAsAConstant =
        Regex(""",\s*[A-Za-z_][A-Za-z0-9_.]*\.(columnType|poolType|trackingMode)\s*[,)]""")

    @Test
    fun `the guard below would notice the mistake it guards against`() {
        // The check is a regex over source text, so it can rot into something
        // that passes by never matching anything. This is the line as it was
        // actually written when the constant reached the screen.
        assertTrue(
            printedAsAConstant.containsMatchIn("""stringResource(Strings.Tasks.rowItem, task.columnType),"""),
        )
        assertTrue(
            printedAsAConstant.containsMatchIn("""Text(stringResource(Strings.Aim.itemLabel, c.gameName, c.columnType))"""),
        )
        // And the form that replaced it, which must not be flagged.
        assertTrue(
            !printedAsAConstant.containsMatchIn(
                """stringResource(Strings.Tasks.rowColumn, stringResource(columnNameOf(task.columnType)))""",
            ),
        )
    }

    @Test
    fun `no screen prints a column or a pool into a sentence`() {
        // The defect this guards against compiles and looks right: stringResource
        // takes Any, so handing it a CellColumnType is accepted and prints the
        // constant. Nothing in the type system objects, so this does.
        val offenders =
            uiSources()
                .flatMap { file ->
                    Files.readAllLines(file).mapIndexed { index, line -> file to (index + 1 to line) }
                }.filter { (_, numbered) ->
                    val line = numbered.second
                    line.contains("stringResource(") && printedAsAConstant.containsMatchIn(line)
                }.map { (file, numbered) -> "${file.fileName}:${numbered.first}" }
        assertEquals(emptyList(), offenders, "a column, pool or mode is being printed as its constant")
    }

    private fun uiSources(): List<Path> {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .map { it.resolve("src/commonMain/kotlin/dev/pnptracker/ui") }
                .firstOrNull { Files.isDirectory(it) }
                ?.let { directory ->
                    Files.walk(directory).use { stream ->
                        return stream.filter { it.toString().endsWith(".kt") }.toList()
                    }
                }
            candidate = candidate.parent
        }
        fail("Could not locate the ui sources from ${Path.of("").toAbsolutePath()}")
    }
}
