package dev.pnptracker.packaging

import dev.pnptracker.APPLICATION_VERSION
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GitHub Actions configuration (Faz 3 / İş 16), as far as it can be held to
 * account without GitHub.
 *
 * Nothing here pretends to have run a workflow: what is checked is what the
 * files say. The two workflows are read with a small reader for the subset of
 * YAML they use, and then asked the questions that matter — are the actions
 * pinned to a commit nobody can move, can a pull request obtain write access or
 * publish, can a release happen without the tests, does the tag have to match
 * the one version the build knows, and does every command name a Gradle task
 * this repository really has.
 */
class WorkflowsTest {
    private fun repository(): Path {
        var directory = Path.of("").toAbsolutePath()
        while (!Files.isDirectory(directory.resolve(".git"))) {
            directory = requireNotNull(directory.parent) { "the repository root is not above the working directory" }
        }
        return directory
    }

    private val workflows: Path get() = repository().resolve(".github/workflows")

    private fun workflow(name: String): Map<String, Any> {
        val file = workflows.resolve(name)
        assertTrue(Files.isRegularFile(file), "$name yok")
        return Yaml.parse(Files.readString(file)).asMap()
    }

    private val ci get() = workflow("ci.yml")
    private val release get() = workflow("release.yml")

    private fun jobs(workflow: Map<String, Any>) = workflow.getValue("jobs").asMap().mapValues { it.value.asMap() }

    private fun steps(job: Map<String, Any>) = job["steps"].orEmpty().asList().map { it.asMap() }

    private fun everyStep(workflow: Map<String, Any>) = jobs(workflow).values.flatMap(::steps)

    private fun commandsOf(workflow: Map<String, Any>) = everyStep(workflow).mapNotNull { it["run"] as String? }

    // ------------------------------------------------------------------ shape

    @Test
    fun `both workflows parse and say what they are for`() {
        assertEquals("verify", ci["name"])
        assertEquals("release", release["name"])
        assertEquals(listOf("check"), jobs(ci).keys.toList())
        assertEquals(listOf("verify", "package", "publish"), jobs(release).keys.toList())
        assertTrue(everyStep(ci).all { "name" in it }, "adı olmayan adım var")
        assertTrue(everyStep(release).all { "name" in it }, "adı olmayan adım var")
    }

    @Test
    fun `the triggers are the ones the work asked for`() {
        val ciOn = ci.getValue("on").asMap()
        assertTrue("pull_request" in ciOn, "pull request'te koşmuyor")
        val pushedBranches = ciOn.getValue("push").asMap()
        assertEquals(listOf("main"), pushedBranches.getValue("branches").asList())
        assertTrue("workflow_dispatch" in ciOn, "elle çalıştırılamıyor")
        assertFalse("schedule" in ciOn, "beklenmeyen bir tetikleyici")

        val releaseOn = release.getValue("on").asMap()
        val pushedTags = releaseOn.getValue("push").asMap()
        assertEquals(listOf("v*"), pushedTags.getValue("tags").asList())
        assertFalse("pull_request" in releaseOn, "yayın akışı pull request'te koşuyor")
        assertTrue("workflow_dispatch" in releaseOn, "yayın yolu elle doğrulanamıyor")
    }

    // ------------------------------------------------------------- permissions

    @Test
    fun `only the publish job may write, and only releases`() {
        listOf(ci, release).forEach { workflow ->
            assertEquals(mapOf("contents" to "read"), workflow["permissions"], "varsayılan izin salt okunur değil")
        }
        jobs(ci).forEach { (name, job) ->
            assertEquals(mapOf("contents" to "read"), job["permissions"], "$name işi salt okunur değil")
        }
        jobs(release).forEach { (name, job) ->
            val permissions = job.getValue("permissions").asMap()
            if (name == "publish") {
                assertEquals(mapOf("contents" to "write"), permissions, "yayın işi gerekenden geniş izin istiyor")
            } else {
                assertEquals(mapOf("contents" to "read"), permissions, "$name işi yazma izni istiyor")
            }
        }
        val text = Files.readString(workflows.resolve("ci.yml"))
        listOf("write-all", "contents: write", "packages:", "id-token:", "gh release").forEach { forbidden ->
            assertFalse(forbidden in text, "doğrulama akışı $forbidden içeriyor")
        }
    }

    @Test
    fun `a pull request needs no secret`() {
        val text = Files.readString(workflows.resolve("ci.yml"))
        assertFalse("secrets." in text, "doğrulama akışı bir secret istiyor; fork'tan gelen PR koşamaz")
        assertFalse("GH_TOKEN" in text, "doğrulama akışı token kullanıyor")
        // The release workflow uses only the token GitHub gives the run itself.
        val releaseText = Files.readString(workflows.resolve("release.yml"))
        assertFalse(Regex("secrets\\.(?!GITHUB_TOKEN)").containsMatchIn(releaseText), "yayın akışı ayrı bir secret istiyor")
        assertTrue("github.token" in releaseText, "yayın akışı GitHub'ın kendi token'ını kullanmıyor")
    }

    // ----------------------------------------------------------------- actions

    @Test
    fun `every action is pinned to a commit that cannot move, with its version written beside it`() {
        val pin = Regex("uses: ([a-z0-9-]+)/([A-Za-z0-9-]+)(/[A-Za-z0-9-]+)?@([0-9a-f]{40}) # (v[0-9]+\\.[0-9]+\\.[0-9]+)$")
        var pinned = 0
        listOf("ci.yml", "release.yml").forEach { name ->
            Files.readAllLines(workflows.resolve(name)).map { it.trim() }.filter { it.startsWith("uses:") }.forEach { line ->
                val match = pin.matchEntire(line)
                assertTrue(match != null, "$name: sabit commit'e bağlanmamış eylem: $line")
                assertTrue(match.groupValues[1] in setOf("actions", "gradle"), "$name: resmî olmayan kaynak: $line")
                pinned++
            }
        }
        assertTrue(pinned >= 8, "beklenenden az eylem bulundu: $pinned")
        // The same action is pinned to the same commit everywhere.
        val used =
            listOf("ci.yml", "release.yml")
                .flatMap { Files.readAllLines(workflows.resolve(it)) }
                .map { it.trim() }
                .filter { it.startsWith("uses:") }
                .map { it.removePrefix("uses: ").substringBefore(" #") }
        used
            .groupBy({
                it
                    .substringBefore('@')
                    .split('/')
                    .take(2)
                    .joinToString("/")
            }, { it.substringAfter('@') })
            .forEach { (action, shas) ->
                assertEquals(1, shas.distinct().size, "$action iki ayrı commit'e bağlanmış: ${shas.distinct()}")
            }
    }

    @Test
    fun `every uploaded artifact says how long it is kept`() {
        (everyStep(ci) + everyStep(release))
            .filter { (it["uses"] as String?)?.contains("upload-artifact") == true }
            .also { assertTrue(it.isNotEmpty(), "hiç artifact yüklenmiyor") }
            .forEach { step ->
                val days = step.getValue("with").asMap()["retention-days"]
                assertTrue(days != null && days.toString().toInt() in 1..90, "saklama süresi belirtilmemiş: ${step["name"]}")
            }
        // Test reports are kept even when the run failed.
        val reports = everyStep(ci).single { (it["uses"] as String?)?.contains("upload-artifact") == true }
        assertEquals("\${{ always() }}", reports["if"], "test raporları yalnız başarıda yükleniyor")
    }

    // -------------------------------------------------------------- the commands

    @Test
    fun `every gradle command names a task this repository has`() {
        val build = Files.readString(repository().resolve("app/build.gradle.kts"))
        val named = Regex("\\./gradlew ((?::app:)?[A-Za-z]+(?: (?::app:)?[A-Za-z]+)*)")
        val tasks =
            (commandsOf(ci) + commandsOf(release))
                .flatMap { named.findAll(it).map { match -> match.groupValues[1] }.toList() }
                .flatMap { it.split(' ') }
                .map { it.removePrefix(":app:") }
                .distinct()
        assertTrue(tasks.isNotEmpty(), "hiçbir Gradle komutu bulunamadı")
        tasks.forEach { task ->
            val known = task in PLUGIN_TASKS || "\"$task\"" in build || "val $task by" in build
            assertTrue(known, "yapılandırmadaki Gradle görevi yok: $task")
        }
        assertTrue("checkReleaseTag" in tasks && "packageRelease" in tasks, "yayın akışı sürüm ve paket görevlerini çağırmıyor")
        assertTrue("verifyLinuxPackage" in tasks && "verifyArchPackage" in tasks, "paket doğrulama görevleri çağrılmıyor")
    }

    @Test
    fun `the verification runs the memory-limited command the project uses`() {
        val check = commandsOf(ci).single { "clean check" in it }
        listOf(
            "--rerun-tasks",
            "--no-daemon",
            "--no-parallel",
            "--max-workers=1",
            "-Pkotlin.compiler.execution.strategy=in-process",
            "-Xmx1536m",
            "-XX:MaxMetaspaceSize=512m",
        ).forEach { flag -> assertTrue(flag in check, "check komutunda $flag yok") }
        // The suite needs a display; it is given one rather than skipped.
        assertTrue("xvfb-run" in check, "pencere isteyen testler ekransız koşturuluyor")
        assertFalse(Regex("-x\\s|--exclude-task|-PskipTests").containsMatchIn(check), "check komutu test dışlıyor")
    }

    @Test
    fun `the tests run against temporary XDG folders and never a real one`() {
        val environment = jobs(ci).getValue("check").getValue("env").asMap()
        listOf("XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME").forEach { variable ->
            val value = environment[variable]?.toString() ?: error("$variable verilmemiş")
            assertTrue(value.startsWith("\${{ github.workspace }}/"), "$variable kalıcı bir yere bakıyor: $value")
        }
        // And the run proves afterwards that nothing reached the runner's home.
        val guard = commandsOf(ci).single { "\$HOME/.local/share/pnp-tracker" in it }
        val home = "\$HOME"
        listOf("$home/.local/share/pnp-tracker", "$home/.config/pnp-tracker", "$home/.local/state/pnp-tracker").forEach { place ->
            assertTrue("test ! -e \"$place\"" in guard, "$place denetlenmiyor")
        }
        assertTrue(commandsOf(ci).any { "git diff --exit-code -- app/schemas" in it }, "şema ve fixture denetimi yok")
        assertTrue(
            commandsOf(ci).any { Regex("git diff --exit-code\\s*$", RegexOption.MULTILINE).containsMatchIn(it) },
            "kaynak denetimi yok",
        )
    }

    // ------------------------------------------------------------- the release

    @Test
    fun `nothing is published before the tests and the packages`() {
        val releaseJobs = jobs(release)
        assertEquals("verify", releaseJobs.getValue("package")["needs"], "paketleme işi testleri beklemiyor")
        assertEquals("package", releaseJobs.getValue("publish")["needs"], "yayın işi paketleri beklemiyor")
        val condition = releaseJobs.getValue("publish").getValue("if").toString()
        assertTrue("github.event_name == 'push'" in condition, "elle çalıştırma da yayın yapabiliyor")
        assertTrue("refs/tags/v" in condition, "etiketsiz bir ref de yayın yapabiliyor")
        // The verification job stops on a tag that is not this version, before any package is made.
        val verifySteps = steps(releaseJobs.getValue("verify"))
        val tagStep = verifySteps.indexOfFirst { (it["run"] as String?)?.contains("checkReleaseTag") == true }
        val buildStep = verifySteps.indexOfFirst { (it["run"] as String?)?.contains("gradlew clean check") == true }
        assertTrue(tagStep in 0 until buildStep, "etiket denetimi derlemeden sonra")
        assertTrue("\${{ github.event_name == 'push' }}" == verifySteps[tagStep]["if"], "etiket denetimi her koşuda çalışmıyor")
        assertTrue("GITHUB_REF_NAME" in verifySteps[tagStep].getValue("run").toString(), "etiket adı görevden geçmiyor")
    }

    @Test
    fun `only v-plus-the-project-version may publish`() {
        val build = Files.readString(repository().resolve("app/build.gradle.kts"))
        assertTrue("""val expected = "v${'$'}{project.version}"""" in build, "beklenen etiket sürümden türetilmiyor")
        assertTrue("check(tag == expected)" in build, "etiket karşılaştırması birebir değil")
        // The rule the build applies, on examples: one tag passes, everything else fails.
        val expected = "v$APPLICATION_VERSION"
        assertTrue(expected.matches(Regex("v\\d+\\.\\d+\\.\\d+")), "sürüm beklenen biçimde değil: $APPLICATION_VERSION")
        assertTrue(expected == "v$APPLICATION_VERSION")
        listOf(
            APPLICATION_VERSION,
            "V$APPLICATION_VERSION",
            "$expected ",
            "${expected.dropLast(2)}",
            "v0.0.0",
            "v1.0.0",
            "$expected-rc1",
            "release-$expected",
            "",
        ).forEach { tag -> assertFalse(tag == expected, "yanlış etiket kabul edilirdi: $tag") }
    }

    @Test
    fun `the release carries both packages, one checksum file and the user documents`() {
        val build = Files.readString(repository().resolve("app/build.gradle.kts"))
        assertTrue("""destination.resolve("SHA256SUMS")""" in build, "SHA256SUMS üretilmiyor")
        listOf("LICENSE", "docs/kullanim-kilavuzu.md", "docs/ornek-ice-aktarma.md", "docs/ornek-ice-aktarma.csv").forEach { document ->
            assertTrue("""rootProject.file("$document")""" in build, "$document yayına girmiyor")
        }
        // The names the checksum file will carry are the package names.
        assertTrue("""archiveFileName.set("${'$'}linuxPackageName-linux-${'$'}linuxArch.tar.gz")""" in build, "arşiv adı değişmiş")
        assertTrue("""pnp-tracker-${'$'}{project.version}-1-${'$'}linuxArch.pkg.tar.zst""" in build, "Arch paketi adı değişmiş")
        // And the release notes read those two names out of the checksum file.
        val notes = commandsOf(release).single { "notes.md" in it && "gh release create" !in it }
        // The two names are read out of SHA256SUMS, so the notes can never name a file the release does not carry.
        assertTrue("""/\.tar\.gz${'$'}/""" in notes, "yayın notları arşivi özet dosyasından okumuyor")
        assertTrue("""/\.pkg\.tar\.zst${'$'}/""" in notes, "yayın notları Arch paketini özet dosyasından okumuyor")
        assertTrue("imzasız" in notes && "sha256sum -c SHA256SUMS" in notes, "yayın notları imzasızlığı ve doğrulamayı söylemiyor")
        assertTrue(commandsOf(release).count { "sha256sum -c SHA256SUMS" in it } >= 2, "özetler yayından önce iki kez doğrulanmıyor")
    }

    @Test
    fun `a second run for the same tag does not quietly overwrite the first`() {
        val guard = commandsOf(release).single { "gh release view" in it }
        assertTrue("exit 1" in guard, "mevcut yayın bulunduğunda koşu durmuyor")
        val create = commandsOf(release).single { "gh release create" in it }
        assertTrue("--verify-tag" in create, "yayın var olmayan bir etiketle oluşturulabiliyor")
        assertFalse("--clobber" in create || "delete" in create, "yayın akışı mevcut bir yayını siliyor")
        // Nothing leaves this repository but the release itself.
        val text = Files.readString(workflows.resolve("release.yml"))
        listOf("aur.archlinux.org", "scp ", "rsync", "curl -T", "docker push").forEach { upload ->
            assertFalse(upload in text, "yayın akışı başka bir hedefe gönderiyor: $upload")
        }
        assertFalse("gpg" in text || "--sign" in text, "imza anahtarı yokken imza üretiliyor")
    }

    // ----------------------------------------------------------- a small reader

    private fun Any?.asMap(): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return this as Map<String, Any>
    }

    private fun Any?.asList(): List<Any> {
        @Suppress("UNCHECKED_CAST")
        return this as List<Any>
    }

    private fun Any?.orEmpty(): Any = this ?: emptyList<Any>()

    /** Enough YAML for the two files in `.github/workflows`, and no more. */
    private object Yaml {
        fun parse(text: String): Any = Reader(text.lines()).value(0)

        private class Reader(
            private val lines: List<String>,
        ) {
            private var at = 0

            private fun meaningful(): Boolean {
                while (at < lines.size && (lines[at].isBlank() || lines[at].trimStart().startsWith("#"))) at++
                return at < lines.size
            }

            private fun indentOf(line: String) = line.takeWhile { it == ' ' }.length

            fun value(indent: Int): Any {
                if (!meaningful()) return ""
                return if (lines[at].trimStart().startsWith("- ")) list(indent) else map(indent)
            }

            private fun map(indent: Int): Map<String, Any> {
                val entries = LinkedHashMap<String, Any>()
                while (meaningful() && indentOf(lines[at]) >= indent) {
                    if (indentOf(lines[at]) > indent) error("beklenmeyen girinti: ${lines[at]}")
                    val line = lines[at].trim()
                    val key = line.substringBefore(':')
                    val rest = line.substringAfter(':', "").trim()
                    at++
                    entries[key] =
                        when {
                            rest.startsWith("|") || rest.startsWith(">") -> block(indent)
                            rest.isEmpty() -> if (meaningful() && indentOf(lines[at]) > indent) value(indentOf(lines[at])) else ""
                            else -> scalar(rest)
                        }
                }
                return entries
            }

            private fun list(indent: Int): List<Any> {
                val items = mutableListOf<Any>()
                while (meaningful() && indentOf(lines[at]) == indent && lines[at].trimStart().startsWith("- ")) {
                    val first = lines[at].trimStart().removePrefix("- ")
                    at++
                    val rest = mutableListOf(" ".repeat(indent + 2) + first)
                    while (meaningful() && indentOf(lines[at]) > indent) {
                        rest += lines[at]
                        at++
                    }
                    items += if (first.contains(": ") || first.endsWith(":")) Reader(rest).value(indent + 2) else scalar(first)
                }
                return items
            }

            private fun block(indent: Int): String {
                val collected = mutableListOf<String>()
                while (at < lines.size && (lines[at].isBlank() || indentOf(lines[at]) > indent)) {
                    collected += lines[at]
                    at++
                }
                val margin = collected.filter { it.isNotBlank() }.minOfOrNull(::indentOf) ?: 0
                return collected.joinToString("\n") { it.drop(margin) }.trim()
            }

            private fun scalar(raw: String): String {
                val withoutComment = if (" #" in raw && !raw.startsWith("'") && !raw.startsWith("\"")) raw.substringBefore(" #") else raw
                return withoutComment.trim().trim('\'', '"')
            }
        }
    }
}
