package dev.pnptracker.platform.xlsx

import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the two promises that surround the fixture: the user's own spreadsheet
 * never enters the repository, and reading spreadsheets never goes near the real
 * database.
 */
class XlsxFixtureSafetyTest {
    @Test
    fun `the only spreadsheet under version control is the anonymised fixture`() {
        val tracked = git("ls-files", "*.xlsx", "*.xls", "*.xlsm")

        // The fixture itself may or may not be in the index yet, depending on
        // whether this run happens before or after it was committed. What must
        // never be true is that some other spreadsheet is tracked.
        assertEquals(
            emptyList(),
            tracked - "app/src/desktopTest/resources/$FIXTURE_NAME",
            "a spreadsheet that is not the anonymised fixture is tracked by git",
        )
    }

    @Test
    fun `the fixture carries no author, no path and no real game names`() {
        // A workbook is a zip, so the parts have to be decompressed before any
        // search over their text means anything.
        val parts = mutableMapOf<String, String>()
        ZipFile(fixtureInRepository().toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                parts[entry.name] = zip.getInputStream(entry).bufferedReader().readText()
            }
        }

        val everything = parts.values.joinToString("\n")
        listOf("/home/", "C:\\", "Users/", "@gmail", "Kitap1").forEach { marker ->
            assertTrue(!everything.contains(marker), "the fixture contains '$marker'")
        }
        // Naming the expected author outright is stronger than listing names to
        // avoid, and keeps nobody's account name in the repository.
        assertTrue(
            parts.getValue("docProps/core.xml").contains("<dc:creator>Apache POI</dc:creator>"),
            "the fixture should be authored by the generator, not by a person",
        )
        assertTrue(parts.containsKey("xl/theme/theme1.xml"), "the fixture should carry a theme part")
        assertTrue(everything.contains("Ornek Tema"), "the fixture should carry its own synthetic theme")
        assertTrue(everything.contains("Örnek Oyun A"), "the fixture should hold the synthetic sample games")
    }

    @Test
    fun `reading spreadsheets never opens the real application database`() {
        val realDatabase = TemporaryDatabaseDirectory.realApplicationDatabaseFile()
        val existedBefore = Files.exists(realDatabase)
        val fingerprintBefore = if (existedBefore) sha256Of(realDatabase) else null

        val directory = Files.createTempDirectory("pnp-xlsx-safety")
        try {
            XlsxWorkbookReader().read(copyFixtureInto(directory))
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }

        assertEquals(existedBefore, Files.exists(realDatabase))
        assertEquals(fingerprintBefore, if (existedBefore) sha256Of(realDatabase) else null)
    }

    private fun fixtureInRepository(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            val resources = candidate.resolve("app/src/desktopTest/resources/$FIXTURE_NAME")
            if (Files.isRegularFile(resources)) return resources
            candidate = candidate.parent
        }
        fail("Could not find the committed fixture from ${Path.of("").toAbsolutePath()}")
    }

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(".git"))) return candidate
            candidate = candidate.parent
        }
        fail("Could not find the repository root from ${Path.of("").toAbsolutePath()}")
    }

    private fun git(vararg arguments: String): List<String> {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repositoryRoot().toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "git did not finish in time")
        assertEquals(0, process.exitValue(), "git ${arguments.joinToString(" ")} failed: $output")
        return output.lines().filter { it.isNotBlank() }.sorted()
    }
}
