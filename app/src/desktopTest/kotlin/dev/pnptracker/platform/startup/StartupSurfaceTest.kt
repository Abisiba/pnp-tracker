package dev.pnptracker.platform.startup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Things about the startup path that are true of the source rather than of a run.
 *
 * Two of them, and both are rules a future change could break without a single
 * test going red anywhere else.
 */
class StartupSurfaceTest {
    @Test
    fun `nowhere in the application is there a way past a missing migration`() {
        // `Migration7To8Test` already says this of `DatabaseFactory`, which is
        // the one builder. PLAN 14.4.10 made a second database — the migration
        // working copy — so the claim is worth making of the whole of
        // production: throwing somebody's data away rather than failing to
        // migrate it is never the answer, on either database (PLAN 26 note).
        val offending =
            productionSources().filter { "fallbackToDestructiveMigration" in Files.readString(it) }

        assertEquals(emptyList(), offending.map { it.fileName.toString() })
    }

    @Test
    fun `the gate is the only thing in production that opens the application's own database`() {
        // The structural half of PLAN 14.4.10 step 13. `DatabaseFactory.open` is
        // called in three places and each is accounted for: the gate, which is
        // the guarded route; the snapshot set writer, which opens a working
        // *copy* in a temporary directory; and the throwaway probe a backup is
        // tried out in. A fourth would be a way to reach a migration without a
        // snapshot, and it would be silent.
        val callers =
            productionSources()
                .filter { "databases.open(" in Files.readString(it) || "DatabaseFactory().open(" in Files.readString(it) }
                .map { it.fileName.toString() }
                .sorted()

        assertEquals(
            listOf("MigrationSnapshotSetWriter.kt", "StartupGate.kt", "TemporaryBackupProbe.kt"),
            callers,
            "something else in production opens a database directly",
        )
        // And `Main` reaches it only through the gate.
        val main = Files.readString(moduleRoot().resolve("src/desktopMain/kotlin/dev/pnptracker/Main.kt"))
        assertTrue("StartupGate(" in main, "the application no longer starts through the gate")
        assertTrue("DatabaseFactory().open(" !in main, "the application opens the database around the gate")
    }

    private fun productionSources(): List<Path> =
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
