package dev.pnptracker.platform.settings

import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.files.AtomicWriteException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * TEMPORARY PROBE — measures why the game order write did not arrive on Windows CI.
 * Never fails; prints `PROBE` lines. Removed in the next commit.
 */
class GameOrderWriteProbeTest {
    private lateinit var folder: Path

    @BeforeTest
    fun createFolder() {
        folder = Files.createTempDirectory("pnp-tracker-order-probe")
    }

    @AfterTest
    fun deleteFolder() {
        Files.walk(folder).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
    }

    private fun measure(
        label: String,
        readerPauseMillis: Long?,
    ) {
        val file = folder.resolve("$label.json")
        val writer = AtomicFileWriter(temporarySuffix = ".json.part")
        writer.write(file, "start")
        val stop = AtomicBoolean(false)
        val reads = AtomicInteger(0)
        val readFailures = mutableMapOf<String, Int>()
        val reader =
            readerPauseMillis?.let { pause ->
                thread(name = "probe-reader") {
                    while (!stop.get()) {
                        try {
                            Files.readAllBytes(file)
                            reads.incrementAndGet()
                        } catch (failure: Exception) {
                            synchronized(readFailures) {
                                val key = failure::class.qualifiedName.toString()
                                readFailures[key] = (readFailures[key] ?: 0) + 1
                            }
                        }
                        if (pause > 0) Thread.sleep(pause)
                    }
                }
            }
        val causes = mutableMapOf<String, Int>()
        var lost = 0
        val attempts = 300
        repeat(attempts) { index ->
            val content = "write $index"
            try {
                writer.write(file, content)
            } catch (refused: AtomicWriteException) {
                val cause = refused.cause
                val key = "${refused.failure} / ${cause?.let { it::class.qualifiedName }}: ${cause?.message}"
                causes[key] = (causes[key] ?: 0) + 1
            }
            if (runCatching { Files.readString(file) }.getOrNull() != content) lost++
        }
        stop.set(true)
        reader?.join()
        System.out.println(
            "PROBE [$label] os=${System.getProperty("os.name")} attempts=$attempts refused=${causes.values.sum()} " +
                "notOnDiskAfterWrite=$lost readerReads=${reads.get()} readerFailures=$readFailures",
        )
        causes.forEach { (cause, count) -> System.out.println("PROBE [$label] cause x$count: $cause") }
    }

    @Test
    fun `probe the order write with and without a reader polling the file`() {
        measure("no-reader", readerPauseMillis = null)
        measure("reader-every-10ms", readerPauseMillis = 10)
        measure("reader-tight", readerPauseMillis = 0)
    }
}
