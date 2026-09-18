package dev.pnptracker.packaging

import dev.pnptracker.AppInfo
import dev.pnptracker.data.database.DatabaseFactory
import dev.pnptracker.data.database.TemporaryBackupProbe
import dev.pnptracker.data.database.entity.GameEntity
import dev.pnptracker.data.repository.BackupStore
import dev.pnptracker.domain.backup.DatabaseBackupExporter
import dev.pnptracker.domain.backup.restore.BackupReadResult
import dev.pnptracker.domain.backup.restore.UntrustedBackupReader
import dev.pnptracker.domain.csv.CsvDelimiter
import dev.pnptracker.domain.csv.csvDocument
import dev.pnptracker.domain.csv.parseCsvRecords
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.platform.backupfiles.PathBackupInput
import dev.pnptracker.platform.csv.CsvFileReader
import dev.pnptracker.platform.diagnostics.QueuedDiagnostics
import dev.pnptracker.platform.files.AtomicFileWriter
import dev.pnptracker.platform.xlsx.XlsxWorkbookReader
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Clock

/** Turkish letters every text path must carry through untouched. */
private const val TURKISH = "Görev İşçi ğüşöç ÇĞİÖŞÜ"

/** What csvDocument writes first, so a spreadsheet program reads the file as UTF-8. */
private const val BYTE_ORDER_MARK = "\uFEFF"

/**
 * Runs the application's own features on the runtime a package brought, before
 * its window opens (Faz 3 / İş 11).
 *
 * Loaded by the package's real launcher as a Java agent (`JAVA_TOOL_OPTIONS
 * -javaagent:…`), so it runs in the very JVM the launcher started: the embedded
 * runtime, the packaged jars, the packaged native libraries. It proves the
 * runtime was cut down without cutting away something the application needs —
 * XLSX, CSV, the JSON backup through Room and the bundled SQLite, the diagnostic
 * log, AWT fonts and Skia text — and that no other Java took part. Then it ends
 * the process; the application's main never runs.
 *
 * Arguments: `<application directory>|<work directory>`; the work directory
 * holds `sample-import.xlsx` and receives everything this writes.
 */
object PackageRuntimeProbe {
    @JvmStatic
    fun premain(arguments: String?) {
        val code =
            try {
                val (application, work) = requireNotNull(arguments).split('|').map { Path.of(it).toRealPath() }
                run(application, work)
                println("PROBE: OK")
                0
            } catch (failure: Throwable) {
                println("PROBE: FAILED ${failure::class.java.name}: ${failure.message}")
                failure.printStackTrace(System.out)
                1
            }
        System.out.flush()
        exitProcess(code)
    }

    private fun step(
        name: String,
        check: () -> String,
    ) {
        println("PROBE: $name — ${check()}")
    }

    private fun run(
        application: Path,
        work: Path,
    ) {
        step("runtime") {
            val home = Path.of(System.getProperty("java.home")).toRealPath()
            check(home == application.resolve("lib/runtime").toRealPath()) { "java.home is $home" }
            val jvms =
                Files
                    .readAllLines(Path.of("/proc/self/maps"))
                    .mapNotNull { line ->
                        line
                            .substringAfter(' ', "")
                            .trim()
                            .split(Regex("\\s+"))
                            .lastOrNull()
                    }.filter { it.endsWith("libjvm.so") }
                    .toSet()
            check(jvms.isNotEmpty() && jvms.all { Path.of(it).startsWith(application) }) { "libjvm.so mapped from $jvms" }
            val modules =
                ModuleLayer
                    .boot()
                    .modules()
                    .map { it.name }
                    .sorted()
            "java.home and libjvm.so inside the package; ${modules.size} modules: ${modules.joinToString(" ")}"
        }

        step("xlsx") {
            val workbook = XlsxWorkbookReader().read(work.resolve("sample-import.xlsx"))
            val cells = workbook.sheets.sumOf { it.cells.size }
            check(cells > 0) { "the workbook read as empty" }
            "${workbook.sheets.size} sheet(s), $cells cell(s)"
        }

        step("csv") {
            val records = listOf(listOf("Oyun", "Görev"), listOf("Harmonies", TURKISH))
            val file = work.resolve("probe.csv")
            AtomicFileWriter(temporarySuffix = ".part").write(file, csvDocument(records).toByteArray(Charsets.UTF_8))
            val read = parseCsvRecords(CsvFileReader().readText(file).removePrefix(BYTE_ORDER_MARK), CsvDelimiter.COMMA).map { it.fields }
            check(read == records) { "read back $read" }
            "written and read back, UTF-8, ${records.size} records"
        }

        step("database and backup") {
            val database = DatabaseFactory().open(work.resolve("probe.db"))
            try {
                val now = Clock.System.now()
                runBlocking {
                    database.gameDao().insert(GameEntity(id = IdGenerator.Random.newId(), name = TURKISH, createdAt = now, updatedAt = now))
                }
                val document = runBlocking { DatabaseBackupExporter(BackupStore(database), AppInfo.Current, Clock.System).backupDocument() }
                val file = work.resolve("probe-backup.json")
                AtomicFileWriter(temporarySuffix = ".part").write(file, document.json.encodeToByteArray())
                val read = runBlocking { UntrustedBackupReader(TemporaryBackupProbe()).read(PathBackupInput(file)) }
                check(read is BackupReadResult.Valid) { "the backup was refused: $read" }
                check(
                    read.backup.data.games
                        .single()
                        .name == TURKISH,
                ) { "the game did not come back" }
                "Room + bundled SQLite, JSON backup written, read and loaded into a throwaway database"
            } finally {
                database.close()
            }
        }

        step("diagnostics") {
            val logs = work.resolve("logs")
            QueuedDiagnostics.inDirectory(logs, AppInfo.Current).use { diagnostics ->
                diagnostics.record(
                    DiagnosticRecord(
                        DiagnosticEvent.UNEXPECTED_FAILURE,
                        area = DiagnosticArea.APPLICATION,
                        failure = IllegalStateException(),
                    ),
                )
            }
            val lines = Files.list(logs).use { files -> files.toList() }.flatMap { Files.readAllLines(it) }
            check(lines.size == 1 && "unexpected" in lines.single()) { "the log holds $lines" }
            "one line written under its own lock"
        }

        step("awt fonts") {
            val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
            check(families.isNotEmpty()) { "no font family" }
            val image = BufferedImage(400, 40, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                drawString(TURKISH, 4, 28)
                dispose()
            }
            val lit = (0 until image.width).sumOf { x -> (0 until image.height).count { y -> image.getRGB(x, y) and 0xFFFFFF != 0 } }
            check(lit > 0) { "nothing was drawn" }
            "${families.size} font families, Turkish text rasterised"
        }

        step("skia") {
            // Compose asks Skia's font manager for its fonts; so does this.
            val fonts = FontMgr.default
            check(fonts.familiesCount > 0) { "Skia found no font family" }
            val typeface = requireNotNull(fonts.matchFamilyStyle("sans-serif", FontStyle.NORMAL)) { "Skia found no sans-serif face" }
            val surface = Surface.makeRasterN32Premul(400, 40)
            surface.canvas.clear(-1)
            surface.canvas.drawString(TURKISH, 4f, 28f, Font(typeface, 20f), Paint())
            val bitmap = Bitmap()
            bitmap.allocN32Pixels(400, 40)
            check(surface.readPixels(bitmap, 0, 0)) { "no pixels" }
            val dark = (0 until 400).sumOf { x -> (0 until 40).count { y -> bitmap.getColor(x, y) and 0xFF < 0x80 } }
            check(dark > 0) { "Skia drew nothing" }
            "native Skia loaded from the package, ${fonts.familiesCount} families, Turkish text drawn"
        }
    }
}
