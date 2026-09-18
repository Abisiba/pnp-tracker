package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryDatabaseDirectory
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** İş 9's library (PLAN 18). */
private const val LIBRARY = 1_203

/**
 * PLAN 14.7.4's measurement, made before the gate was given its check.
 *
 * Two things are measured and only one of them can fail:
 *
 * - what `quick_check`, `integrity_check` and `foreign_key_check` cost on İş 9's
 *   library and on ten times it, with the page count and size — printed as
 *   `PERF` lines for the record (master context §29), never compared with a
 *   threshold, because they depend on the machine;
 * - which of the four page-level damage classes each check finds. This is what
 *   the decision rests on: if `quick_check` missed one, the slice would stop and
 *   ask. So it is asserted, on any machine: every class found by `quick_check`.
 *   Whether `user_version` still reads is printed beside it, because a file
 *   whose version cannot be read is refused as DATABASE_NOT_READABLE before the
 *   check runs at all (PLAN 14.7.4) — a truncated file usually is.
 */
class DatabaseDamageMeasurementTest {
    private lateinit var root: Path
    private var realDatabaseExisted = false

    @BeforeTest
    fun createRoot() {
        realDatabaseExisted = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        root = Files.createTempDirectory("pnp-tracker-damage-measurement")
    }

    @AfterTest
    fun deleteRoot() {
        assertEquals(
            realDatabaseExisted,
            Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile()),
            "the measurement changed whether the real application database exists",
        )
        deleteTemporaryTree(root)
    }

    private fun aLibrary(
        name: String,
        tasks: Int,
    ): Path {
        val file = Files.createDirectories(root.resolve(name)).resolve("pnp.db")
        val database = DatabaseFactory().open(file)
        try {
            runBlocking { libraryOf(database, tasks) }
        } finally {
            database.close()
        }
        check(Files.notExists(Path.of("$file-wal"))) { "the library was left in a log" }
        return file
    }

    /** What one check says about [file]: `ok`, the rows it found, or the exception that stopped it. */
    private fun verdict(
        file: Path,
        pragma: String,
    ): String =
        try {
            val rows = pragmaAnswerOf(file, pragma)
            when {
                pragma == "foreign_key_check" && rows.isEmpty() -> "ok"
                pragma == "user_version" -> rows.single()
                rows == listOf("ok") -> "ok"
                else -> "found (${rows.size} row(s))"
            }
        } catch (stopped: SQLiteException) {
            "found (${stopped::class.simpleName})"
        }

    private fun millis(nanos: Long) = "%.1f ms".format(nanos / 1_000_000.0)

    @Test
    fun `what each check costs on the library and on ten times it`() {
        listOf(LIBRARY, LIBRARY * 10).forEach { tasks ->
            val file = aLibrary("cost-$tasks", tasks)
            val pageCount = pragmaAnswerOf(file, "page_count").single()
            val pageSize = pragmaAnswerOf(file, "page_size").single()
            val costs =
                listOf("quick_check", "integrity_check", "foreign_key_check").associateWith { pragma ->
                    // The first run warms the file cache, as a start after a
                    // recent one would find it; the second is what is recorded.
                    verdict(file, pragma)
                    val started = System.nanoTime()
                    val said = verdict(file, pragma)
                    val took = System.nanoTime() - started
                    assertEquals("ok", said, "$pragma on a healthy library at $tasks")
                    took
                }
            println(
                "PERF startup-check tasks=$tasks page_count=$pageCount page_size=$pageSize bytes=${Files.size(file)} " +
                    costs.entries.joinToString(" ") { (pragma, took) -> "$pragma=${millis(took)}" },
            )
        }
    }

    @Test
    fun `every page-level damage class is found by quick_check`() {
        val healthy = aLibrary("healthy", LIBRARY)
        val found =
            DatabaseDamage.entries.associateWith { damage ->
                val file = Files.createDirectories(root.resolve(damage.name)).resolve("pnp.db")
                Files.copy(healthy, file)
                damage.applyTo(file)
                listOf("user_version", "quick_check", "integrity_check", "foreign_key_check").associateWith { verdict(file, it) }
            }
        found.forEach { (damage, verdicts) ->
            println("DAMAGE $damage " + verdicts.entries.joinToString(" ") { (pragma, said) -> "$pragma=$said" })
        }

        found.forEach { (damage, verdicts) ->
            assertTrue(verdicts.getValue("quick_check").startsWith("found"), "quick_check missed $damage: $verdicts")
        }
    }
}
