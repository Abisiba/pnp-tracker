package dev.pnptracker.packaging

import dev.pnptracker.APPLICATION_VERSION
import dev.pnptracker.domain.export.TASK_EXPORT_HEADER
import dev.pnptracker.domain.importprep.CsvImportColumns
import dev.pnptracker.domain.importprep.readCsvWorkbook
import dev.pnptracker.domain.importprep.sourceColumnTypeOf
import dev.pnptracker.domain.model.SourceColumnType
import dev.pnptracker.platform.csv.CsvFileReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tasks the Kotlin, Compose and ktlint plugins bring; every other name must be in the build script. */
internal val PLUGIN_TASKS = listOf("run", "check", "clean", "desktopTest", "ktlintCheck", "ktlintFormat")

/**
 * The guides (Faz 3 / İş 14), held to the application they describe.
 *
 * Documentation goes stale quietly, so what can be checked is checked: the
 * example import file is read by the application's own CSV reader, the column
 * names and headings in the text are the ones the code uses, every link points
 * at a file that exists, and nothing carries a path or a name of the machine
 * this was written on. The version is never written down: the guides say
 * `<version>` and the one version lives in the build.
 */
class DocumentationTest {
    private fun repository(): Path {
        var directory = Path.of("").toAbsolutePath()
        while (!Files.isDirectory(directory.resolve(".git"))) {
            directory = requireNotNull(directory.parent) { "the repository root is not above the working directory" }
        }
        return directory
    }

    private val guide: Path get() = repository().resolve("docs/kullanim-kilavuzu.md")
    private val importGuide: Path get() = repository().resolve("docs/ornek-ice-aktarma.md")
    private val exampleCsv: Path get() = repository().resolve("docs/ornek-ice-aktarma.csv")

    private val documents: List<Path>
        get() =
            listOf(
                guide,
                importGuide,
                repository().resolve("README.md"),
                repository().resolve("CONTRIBUTING.md"),
                repository().resolve("packaging/verify/README.md"),
            )

    @Test
    fun `every guide is there and says what it is`() {
        documents.forEach { document ->
            assertTrue(Files.isRegularFile(document), "$document yok")
            assertTrue(Files.readString(document).startsWith("# "), "${document.fileName} bir başlıkla başlamıyor")
        }
        assertTrue(Files.readString(guide).length > 4_000, "kullanım kılavuzu fazla kısa")
    }

    @Test
    fun `the user guide covers every section the work asked for`() {
        val text = Files.readString(guide)
        listOf(
            "What is PnP Tracker?",
            "First start and where the data lives",
            "Creating a game",
            "Cells and tasks",
            "Importing XLSX and CSV",
            "Reviewing, editing, confirming and removing drafts",
            "Taking back a confirmed import",
            "Task progress, finishing and turning a task back into text",
            "Search and pool filters",
            "Exporting to CSV",
            "Making a backup by hand",
            "Restoring a backup, and the safety backup",
            "How many automatic backups are kept",
            "After an unexpected shutdown",
            "Veri dosyanızda bir hasar bulundu",
            "Diagnostic logs",
            "Keyboard and accessibility",
            "Running the Linux portable archive",
            "Installing, updating and removing the Garuda/Arch package",
            "Why your data stays when the package is removed",
            "Known limits",
            "Installing on Windows",
        ).forEach { heading ->
            assertTrue(Regex("^#+ .*${Regex.escape(heading)}", RegexOption.MULTILINE).containsMatchIn(text), "bölüm eksik: $heading")
        }
    }

    @Test
    fun `the guides name the screens and files the application really has`() {
        val strings = Files.readString(repository().resolve("app/src/commonMain/composeResources/values/strings.xml"))
        val text = Files.readString(guide) + Files.readString(importGuide)
        // Every screen and button the guides put in bold must exist as a string.
        listOf(
            "PnP Üretim Takipçisi",
            "Yeni oyun oluştur",
            "Oyunu kaydet",
            "Excel veya CSV dosyası seç",
            "Taslak olarak kaydet",
            "Devam eden içe aktarmalar",
            "Onaylanmış içe aktarmalar",
            "Geri al",
            "Görevi metne dönüştür",
            "Eksik/hatalı bildir",
            "Görevleri CSV’ye aktar",
            "Yedek oluştur",
            "Yedekten geri yükle",
            "Otomatik yedeklerin saklanması",
            "Renk seçilecek",
            "Tek öge çok renk",
            "PNP açılamadı",
        ).forEach { label ->
            assertTrue(">$label<" in strings || ">$label" in strings, "arayüzde olmayan bir ad: $label")
            val inGuides = label.replace('’', '\'')
            assertTrue(inGuides in text.replace('’', '\''), "kılavuzlarda geçmiyor: $label")
        }
        // The application's own places, spelled as the code spells them.
        listOf("pnp-tracker", "pnp-yedek-", "pnp-oncesi-", "/opt/pnp-tracker", "/usr/bin/pnp-tracker").forEach { path ->
            assertTrue(path in text, "kılavuzlarda geçmiyor: $path")
        }
    }

    @Test
    fun `the export header in the guide is the header the application writes`() {
        val text = Files.readString(guide)
        assertTrue(TASK_EXPORT_HEADER.joinToString(", ") in text, "dışa aktarma sütunları kılavuzda yanlış")
    }

    @Test
    fun `the import guide names the three required columns and every source type`() {
        val text = Files.readString(importGuide)
        CsvImportColumns.required.forEach { column ->
            assertTrue("`$column`" in text, "zorunlu sütun anlatılmamış: $column")
        }
        SourceColumnType.entries.forEach { type ->
            assertTrue("`${type.name}`" in text, "source_type değeri anlatılmamış: ${type.name}")
        }
        // The Turkish headings the guide offers as alternatives really answer.
        listOf("3D Print", "Laminasyon", "Mukavva", "Özel", "Eksik", "Ödünç Parçalar").forEach { heading ->
            assertTrue(heading in text, "Excel başlığı anlatılmamış: $heading")
            assertNotNull(sourceColumnTypeOf(heading), "$heading başlığını uygulama tanımıyor")
        }
    }

    @Test
    fun `the example file really imports, through the application's own reader`() {
        val reading = readCsvWorkbook(exampleCsv.fileName.toString(), CsvFileReader().readText(exampleCsv))

        val sheet = reading.workbook.sheets.single()
        // The heading row the reader writes itself, then one row per record.
        val rows = sheet.cells.map { it.rowIndex }.distinct()
        assertEquals(12, rows.size, "örnek dosyadan beklenen satır sayısı çıkmadı")
        val games =
            sheet.cells
                .filter { it.columnIndex == 0 && it.rowIndex > 0 }
                .map { it.rawText }
                .distinct()
        assertEquals(listOf("Harmonies", "Ark Nova"), games)
        val texts = sheet.cells.joinToString("\n") { it.rawText }
        assertTrue("15 KIRMIZI**, 19 YEŞİL" in texts, "Türkçe harfler ve ** işareti korunmadı")
        assertTrue("=1+1 yazan kart, düz metin olarak kalır" in texts, "formül gibi görünen hücre düz metin kalmadı")

        // What the guide shows as the example is the file itself.
        val shown =
            Files
                .readString(importGuide)
                .substringAfter("```csv")
                .substringBefore("```")
                .trim()
        val fileText =
            CsvFileReader()
                .readText(exampleCsv)
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .trim()
        assertEquals(fileText, shown, "belgedeki örnek ile dosya ayrı düşmüş")
    }

    @Test
    fun `the files these tests read byte for byte are stored with one line ending`() {
        // The test above compares a block of a guide with the example file, and two
        // others hold LICENSE and a test's own source to their exact bytes. All of
        // that turns on how the repository is checked out, which is decided in one
        // file — without it, a Windows checkout would convert the guides and the
        // three would fail for a reason that is not in the application at all.
        val attributes = Files.readString(repository().resolve(".gitattributes"))
        assertTrue("* text=auto eol=lf" in attributes, "metin dosyaları için LF kuralı yok")
        // And the two files that are deliberately not LF stay out of it: a CSV is
        // CRLF by RFC 4180, and Windows' command interpreter wants CRLF.
        assertTrue("*.csv -text" in attributes, "CSV dönüştürmenin dışında tutulmamış")
        assertTrue("*.bat -text" in attributes, "toplu iş dosyası dönüştürmenin dışında tutulmamış")
    }

    @Test
    fun `the guides carry no version number, no absolute home path and no name of this machine`() {
        documents.forEach { document ->
            val text = Files.readString(document)
            assertFalse(Regex("(?<!\\$\\{)/home/[a-z]").containsMatchIn(text), "${document.fileName} sabit bir /home yolu taşıyor")
            assertFalse(System.getProperty("user.name") in text, "${document.fileName} kullanıcı adı taşıyor")
            if (document != repository().resolve("README.md")) {
                assertFalse(
                    Regex("\\b${Regex.escape(APPLICATION_VERSION)}\\b").containsMatchIn(text),
                    "${document.fileName} sürüm numarasını sabitlemiş; <sürüm> yazılmalı",
                )
            }
        }
        assertTrue("<version>" in Files.readString(guide), "kılavuz sürümü değişken olarak anmıyor")
    }

    @Test
    fun `every link in the guides points at a file that is there`() {
        documents.forEach { document ->
            Regex("\\[[^\\]]+\\]\\(([^)]+)\\)").findAll(Files.readString(document)).forEach { match ->
                val target = match.groupValues[1].substringBefore('#')
                if (target.isNotEmpty() && !target.startsWith("http")) {
                    val resolved = document.parent.resolve(target).normalize()
                    assertTrue(Files.exists(resolved), "${document.fileName} içindeki bağlantı boşa çıkıyor: $target")
                }
            }
        }
    }

    @Test
    fun `the commands in the guides are commands this repository really has`() {
        val text =
            Files.readString(guide) + Files.readString(repository().resolve("README.md")) +
                Files.readString(repository().resolve("CONTRIBUTING.md")) +
                Files.readString(repository().resolve("packaging/verify/README.md"))
        val build = Files.readString(repository().resolve("app/build.gradle.kts"))
        Regex("\\./gradlew (:app:)?([A-Za-z]+)").findAll(text).map { it.groupValues[2] }.distinct().forEach { task ->
            val known = task in PLUGIN_TASKS || "\"$task\"" in build || "val $task by" in build
            assertTrue(known, "kılavuzdaki Gradle görevi yok: $task")
        }
        listOf("packaging/verify/preflight.sh", "packaging/verify/verify-clean-install.sh").forEach { script ->
            assertTrue(script in text, "doğrulama komutu anlatılmamış: $script")
            assertTrue(Files.isExecutable(repository().resolve(script)), "$script çalıştırılabilir değil")
        }
        // Nothing in a guide may hand somebody a deletion they cannot check first.
        listOf(Files.readString(guide), Files.readString(repository().resolve("packaging/verify/README.md"))).forEach { document ->
            Regex("^\\s*(sudo )?rm\\b.*$", RegexOption.MULTILINE).findAll(document).forEach { match ->
                throw AssertionError("kılavuzda silme komutu var: ${match.value.trim()}")
            }
        }
    }
}
