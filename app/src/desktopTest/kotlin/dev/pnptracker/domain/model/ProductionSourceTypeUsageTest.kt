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
 */
class ProductionSourceTypeUsageTest {
    @Test
    fun `production sources use no jvm uuid or date time type`() {
        val forbiddenImports = listOf("java.util.UUID", "java.time")
        val offenders = mutableListOf<String>()

        productionSourceFiles().forEach { file ->
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
    }
}
