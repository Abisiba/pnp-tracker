package dev.pnptracker.packaging

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a fake `pacman` writes when it is called, so a test can see that it was. */
private const val PACMAN_MARKER = "pacman-cagrildi.txt"

/**
 * The clean-Garuda verification scripts (Faz 3 / İş 13), held to what they
 * promise — on this machine, where none of it may actually run.
 *
 * Nothing here installs, removes or asks for a privilege. The scripts are read
 * as text for the rules that keep them safe, checked for syntax by bash itself,
 * and then run against **fake** `pacman`, `sudo` and friends in a temporary
 * directory: the preflight all the way through, and the installer far enough to
 * prove it refuses without a typed confirmation.
 */
class GarudaVerificationScriptsTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun deleteTemporaryRoots() {
        roots.forEach { root ->
            if (Files.exists(root)) {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }

    private fun repository(): Path {
        var directory = Path.of("").toAbsolutePath()
        while (!Files.isDirectory(directory.resolve(".git"))) {
            directory = requireNotNull(directory.parent) { "the repository root is not above the working directory" }
        }
        return directory
    }

    private fun verificationDirectory(): Path = repository().resolve("packaging/verify")

    private val scripts: List<Path>
        get() = listOf("lib.sh", "preflight.sh", "verify-clean-install.sh").map { verificationDirectory().resolve(it) }

    private fun temporaryRoot(prefix: String): Path = Files.createTempDirectory(prefix).also(roots::add)

    /** Runs a command with a prepared PATH and answers on standard input. */
    private fun run(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
        input: String = "",
    ): Pair<Int, String> {
        val builder =
            ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(environment)
        val process = builder.start()
        process.outputStream.use { it.write(input.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "the script did not finish" }
        return process.exitValue() to output
    }

    /**
     * A PATH holding fake system commands, so nothing real is asked anything.
     *
     * `pacman` answers "not installed" and writes a marker whenever it is called;
     * `sudo` refuses outright, because a test must never reach a privilege.
     */
    private fun fakeCommands(
        root: Path,
        pacmanQueryExitCode: Int = 1,
    ): Path {
        val bin = Files.createDirectories(root.resolve("sahte-bin"))

        fun write(
            name: String,
            body: String,
        ) {
            val file = bin.resolve(name)
            Files.writeString(file, "#!/usr/bin/env bash\n$body\n")
            file.toFile().setExecutable(true)
        }
        write(
            "pacman",
            """
            printf '%s\n' "pacman $*" >> "${'$'}MARKER"
            case "${'$'}1" in
              -Qq|-Q) exit $pacmanQueryExitCode ;;
              *) exit 0 ;;
            esac
            """.trimIndent(),
        )
        write("sudo", "printf 'sahte sudo çağrıldı\\n' >> \"\$MARKER\"; exit 1")
        write("desktop-file-validate", "exit 0")
        listOf(
            "bash",
            "cat",
            "sed",
            "grep",
            "head",
            "tr",
            "find",
            "sort",
            "uname",
            "ldd",
            "stat",
            "sha256sum",
            "bsdtar",
            "mktemp",
            "rm",
            "mkdir",
            "wc",
            "basename",
            "dirname",
            "readlink",
            "env",
            "cut",
            "pgrep",
            "tee",
            "printf",
        ).forEach { name ->
            val real = listOf("/usr/bin/$name", "/bin/$name").map(Path::of).firstOrNull { Files.isExecutable(it) }
            if (real != null) Files.createSymbolicLink(bin.resolve(name), real)
        }
        return bin
    }

    private fun environmentFor(
        root: Path,
        bin: Path,
    ): Map<String, String> =
        mapOf(
            "PATH" to bin.toString(),
            "HOME" to Files.createDirectories(root.resolve("ev")).toString(),
            "TMPDIR" to Files.createDirectories(root.resolve("gecici")).toString(),
            "MARKER" to root.resolve(PACMAN_MARKER).toString(),
            "LANG" to "C.UTF-8",
            "DISPLAY" to ":0",
            "XDG_SESSION_TYPE" to "x11",
        )

    /** A file that reads as the Arch package the scripts expect, as far as the fakes go. */
    private fun aPackageFile(root: Path): Path {
        val file = root.resolve("pnp-tracker-9.9.9-1-x86_64.pkg.tar.zst")
        Files.writeString(file, "bu bir paket değil; sahte bsdtar onu okumaz\n")
        return file
    }

    // ------------------------------------------------------------ as text

    @Test
    fun `every script is executable, strict and free of this machine's paths`() {
        scripts.forEach { script ->
            assertTrue(Files.isRegularFile(script), "$script yok")
            val text = Files.readString(script)
            assertTrue(text.startsWith("#!/usr/bin/env bash\n"), "${script.name} bash ile başlamıyor")
            assertTrue("set -euo pipefail" in text, "${script.name} katı kip kullanmıyor")
            assertFalse(Regex("(?<!\\$\\{)/home/[a-z]").containsMatchIn(text), "${script.name} sabit bir /home yolu taşıyor")
            assertFalse(System.getProperty("user.name") in text, "${script.name} kullanıcı adı taşıyor")
            if (script.name != "lib.sh") {
                assertTrue(Files.isExecutable(script), "${script.name} çalıştırılabilir değil")
            }
        }
    }

    @Test
    fun `bash reads every script`() {
        val root = temporaryRoot("pnp-verify-syntax-")
        scripts.forEach { script ->
            val (code, output) = run(listOf("bash", "-n", script.toString()), root, mapOf("PATH" to "/usr/bin:/bin"))
            assertEquals(0, code, "${script.name}: $output")
        }
    }

    @Test
    fun `only pacman is ever run with a privilege`() {
        val installer = Files.readString(verificationDirectory().resolve("verify-clean-install.sh"))
        val privileged =
            installer
                .lines()
                .withIndex()
                .filter { (_, line) -> Regex("(^|[^#\\w])sudo ").containsMatchIn(line) && !line.trimStart().startsWith("#") }
        assertTrue(privileged.isNotEmpty(), "yükseltilmiş komut hiç yok gibi görünüyor")
        privileged.forEach { (index, line) ->
            assertTrue("pacman" in line, "satır ${index + 1} pacman dışında bir şeyi yetkiyle çalıştırıyor: $line")
        }
        assertTrue("\$EUID -eq 0" in installer, "script root olarak çalıştırılmayı reddetmiyor")
        assertFalse(
            "sudo" in Files.readString(verificationDirectory().resolve("preflight.sh")).substringAfter("# `sudo`"),
            "ön kontrol sudo kullanıyor",
        )
    }

    @Test
    fun `nothing is deleted but the run's own temporary directory`() {
        val library = Files.readString(verificationDirectory().resolve("lib.sh"))
        scripts.forEach { script ->
            val text = Files.readString(script)
            val deletions = text.lines().filter { "rm -rf" in it && !it.trimStart().startsWith("#") }
            deletions.forEach { line ->
                assertTrue(
                    script.name == "lib.sh" && "\"\$resolved\"" in line,
                    "${script.name} korumasız bir silme yapıyor: $line",
                )
            }
        }
        // The guard itself: the temporary directory, the prefix, and a refusal.
        assertTrue("PNP_TEMPORARY_PREFIX" in library && "GÜVENLİK" in library, "silme koruması eksik")
    }

    @Test
    fun `the deletion guard refuses a directory the run did not make`() {
        val root = temporaryRoot("pnp-verify-guard-")
        val bin = fakeCommands(root)
        val outside = Files.createDirectories(root.resolve("baskasinin-dizini"))
        val (code, output) =
            run(
                listOf(
                    "bash",
                    "-c",
                    "source '${verificationDirectory().resolve("lib.sh")}'; remove_own_temporary_directory '$outside'",
                ),
                root,
                environmentFor(root, bin),
            )

        assertEquals(1, code, output)
        assertTrue("GÜVENLİK" in output, output)
        assertTrue(Files.exists(outside, LinkOption.NOFOLLOW_LINKS), "koruma dizini yine de sildi")
    }

    @Test
    fun `the deletion guard removes a directory the run did make`() {
        val root = temporaryRoot("pnp-verify-guard-own-")
        val bin = fakeCommands(root)
        val temporary = Files.createDirectories(root.resolve("gecici").resolve("pnp-verify-kendi"))
        Files.writeString(temporary.resolve("dosya.txt"), "veri")
        val (code, output) =
            run(
                listOf(
                    "bash",
                    "-c",
                    "source '${verificationDirectory().resolve("lib.sh")}'; remove_own_temporary_directory '$temporary'",
                ),
                root,
                environmentFor(root, bin),
            )

        assertEquals(0, code, output)
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS), "kendi geçici dizini silinmedi")
    }

    @Test
    fun `the package file name rules read the version and the architecture`() {
        val root = temporaryRoot("pnp-verify-names-")

        fun ask(expression: String): Pair<Int, String> =
            run(
                listOf("bash", "-c", "source '${verificationDirectory().resolve("lib.sh")}'; $expression"),
                root,
                mapOf("PATH" to "/usr/bin:/bin"),
            )

        assertEquals(
            0 to "pnp-tracker-0.1.0-1-x86_64.pkg.tar.zst",
            ask("expected_package_file_name 0.1.0 1 x86_64"),
        )
        assertEquals(0 to "0.1.0-1", ask("version_from_package_file_name /yol/pnp-tracker-0.1.0-1-x86_64.pkg.tar.zst"))
        assertEquals(0 to "x86_64", ask("arch_from_package_file_name pnp-tracker-0.1.0-1-x86_64.pkg.tar.zst"))
        assertEquals(1, ask("version_from_package_file_name baska-paket-1.0.pkg.tar.zst").first)
    }

    // ------------------------------------------------------------ with fakes

    @Test
    fun `the preflight refuses a machine that already has the package`() {
        val root = temporaryRoot("pnp-verify-preflight-")
        val bin = fakeCommands(root, pacmanQueryExitCode = 0)
        val (code, output) =
            run(
                listOf("bash", verificationDirectory().resolve("preflight.sh").toString(), aPackageFile(root).toString()),
                root,
                environmentFor(root, bin),
            )

        assertEquals(1, code, output)
        assertTrue("[FAIL] paket kurulu değil" in output, output)
        assertTrue("SONUÇ MATRİSİ" in output, "matris yazılmadı: $output")
    }

    @Test
    fun `the preflight says what it cannot check and never asks for a privilege`() {
        val root = temporaryRoot("pnp-verify-preflight-clean-")
        val bin = fakeCommands(root)
        val (code, output) =
            run(
                listOf("bash", verificationDirectory().resolve("preflight.sh").toString(), aPackageFile(root).toString()),
                root,
                environmentFor(root, bin),
            )

        // The fake package is not a real one, so the metadata checks fail — which
        // is the point: the preflight says so instead of passing quietly.
        assertTrue("[PASS] paket kurulu değil" in output, output)
        assertTrue("[PASS] kurulum yeri boş: /opt/pnp-tracker" in output, output)
        assertTrue("[INFO] paket SHA-256" in output, output)
        assertTrue("SONUÇ MATRİSİ" in output, output)
        assertEquals(1, code, "bozuk bir paket dosyası başarısız olmalıydı")
        assertFalse(
            Files.exists(root.resolve(PACMAN_MARKER)) && "sudo" in Files.readString(root.resolve(PACMAN_MARKER)),
            "ön kontrol sudo çağırdı",
        )
    }

    @Test
    fun `the installer does nothing at all without a typed confirmation`() {
        val root = temporaryRoot("pnp-verify-refuse-")
        val bin = fakeCommands(root)
        val (code, output) =
            run(
                listOf("bash", verificationDirectory().resolve("verify-clean-install.sh").toString(), aPackageFile(root).toString()),
                root,
                environmentFor(root, bin),
                input = "hayır\n",
            )

        assertEquals(1, code, output)
        assertTrue("Onay verilmedi" in output, output)
        assertFalse(Files.exists(root.resolve(PACMAN_MARKER)), "onay verilmeden pacman çağrıldı")
    }

    @Test
    fun `the installer stops at the preflight and installs nothing`() {
        val root = temporaryRoot("pnp-verify-stop-")
        val bin = fakeCommands(root, pacmanQueryExitCode = 0)
        val (code, output) =
            run(
                listOf("bash", verificationDirectory().resolve("verify-clean-install.sh").toString(), aPackageFile(root).toString()),
                root,
                environmentFor(root, bin),
                input = "EVET\n",
            )

        assertEquals(1, code, output)
        assertTrue("[FAIL] ön kontrol" in output, output)
        val calls = if (Files.exists(root.resolve(PACMAN_MARKER))) Files.readString(root.resolve(PACMAN_MARKER)) else ""
        assertFalse("-U" in calls, "kuruluma geçilmemeliydi: $calls")
        assertFalse("sudo" in calls, "yetki istenmemeliydi: $calls")
    }

    @Test
    fun `the installer refuses to run as root, before anything is installed`() {
        // EUID is read-only in bash, so this cannot be simulated; what is held
        // here is that the refusal exists and comes before the first privilege.
        val installer = Files.readString(verificationDirectory().resolve("verify-clean-install.sh"))
        val refusal = installer.indexOf("Bu script root olarak çalıştırılmaz")
        val firstPrivilege = installer.indexOf("sudo pacman \"\$@\"")
        val firstInstall = installer.indexOf("pacman_as_root -U")
        assertTrue(refusal > 0, "root reddi yok")
        assertTrue(installer.indexOf("\$EUID -eq 0") < firstPrivilege, "root denetimi yetkili komuttan sonra geliyor")
        assertTrue(refusal < firstInstall, "root reddi kurulumdan sonra geliyor")
    }
}
