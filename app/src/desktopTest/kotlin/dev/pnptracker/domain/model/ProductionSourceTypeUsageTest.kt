package dev.pnptracker.domain.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the decision that the domain uses the Kotlin identifier and time types.
 *
 * This check reads the source tree, which common test code cannot do, so it lives
 * in the desktop test source set even though the rule it protects is about
 * `commonMain`.
 *
 * There is exactly one allowance, named below. Reading a stored moment in the
 * user's own calendar is a thing no Kotlin standard library type can do, and
 * PLAN 14.1 lists no date library to add for it — so the platform's calendar is
 * asked, in one `actual` whose whole purpose is that crossing. It changes
 * nothing about what the rule protects: what is stored, passed and compared
 * stays [kotlin.time.Instant], and what comes back is a handful of integers that
 * are shown and then thrown away. A second file wanting the same allowance still
 * fails here, which is the point of naming it rather than widening the rule.
 */
class ProductionSourceTypeUsageTest {
    @Test
    fun `production sources use no jvm uuid or date time type`() {
        val forbiddenImports = listOf("java.util.UUID", "java.time")
        val offenders = mutableListOf<String>()

        productionSourceFiles().filterNot(::isTheCalendarCrossing).forEach { file ->
            val lines = Files.readAllLines(file)
            lines.forEachIndexed { index, line ->
                forbiddenImports
                    .filter { forbidden -> line.contains(forbidden) }
                    .forEach { forbidden -> offenders += "$file:${index + 1} uses $forbidden" }
            }
        }

        assertTrue(offenders.isEmpty(), "Forbidden JVM types in production code:\n${offenders.joinToString("\n")}")
    }

    @Test
    fun `the one allowance is a file that really exists and is the only one`() {
        // An allowance for a file nobody wrote would let the rule be widened by
        // deleting something, so the file is required to be there.
        val allowed = productionSourceFiles().filter(::isTheCalendarCrossing)

        assertTrue(allowed.size == 1, "expected exactly one calendar crossing, found: ${allowed.map { it.fileName }}")
        val text = Files.readString(allowed.single())
        assertTrue("actual fun localMomentOf" in text, "the allowed file is not the calendar crossing any more")
        assertTrue("Instant" in text, "the crossing no longer starts from a Kotlin instant")
    }

    /** The single `actual` that is allowed to ask the platform what day it is. */
    private fun isTheCalendarCrossing(file: Path): Boolean = file.fileName.toString() == CALENDAR_CROSSING

    @Test
    fun `the guard actually sees the production sources`() {
        val files = productionSourceFiles()

        assertTrue(files.isNotEmpty(), "no production sources were scanned")
        assertTrue(
            files.any { it.fileName.toString() == "EntityId.kt" },
            "expected EntityId.kt among the scanned files, found: ${files.map { it.fileName }}",
        )
    }

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
        const val CALENDAR_CROSSING = "DesktopLocalMoment.kt"
    }
}
