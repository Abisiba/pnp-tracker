package dev.pnptracker.domain.importhint

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps the hint detectors pure domain code.
 *
 * These rules have to run against a cell whatever produced it, so a single
 * import of the spreadsheet library, of Room or of the file system here would
 * tie the rules to the desktop and to a database that has not been written yet.
 * Reading the source tree is something only the desktop tests can do, which is
 * why this guard lives here rather than beside the code it protects.
 */
class ImportHintPurityTest {
    private val forbidden =
        listOf(
            "org.apache.poi",
            "androidx.room",
            "androidx.sqlite",
            "androidx.compose",
            "java.io",
            "java.nio",
            "java.time",
            "java.util",
            "kotlinx.coroutines",
        )

    @Test
    fun `the hint package imports nothing from a platform, a library or a database`() {
        val offenders = mutableListOf<String>()

        hintSourceFiles().forEach { file ->
            Files.readAllLines(file).forEachIndexed { index, line ->
                if (line.trimStart().startsWith("import")) {
                    forbidden
                        .filter { line.contains(it) }
                        .forEach { offenders += "${file.fileName}:${index + 1} imports $it" }
                }
            }
        }

        assertTrue(offenders.isEmpty(), "the hint rules reached outside the domain:\n${offenders.joinToString("\n")}")
    }

    @Test
    fun `the hint package holds the detectors it claims to`() {
        val names = hintSourceFiles().map { it.fileName.toString() }.toSet()

        listOf(
            "CompletionMarkerScan.kt",
            "GreenCellHintDetector.kt",
            "ReferenceColumnLayout.kt",
            "AlternativeColorExpressionDetector.kt",
            "ImportHintAnalyzer.kt",
        ).forEach { expected ->
            assertTrue(expected in names, "$expected was not scanned; the guard is looking in the wrong place")
        }
    }

    @Test
    fun `no detector creates a task, a colour relation or a decision of its own`() {
        val forbiddenTypes = listOf("TaskEntity", "TaskColorEntity", "GameEntity", "DraftTaskEntity", "EntityId")
        val offenders = mutableListOf<String>()

        hintSourceFiles().forEach { file ->
            val text = Files.readString(file)
            forbiddenTypes.filter { text.contains(it) }.forEach { offenders += "${file.fileName} mentions $it" }
            // ACCEPTED is the one decision the detectors must never be able to reach.
            if (text.contains("HintDecision.ACCEPTED")) offenders += "${file.fileName} accepts a hint by itself"
        }

        assertTrue(offenders.isEmpty(), "a detector reached into production data:\n${offenders.joinToString("\n")}")
    }

    private fun hintSourceFiles(): List<Path> {
        val root = moduleRoot().resolve("src/commonMain/kotlin/dev/pnptracker/domain/importhint")
        return Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
    }

    private fun moduleRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .firstOrNull { Files.isDirectory(it.resolve("src/commonMain/kotlin")) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("Could not locate the 'app' module from ${Path.of("").toAbsolutePath()}")
    }
}
