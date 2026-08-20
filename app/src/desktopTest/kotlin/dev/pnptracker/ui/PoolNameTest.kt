package dev.pnptracker.ui

import dev.pnptracker.domain.model.PoolType
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import dev.pnptracker.ui.feature.games.labelOf as gameTaskLabelOf
import dev.pnptracker.ui.feature.importworkspace.labelOf as importLabelOf

/**
 * The pool names the user reads, and the rule that there is only one set of them.
 *
 * Resolved through the real resource loader, so a name that is missing from
 * `strings.xml` fails here as loudly as a name that is wrong.
 */
class PoolNameTest {
    /** What PLAN 3.4 calls the four pools. */
    private val canonicalNames =
        mapOf(
            PoolType.THREE_D to "3D Baskı",
            PoolType.CARD to "Kart",
            PoolType.BOARD to "Mukavva",
            PoolType.SPECIAL to "Özel",
        )

    private fun textOf(poolType: PoolType): String = runBlocking { getString(poolNameOf(poolType)) }

    @Test
    fun `each pool reads as the name the plan gives it`() {
        canonicalNames.forEach { (poolType, expected) ->
            assertEquals(expected, textOf(poolType), "$poolType is shown under the wrong name")
        }
    }

    @Test
    fun `every pool is named, so none can fall through to a general wording`() {
        // The mapping is a `when` without a fallback, so this failing means a pool
        // was added and pointed at a name that already belonged to another one.
        val resources = PoolType.entries.map { poolNameOf(it) }

        assertEquals(PoolType.entries.size, resources.toSet().size, "two pools share one name: $resources")
        assertEquals(
            PoolType.entries.size,
            PoolType.entries
                .map { textOf(it) }
                .toSet()
                .size,
            "two pools read the same to the user",
        )
        PoolType.entries.forEach { assertTrue(textOf(it).isNotBlank(), "$it has no name at all") }
    }

    @Test
    fun `the board pool is called Mukavva in the game task section`() {
        assertEquals("Mukavva", runBlocking { getString(gameTaskLabelOf(PoolType.BOARD)) })
    }

    @Test
    fun `the board pool is called Mukavva in the import review section`() {
        assertEquals("Mukavva", runBlocking { getString(importLabelOf(PoolType.BOARD)) })
    }

    @Test
    fun `the game task section and the import review section agree on every pool`() {
        PoolType.entries.forEach { poolType ->
            assertEquals(
                runBlocking { getString(gameTaskLabelOf(poolType)) },
                runBlocking { getString(importLabelOf(poolType)) },
                "$poolType reads differently depending on which screen the user is on",
            )
            assertEquals(poolNameOf(poolType), gameTaskLabelOf(poolType), "$poolType: the task section named it itself")
            assertEquals(poolNameOf(poolType), importLabelOf(poolType), "$poolType: the import section named it itself")
        }
    }

    @Test
    fun `the pools are shown in the order the plan lists them`() {
        assertEquals(
            listOf("3D Baskı", "Kart", "Mukavva", "Özel"),
            PoolType.entries.map { textOf(it) },
            "the order the sections are drawn in is no longer the order PLAN 3.4 lists the pools",
        )
    }

    @Test
    fun `no production source still calls the board pool Tahta`() {
        val offenders =
            productionUserFacingFiles().flatMap { file ->
                Files.readAllLines(file).mapIndexedNotNull { index, line ->
                    "$file:${index + 1}".takeIf { line.contains("Tahta") }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "the board pool is named Tahta in production code:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `the guard actually sees the production sources`() {
        val files = productionUserFacingFiles()

        assertTrue(files.isNotEmpty(), "no production sources were scanned")
        assertTrue(
            files.any { it.fileName.toString() == "PoolNames.kt" },
            "expected PoolNames.kt among the scanned files, found: ${files.map { it.fileName }}",
        )
        assertTrue(
            productionUserFacingFiles().any { it.fileName.toString() == "strings.xml" },
            "the text catalogue was not scanned",
        )
    }

    /** Everything shipped that can put words in front of the user: code and the text catalogue. */
    private fun productionUserFacingFiles(): List<Path> =
        productionSourceFiles() + moduleRoot().resolve(TEXT_CATALOGUE).takeIf { Files.isRegularFile(it) }.let(::listOfNotNull)

    private fun productionSourceFiles(): List<Path> =
        listOf("src/commonMain/kotlin", "src/desktopMain/kotlin")
            .map { moduleRoot().resolve(it) }
            .filter { Files.isDirectory(it) }
            .flatMap { root -> Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() } }

    /** Finds the module directory that holds `src/commonMain/kotlin`, regardless of the working directory. */
    private fun moduleRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve(MODULE_NAME))
                .firstOrNull { Files.isDirectory(it.resolve("src/commonMain/kotlin")) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("Could not locate the '$MODULE_NAME' module from ${Path.of("").toAbsolutePath()}")
    }

    private companion object {
        const val MODULE_NAME = "app"
        const val TEXT_CATALOGUE = "src/commonMain/composeResources/values/strings.xml"
    }
}
