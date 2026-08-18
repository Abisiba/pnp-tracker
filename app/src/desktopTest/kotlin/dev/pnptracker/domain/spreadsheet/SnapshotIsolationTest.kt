package dev.pnptracker.domain.spreadsheet

import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps the snapshot model free of the library that fills it in.
 *
 * The snapshot has to stay usable from `commonMain`, because the hint rules that
 * read it are pure domain logic. A single `XSSFColor` or `Path` on one of these
 * types would tie all of that to the desktop.
 */
class SnapshotIsolationTest {
    // Plain Java reflection, so that checking the model costs no extra dependency.
    private val snapshotTypes =
        listOf(
            WorkbookSnapshot::class.java,
            SheetSnapshot::class.java,
            CellSnapshot::class.java,
            RichTextRunSnapshot::class.java,
        )

    @Test
    fun `no snapshot property is a spreadsheet library type or a file type`() {
        val allowed =
            setOf(
                "java.lang.String",
                "java.lang.Integer",
                "java.lang.Boolean",
                "java.util.List",
                "int",
                "boolean",
                "dev.pnptracker.domain.spreadsheet.WorkbookSnapshot",
                "dev.pnptracker.domain.spreadsheet.SheetSnapshot",
                "dev.pnptracker.domain.spreadsheet.CellSnapshot",
                "dev.pnptracker.domain.spreadsheet.RichTextRunSnapshot",
                "dev.pnptracker.domain.spreadsheet.SheetVisibility",
                "dev.pnptracker.domain.spreadsheet.SpreadsheetCellKind",
            )
        val offenders =
            snapshotTypes.flatMap { type ->
                type.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                    .mapNotNull { field ->
                        val name = field.type.name
                        if (name in allowed) null else "${type.simpleName}.${field.name}: $name"
                    }
            }

        assertTrue(offenders.isEmpty(), "snapshot properties leak platform types:\n${offenders.joinToString("\n")}")
    }

    @Test
    fun `common sources import nothing from the spreadsheet library or the file system`() {
        val forbidden = listOf("org.apache.poi", "java.io.File", "java.nio.file", "javax.")
        val offenders = mutableListOf<String>()

        commonSourceFiles().forEach { file ->
            Files.readAllLines(file).forEachIndexed { index, line ->
                if (line.trimStart().startsWith("import")) {
                    forbidden
                        .filter { line.contains(it) }
                        .forEach { offenders += "${file.fileName}:${index + 1} imports $it" }
                }
            }
        }

        assertTrue(offenders.isEmpty(), "commonMain reaches for the platform:\n${offenders.joinToString("\n")}")
    }

    @Test
    fun `the guard actually sees the snapshot sources`() {
        val files = commonSourceFiles()

        assertTrue(files.any { it.fileName.toString() == "CellSnapshot.kt" }, "the snapshot sources were not scanned")
        assertEquals(4, snapshotTypes.size)
        assertTrue(
            snapshotTypes.all { it.declaredFields.any { field -> !Modifier.isStatic(field.modifiers) } },
            "a snapshot type exposed no fields to check",
        )
    }

    private fun commonSourceFiles(): List<Path> {
        val root = moduleRoot().resolve("src/commonMain/kotlin")
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
