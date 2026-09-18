package dev.pnptracker.platform.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shape of the narrowing, written down beside the behaviour that proves it
 * (PLAN 14.7.6).
 *
 * `UnreadableReadingsTest` shows that a defect rises out of each of the three
 * readings today. This is the other half: nothing anywhere in the production
 * tree may catch everything again. A behaviour test only asks about the defects
 * somebody thought to throw; a broad `catch` put back next year would pass every
 * one of them and swallow the defect nobody wrote a test for.
 */
class ReadingRefusalSurfaceTest {
    /**
     * A `catch` that would take a defect with it.
     *
     * `Throwable` and `Exception` both cover `IllegalStateException`,
     * `IllegalArgumentException` and `NullPointerException`, which PLAN 14.4.5
     * will not have masked. `Flow.catch { }` is the same thing on a stream: its
     * parameter is a `Throwable` whatever the upstream is.
     */
    private val catchesEverything = Regex("""catch\s*\(\s*\w+\s*:\s*(Throwable|Exception)\s*\)""")

    /** The one place a stream's failures may be answered, and it narrows by type. */
    private val theNarrowSeam = "answeringStorageRefusal"

    @Test
    fun `nothing in the pool reading catches more than storage refusing`() {
        val pool = Files.readString(production("src/commonMain/kotlin/dev/pnptracker/ui/feature/pools/PoolController.kt"))

        assertTrue(theNarrowSeam in pool, "the pool no longer goes through the narrow seam")
        assertEquals(
            emptyList(),
            catchesEverything.findAll(pool).map { it.value }.toList(),
            "the pool caught everything again",
        )
        assertTrue(".catch {" !in pool && ".catch(" !in pool, "the pool answers a stream outside the narrow seam")
    }

    @Test
    fun `the three readings the slice typed all go through the narrow seam`() {
        listOf(
            "src/commonMain/kotlin/dev/pnptracker/ui/feature/games/GameTableController.kt",
            "src/commonMain/kotlin/dev/pnptracker/ui/feature/colors/ColorCatalogueController.kt",
            "src/commonMain/kotlin/dev/pnptracker/ui/feature/pools/PoolController.kt",
        ).forEach { relative ->
            val text = Files.readString(production(relative))
            assertTrue(theNarrowSeam in text, "$relative does not answer a refused reading at all")
            assertTrue(".catch {" !in text && ".catch(" !in text, "$relative answers a stream outside the narrow seam")
        }
    }

    @Test
    fun `the seam itself is the only place a stream's failure is sorted`() {
        val seam = Files.readString(production("src/commonMain/kotlin/dev/pnptracker/domain/diagnostics/ObservedReadings.kt"))

        // Narrowed by type and rethrown, rather than swallowed by kind.
        assertTrue("failure !is SQLiteException" in seam, "the seam stopped narrowing by type")
        assertTrue("throw failure" in seam, "the seam stopped throwing on what it does not answer")
    }

    private fun production(relative: String): Path = moduleRoot().resolve(relative)

    private fun moduleRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .firstOrNull { Files.isDirectory(it.resolve("src/commonMain/kotlin")) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("could not find the module root")
    }
}
