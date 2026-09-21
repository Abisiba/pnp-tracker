package dev.pnptracker.packaging

import dev.pnptracker.platform.desktop.CloseOutcome
import dev.pnptracker.platform.desktop.MAIN_WINDOW_TITLE
import dev.pnptracker.platform.desktop.SafeWindowCloser
import dev.pnptracker.platform.desktop.SystemCommands
import dev.pnptracker.platform.desktop.WindowChoice
import dev.pnptracker.platform.desktop.awaitWindow
import dev.pnptracker.platform.desktop.isInProcessTree
import dev.pnptracker.platform.desktop.listed
import dev.pnptracker.platform.desktop.stopIfStillRunning
import dev.pnptracker.platform.desktop.windowClassOf
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.system.exitProcess

private const val TEMPORARY_PREFIX = "pnp-package-check-"
private const val PROBE_WAIT_SECONDS = 180L
private const val EXIT_WAIT_SECONDS = 60L

/** The runtime modules Compose adds and the ones `nativeDistributions.modules` asks for. */
private val ROOT_MODULES =
    setOf(
        "java.base",
        "java.desktop",
        "java.logging",
        "jdk.crypto.ec",
        "java.instrument",
        "java.security.jgss",
        "java.xml.crypto",
        "jdk.unsupported",
    )

/** Modules a trimmed runtime must never carry: compilers, tools, debuggers. */
private val TOOL_MODULES = setOf("jdk.compiler", "jdk.jlink", "jdk.jpackage", "jdk.jshell", "jdk.jdeps", "jdk.jdi", "jdk.hotspot.agent")

/**
 * The package contract (Faz 3 / İş 11 and 12): `./gradlew verifyLinuxPackage`
 * and `./gradlew verifyArchPackage`.
 *
 * Everything happens in one temporary directory outside the repository, made by
 * this run and removed by it. The package is looked at statically — its layout,
 * permissions, version, runtime and every byte for a path of this machine — and
 * then its real launcher is started twice with three temporary XDG folders, an
 * empty working directory, a HOME of its own, an empty PATH and no JAVA_HOME:
 * once with [PackageRuntimeProbe] as an agent, which runs the application's
 * features on the embedded runtime, and once as a person would, where the main
 * window is found and closed by [SafeWindowCloser] alone.
 */
fun main(args: Array<String>) {
    val check = PackageCheck()
    val root = Files.createTempDirectory(TEMPORARY_PREFIX)
    try {
        when (args.firstOrNull()) {
            "archive" -> check.archive(root, ArchiveArguments.of(args.drop(1)))
            "arch" -> check.arch(root, ArchArguments.of(args.drop(1)))
            else -> error("usage: archive|arch …")
        }
    } catch (failure: Exception) {
        check.problems += "the check itself failed: ${failure::class.simpleName}: ${failure.message}"
    } finally {
        deleteOwnTemporaryRoot(root)
        println("PACKAGE: temporary directory removed: ${!Files.exists(root, LinkOption.NOFOLLOW_LINKS)}")
    }
    if (check.problems.isEmpty()) {
        println("PACKAGE: PASSED")
        exitProcess(0)
    }
    check.problems.forEach { println("PACKAGE: FAILED — $it") }
    exitProcess(1)
}

/** What the archive check is told by the build. */
class ArchiveArguments(
    val archive: Path,
    val version: String,
    val arch: String,
    val probeJar: Path,
    val sample: Path,
    val repository: Path,
) {
    companion object {
        fun of(args: List<String>) =
            ArchiveArguments(Path.of(args[0]), args[1], args[2], Path.of(args[3]), Path.of(args[4]), Path.of(args[5]))
    }
}

/** What the Arch package check is told by the build. */
class ArchArguments(
    val packageFile: Path,
    val version: String,
    val arch: String,
    val probeJar: Path,
    val sample: Path,
    val repository: Path,
) {
    companion object {
        fun of(args: List<String>) = ArchArguments(Path.of(args[0]), args[1], args[2], Path.of(args[3]), Path.of(args[4]), Path.of(args[5]))
    }
}

class PackageCheck {
    val problems = mutableListOf<String>()

    fun report(line: String) = println("PACKAGE: $line")

    fun expect(
        condition: Boolean,
        problem: () -> String,
    ) {
        if (!condition) problems += problem()
    }

    // ---------------------------------------------------------------- İş 11

    fun archive(
        root: Path,
        arguments: ArchiveArguments,
    ) {
        val top = "pnp-tracker-${arguments.version}"
        val expectedName = "$top-linux-${arguments.arch}.tar.gz"
        expect(arguments.archive.name == expectedName) { "the archive is ${arguments.archive.name}, not $expectedName" }
        report("archive ${arguments.archive.name}, ${Files.size(arguments.archive)} bytes")

        val entries = run("tar", "--numeric-owner", "-tvzf", arguments.archive.toString()).lines().filter { it.isNotBlank() }
        report("${entries.size} entries")
        checkEntries(entries.map(::tarEntryOf), top)

        val extracted = Files.createDirectories(root.resolve("extracted"))
        run("tar", "-xzf", arguments.archive.toString(), "-C", extracted.toString(), "--preserve-permissions", "--no-same-owner")
        val application = extracted.resolve(top)
        expect(listed(extracted) == listOf(top)) { "the archive does not unpack into one directory $top" }
        expect(
            Files.readString(application.resolve("VERSION")) == "name=pnp-tracker\nversion=${arguments.version}\narch=${arguments.arch}\n",
        ) {
            "VERSION does not say pnp-tracker ${arguments.version} ${arguments.arch}"
        }
        expect(Files.exists(application.resolve("README.txt"))) { "no README.txt" }
        expect(Files.exists(application.resolve("LICENSE"))) { "no LICENSE at the top of the archive" }
        expect(Files.exists(application.resolve("THIRD_PARTY_NOTICES.md"))) { "no THIRD_PARTY_NOTICES.md at the top of the archive" }
        checkApplicationDirectory(application, arguments.version, arguments.repository)

        val launcher = application.resolve("bin/pnp-tracker")
        checkNoFallbackToSystemJava(root, application)
        runProbe(root, launcher, application, arguments.probeJar, arguments.sample)
        runWindow(root, launcher, application)
    }

    // ---------------------------------------------------------------- İş 12

    fun arch(
        root: Path,
        arguments: ArchArguments,
    ) {
        val expectedName = "pnp-tracker-${arguments.version}-1-${arguments.arch}.pkg.tar.zst"
        expect(arguments.packageFile.name == expectedName) { "the package is ${arguments.packageFile.name}, not $expectedName" }
        report("package ${arguments.packageFile.name}, ${Files.size(arguments.packageFile)} bytes")

        val entries = run("bsdtar", "--numeric-owner", "-tvf", arguments.packageFile.toString()).lines().filter { it.isNotBlank() }
        val parsed = entries.map(::tarEntryOf)
        report("${parsed.size} entries")
        val names = parsed.map { it.name.removePrefix("./").trimEnd('/') }.toSet()
        val metadata = setOf(".PKGINFO", ".BUILDINFO", ".MTREE")
        listOf(
            "opt/pnp-tracker/bin/pnp-tracker",
            "usr/bin/pnp-tracker",
            "usr/share/applications/pnp-tracker.desktop",
            "usr/share/icons/hicolor/256x256/apps/pnp-tracker.png",
            // Where Arch keeps a package's licence (Faz 3 / İş 15).
            "usr/share/licenses/pnp-tracker/LICENSE",
            "usr/share/licenses/pnp-tracker/THIRD_PARTY_NOTICES.md",
        ).forEach { expect(it in names) { "$it is not in the package" } }
        val outside = names.filterNot { it in metadata || it.startsWith("opt") || it.startsWith("usr") }
        expect(outside.isEmpty()) { "the package installs outside /opt and /usr: $outside" }
        expect(names.none { it.startsWith("home") || it.startsWith("root") || it.startsWith("etc") || it.startsWith("var") }) {
            "the package installs into home, root, etc or var"
        }
        parsed.filter { it.name.removePrefix("./") !in metadata }.forEach { entry ->
            expect(entry.owner == "0/0") { "${entry.name} is owned by ${entry.owner}, not root" }
            // A symbolic link's own permission bits mean nothing; what it points at is checked below.
            expect(
                entry.type == 'l' || (entry.mode[5] != 'w' && entry.mode[8] != 'w'),
            ) { "${entry.name} is writable by others: ${entry.mode}" }
            expect(!entry.name.split('/').contains("..") && !entry.name.startsWith("/")) { "unsafe path ${entry.name}" }
        }
        val usrBin = parsed.single { it.name.removePrefix("./") == "usr/bin/pnp-tracker" }
        expect(usrBin.type == 'l' && usrBin.linkTarget == "../../opt/pnp-tracker/bin/pnp-tracker") {
            "/usr/bin/pnp-tracker is not a relative link to /opt/pnp-tracker/bin/pnp-tracker: ${usrBin.type} ${usrBin.linkTarget}"
        }

        val pkginfo = run("bsdtar", "-xOf", arguments.packageFile.toString(), ".PKGINFO")
        report(
            ".PKGINFO: " +
                pkginfo.lines().filter { it.startsWith("pkg") || it.startsWith("depend") || it.startsWith("arch") }.joinToString("; "),
        )
        expect("pkgname = pnp-tracker\n" in pkginfo) { ".PKGINFO does not name pnp-tracker" }
        expect("pkgver = ${arguments.version}-1\n" in pkginfo) { ".PKGINFO does not say ${arguments.version}-1" }
        expect("license = MIT\n" in pkginfo) { ".PKGINFO does not say the licence is MIT" }
        expect(pkginfo.lines().none { it.startsWith("depend = java") || it.startsWith("depend = jre") || it.startsWith("depend = jdk") }) {
            "the package depends on a system Java"
        }
        val buildinfo = run("bsdtar", "-xOf", arguments.packageFile.toString(), ".BUILDINFO")
        checkNoLeaks(
            "package metadata",
            listOf(pkginfo, buildinfo).joinToString("\n").encodeToByteArray(),
            arguments.repository,
            text = true,
        )

        val installed = Files.createDirectories(root.resolve("installed"))
        run(
            "bsdtar",
            "-xpf",
            arguments.packageFile.toString(),
            "-C",
            installed.toString(),
            "--exclude",
            ".PKGINFO",
            "--exclude",
            ".BUILDINFO",
            "--exclude",
            ".MTREE",
        )
        val application = installed.resolve("opt/pnp-tracker")
        checkApplicationDirectory(application, arguments.version, arguments.repository)
        checkDeclaredDependencies(installed, pkginfo)
        checkDesktopEntry(installed.resolve("usr/share/applications/pnp-tracker.desktop"))
        expect(
            Files
                .readAllBytes(installed.resolve("usr/share/icons/hicolor/256x256/apps/pnp-tracker.png"))
                .contentEquals(Files.readAllBytes(application.resolve("lib/pnp-tracker.png"))),
        ) { "the hicolor icon is not the application's icon" }
        listOf("LICENSE", "THIRD_PARTY_NOTICES.md").forEach { file ->
            expect(
                Files
                    .readAllBytes(installed.resolve("usr/share/licenses/pnp-tracker/$file"))
                    .contentEquals(Files.readAllBytes(application.resolve(file))),
            ) { "/usr/share/licenses/pnp-tracker/$file is not the file under /opt" }
        }

        // The real entry point, /usr/bin/pnp-tracker, resolved inside the temporary root.
        val launcher = installed.resolve("usr/bin/pnp-tracker")
        expect(launcher.toRealPath() == application.resolve("bin/pnp-tracker").toRealPath()) { "/usr/bin/pnp-tracker does not reach /opt" }
        val before = digestsOf(application)
        runProbe(root, launcher, application, arguments.probeJar, arguments.sample)
        runWindow(root, launcher, application)
        expect(digestsOf(application) == before) { "running the application changed a file under /opt/pnp-tracker" }

        // Removing the package, as pacman would, touches its own files and nothing else.
        val home = root.resolve("window/home")
        val userAreas = listOf(root.resolve("window/data"), root.resolve("window/config"), root.resolve("window/state"))
        val userBefore = userAreas.associateWith { digestsOf(it) }
        parsed
            .map { it.name.removePrefix("./").trimEnd('/') }
            .filter { it !in metadata }
            .sortedByDescending { it.length }
            .forEach { name ->
                val path = installed.resolve(name)
                check(path.normalize().startsWith(installed)) { "refusing to remove $path" }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (listed(path).isEmpty()) Files.delete(path)
                } else {
                    Files.deleteIfExists(path)
                }
            }
        expect(
            !Files.exists(installed.resolve("opt/pnp-tracker")) &&
                !Files.exists(installed.resolve("usr/bin/pnp-tracker"), LinkOption.NOFOLLOW_LINKS),
        ) {
            "removing the package's files left some behind"
        }
        expect(userAreas.all { digestsOf(it) == userBefore.getValue(it) } && Files.exists(root.resolve("window/data/pnp-tracker/pnp.db"))) {
            "removing the package touched the user's data, config or state"
        }
        report("removal: package files gone; data, config and state untouched; home ${listed(home)}")
    }

    /**
     * Everything the package's own files link against is declared (Faz 3, after
     * the 0.1.1 defect: four libraries the Arch JDK links and Temurin bundles
     * were missing from `depends`, and every machine involved happened to have
     * them).
     *
     * A library is answered for only by the package itself or by a package the
     * recipe declares, directly or through Arch's own dependency metadata.
     * Being installed on the machine running this proves nothing — that is
     * exactly how the defect survived — so an owner outside the declared
     * closure is a failure, and so is a library nothing owns.
     */
    private fun checkDeclaredDependencies(
        installed: Path,
        pkginfo: String,
    ) {
        val pacman = Files.isExecutable(Path.of("/usr/bin/pacman"))
        val readelf = SystemCommands.run(listOf("readelf", "--version")).exitCode == 0
        if (!pacman || !readelf) {
            // Not verifiable here is not the same as verified: the Arch package
            // is only ever built on Arch, so the tools are expected to be there.
            problems += "the declared dependencies could not be checked: pacman=$pacman readelf=$readelf"
            return
        }

        val elfFiles = elfFilesUnder(installed)
        val bundled = bundledSonames(installed, elfFiles)
        val needed =
            elfFiles.flatMap { file ->
                neededLibrariesOf(file).map { NeededLibrary(elf = installed.relativize(file).toString(), soname = it) }
            }
        credibilityProblem(elfFiles.size, needed)?.let {
            problems += "the dependency check cannot be believed: $it"
            return
        }
        val declared = declaredDependsOf(pkginfo)
        val closure = transitiveDependencies(declared)
        val report = resolveDependencies(needed, bundled, declared.toSet(), closure, ::ownerOfSoname)

        report("dependencies: ${elfFiles.size} ELF files, ${needed.map { it.soname }.distinct().size} distinct libraries")
        report("dependencies: declared ${declared.sorted().joinToString(" ")}")
        report("dependencies: declared closure ${closure.size} packages")
        report.lines().filterNot { "pakette:" in it }.forEach { report("  $it") }
        expect(report.undeclared.isEmpty()) {
            "the package links libraries it does not declare: " +
                report.undeclared
                    .groupBy { it.needed.soname }
                    .toSortedMap()
                    .map { (soname, uses) ->
                        val owner = (uses.first().provider as Provider.Undeclared).owner ?: "hiçbir pakette yok"
                        "$soname ($owner), wanted by ${uses.map { it.needed.elf }.distinct().sorted().joinToString(", ")}"
                    }.joinToString("; ")
        }
    }

    // ---------------------------------------------------------------- shared

    private fun checkEntries(
        entries: List<TarEntry>,
        top: String,
    ) {
        entries.forEach { entry ->
            val parts = entry.name.trimEnd('/').split('/')
            expect(parts.first() == top) { "${entry.name} is outside $top/" }
            expect(!entry.name.startsWith("/") && ".." !in parts && "." !in parts.drop(1)) { "unsafe path ${entry.name}" }
            expect(entry.type in setOf('-', 'd', 'l')) { "${entry.name} is of type ${entry.type}" }
            expect(entry.owner == "0/0") { "${entry.name} carries the owner ${entry.owner}" }
            expect(entry.mode[5] != 'w' && entry.mode[8] != 'w') { "${entry.name} is writable by others: ${entry.mode}" }
            if (entry.type == 'l') {
                val target = requireNotNull(entry.linkTarget)
                val resolved =
                    Path
                        .of(entry.name)
                        .parent
                        .resolve(target)
                        .normalize()
                expect(!target.startsWith("/") && resolved.startsWith(top)) { "${entry.name} links outside the package: $target" }
            }
        }
        val links = entries.count { it.type == 'l' }
        report("entries: one top directory, owners 0/0, no path escapes, $links symbolic link(s)")
    }

    /**
     * The application's own licence and the notices for everything else it
     * carries (Faz 3 / İş 15). The LICENSE in the package is the repository's
     * LICENSE, byte for byte, and the notices are the ones the staging task
     * derived from this very package — the runtime's modules and the jars whose
     * own licence files were copied out beside it.
     */
    private fun checkLicence(
        application: Path,
        version: String,
        repository: Path,
    ) {
        val licence = application.resolve("LICENSE")
        expect(Files.isRegularFile(licence)) { "the package carries no LICENSE" }
        if (!Files.isRegularFile(licence)) return
        expect(Files.readAllBytes(licence).contentEquals(Files.readAllBytes(repository.resolve("LICENSE")))) {
            "the LICENSE in the package is not the repository's LICENSE"
        }
        val text = Files.readString(licence)
        expect(text.startsWith("MIT License\n")) { "the LICENSE is not the MIT licence" }
        expect("Copyright (c) 2026 PNP Tracker contributors" in text) { "the LICENSE carries another copyright line" }

        val notices = application.resolve("THIRD_PARTY_NOTICES.md")
        expect(Files.isRegularFile(notices)) { "the package carries no THIRD_PARTY_NOTICES.md" }
        if (!Files.isRegularFile(notices)) return
        val noticeText = Files.readString(notices)
        // Every runtime module the package ships is named, and so is every jar.
        val modules = listed(application.resolve("lib/runtime/legal"))
        expect(modules.isNotEmpty() && modules.all { it in noticeText }) { "the notices do not name every runtime module" }
        // Our own jar is not a third party; it is the MIT-licensed application itself.
        val jars =
            listed(application.resolve("lib/app"))
                .filter { it.endsWith(".jar") }
                .map { it.removeSuffix(".jar").replace(Regex("-[0-9a-f]{16,40}$"), "") }
                .filterNot { it == "app-desktop-$version" }
        expect("app-desktop" !in noticeText) { "the notices list our own jar as a third party" }
        val missing = jars.filterNot { jar -> jar.substringBeforeLast('-') in noticeText }
        expect(missing.isEmpty()) { "the notices do not name $missing" }
        expect("MIT" in noticeText && "lisansını **tahmin etmez**" in noticeText) {
            "the notices do not separate our licence from the others'"
        }
        // The licence files the jars really carry are in the package next to the notices.
        val copied = listed(application.resolve("third-party"))
        expect(copied.isNotEmpty()) { "no third-party licence text was copied into the package" }
        copied.forEach { directory ->
            expect("`third-party/$directory/`" in noticeText) { "third-party/$directory is not in the notices" }
            expect(listed(application.resolve("third-party/$directory")).isNotEmpty()) { "third-party/$directory is empty" }
        }
        report("licence: MIT, ${copied.size} third-party licence texts, ${modules.size} runtime legal directories")
    }

    private fun checkApplicationDirectory(
        application: Path,
        version: String,
        repository: Path,
    ) {
        listOf(
            "bin/pnp-tracker",
            "lib/libapplauncher.so",
            "lib/pnp-tracker.png",
            "lib/app/pnp-tracker.cfg",
            "lib/app/.jpackage.xml",
            "lib/app/libskiko-linux-x64.so",
            "lib/runtime/release",
            "lib/runtime/lib/modules",
            "lib/runtime/lib/server/libjvm.so",
        ).forEach { expect(Files.isRegularFile(application.resolve(it))) { "$it is missing" } }
        val launcher = application.resolve("bin/pnp-tracker")
        val permissions = Files.getPosixFilePermissions(launcher)
        expect(
            permissions.containsAll(
                listOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE),
            ),
        ) { "the launcher is not executable by everyone: $permissions" }
        Files.walk(application).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                val mode = Files.getPosixFilePermissions(file)
                expect(
                    PosixFilePermission.GROUP_WRITE !in mode && PosixFilePermission.OTHERS_WRITE !in mode,
                ) { "$file is writable by others" }
            }
        }

        val cfg = Files.readAllLines(application.resolve("lib/app/pnp-tracker.cfg"))
        val classpath = cfg.filter { it.startsWith("app.classpath=") }.map { it.removePrefix("app.classpath=") }
        expect(classpath.isNotEmpty() && classpath.all { it.startsWith("\$APPDIR/") }) { "the launcher's classpath leaves the package" }
        classpath.forEach { entry ->
            expect(Files.isRegularFile(application.resolve("lib/app").resolve(entry.removePrefix("\$APPDIR/")))) { "$entry is missing" }
        }
        expect(cfg.none { it.startsWith("app.runtime") }) { "the launcher names a runtime of its own choosing" }
        expect(cfg.none { Regex("(^|[=\\s])/").containsMatchIn(it) }) { "the launcher configuration holds an absolute path" }
        expect("java-options=-Djpackage.app-version=$version" in cfg) { "the launcher does not say version $version" }
        expect(cfg.any { Regex("^app\\.classpath=\\\$APPDIR/app-desktop-${Regex.escape(version)}-[0-9a-f]+\\.jar$").matches(it) }) {
            "the application jar is not version $version"
        }
        expect("<app-version>$version</app-version>" in Files.readString(application.resolve("lib/app/.jpackage.xml"))) {
            "jpackage's own record is not version $version"
        }

        checkLicence(application, version, repository)

        val release = Files.readAllLines(application.resolve("lib/runtime/release"))
        val modules =
            release
                .single { it.startsWith("MODULES=") }
                .removePrefix("MODULES=")
                .trim('"')
                .split(' ')
                .toSet()
        expect(modules.containsAll(ROOT_MODULES)) { "the runtime lacks ${ROOT_MODULES - modules}" }
        expect(modules.intersect(TOOL_MODULES).isEmpty()) { "the runtime carries tools: ${modules.intersect(TOOL_MODULES)}" }
        expect(!Files.exists(application.resolve("lib/runtime/bin"))) { "the runtime carries its own java commands" }
        report(
            "runtime ${release.single { it.startsWith("JAVA_VERSION=") }}, ${modules.size} modules: ${modules.sorted().joinToString(" ")}",
        )

        val textFiles = listOf("lib/app/pnp-tracker.cfg", "lib/app/.jpackage.xml", "lib/runtime/release").map(application::resolve)
        textFiles.forEach { file ->
            val text = Files.readString(file)
            listOf("/usr/lib/jvm", "JAVA_HOME", "/usr/bin/java", "/usr/share/java").forEach { reference ->
                expect(reference !in text) { "${application.relativize(file)} refers to $reference" }
            }
        }
        var files = 0
        Files.walk(application).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                files++
                val text = file.name.endsWith(".cfg") || file.name.endsWith(".xml") || file.name == "release" || file.name.endsWith(".txt")
                checkNoLeaks(application.relativize(file).toString(), Files.readAllBytes(file), repository, text)
            }
        }
        expect(Files.walk(application).use { paths -> paths.noneMatch { it.name.endsWith(".kt") || it.name.endsWith(".class") } }) {
            "source or loose class files in the package"
        }
        report("application directory: layout, permissions, version $version, launcher configuration, no system Java; $files files scanned")
    }

    private fun checkNoLeaks(
        what: String,
        bytes: ByteArray,
        repository: Path,
        text: Boolean,
    ) {
        val user = System.getProperty("user.name")
        val home = System.getProperty("user.home")
        val patterns = mutableListOf(repository.toAbsolutePath().toString(), home, "/home/$user", ".gradle/caches", "build/compose")
        if (text) patterns += user
        patterns.filter { it.length >= 4 }.forEach { pattern ->
            expect(indexOf(bytes, pattern.encodeToByteArray()) < 0) { "$what holds a path or name of this machine" }
        }
    }

    private fun checkDesktopEntry(file: Path) {
        val lines = Files.readAllLines(file)
        expect("Exec=pnp-tracker" in lines) { "the desktop entry does not start pnp-tracker" }
        expect("Icon=pnp-tracker" in lines) { "the desktop entry does not name the pnp-tracker icon" }
        expect("Type=Application" in lines && "Terminal=false" in lines) { "the desktop entry is not a graphical application" }
        val validator = Path.of("/usr/bin/desktop-file-validate")
        if (Files.isExecutable(validator)) {
            val result = ProcessBuilder(validator.toString(), file.toString()).redirectErrorStream(true).start()
            val said = result.inputStream.bufferedReader().readText()
            expect(result.waitFor() == 0 && said.isBlank()) { "desktop-file-validate: $said" }
            report("desktop entry: Exec=pnp-tracker, Icon=pnp-tracker, desktop-file-validate clean")
        } else {
            report("desktop entry: Exec=pnp-tracker, Icon=pnp-tracker; desktop-file-validate not installed, structure checked only")
        }
    }

    /**
     * A copy of the launcher without the embedded runtime, tempted with a real JDK
     * in JAVA_HOME and on PATH, must refuse to start rather than use it.
     */
    private fun checkNoFallbackToSystemJava(
        root: Path,
        application: Path,
    ) {
        val area = Files.createDirectories(root.resolve("no-runtime"))
        val copy = Files.createDirectories(area.resolve("pnp-tracker"))
        listOf("bin/pnp-tracker", "lib/libapplauncher.so", "lib/app/pnp-tracker.cfg", "lib/app/.jpackage.xml").forEach { file ->
            Files.createDirectories(copy.resolve(file).parent)
            Files.copy(application.resolve(file), copy.resolve(file), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
        }
        val environment = isolatedEnvironment(area)
        val systemJava = Path.of(System.getProperty("java.home"))
        environment["JAVA_HOME"] = systemJava.toString()
        environment["PATH"] = "${systemJava.resolve("bin")}:/usr/bin:/bin"
        environment.remove("TMP")
        val process =
            ProcessBuilder(copy.resolve("bin/pnp-tracker").toString())
                .directory(area.resolve("cwd").toFile())
                .redirectErrorStream(true)
                .redirectOutput(area.resolve("output.log").toFile())
                .also {
                    it.environment().clear()
                    it.environment().putAll(environment)
                }.start()
        val ended = process.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
        if (!ended) process.destroyForcibly().waitFor(20, TimeUnit.SECONDS)
        val exit = if (ended) process.exitValue() else -1
        expect(ended && exit != 0) { "without its runtime the launcher did not refuse (exit $exit)" }
        expect(filesUnder(area.resolve("data")).isEmpty()) { "without its runtime the launcher started the application anyway" }
        report("no fallback: without lib/runtime, and with a JDK in JAVA_HOME and on PATH, the launcher refused (exit $exit)")
    }

    /** Starts the launcher with [PackageRuntimeProbe] as an agent: the features, on the embedded runtime. */
    private fun runProbe(
        root: Path,
        launcher: Path,
        application: Path,
        probeJar: Path,
        sample: Path,
    ) {
        val area = Files.createDirectories(root.resolve("probe"))
        val work = Files.createDirectories(area.resolve("work"))
        Files.copy(sample, work.resolve("sample-import.xlsx"))
        val agent = Files.copy(probeJar, area.resolve("probe.jar"))
        val environment = isolatedEnvironment(area)
        check(listOf(environment.getValue("TMP"), agent.toString(), application.toString(), work.toString()).none { ' ' in it }) {
            "a path with a space cannot go through JAVA_TOOL_OPTIONS"
        }
        environment["JAVA_TOOL_OPTIONS"] =
            "-Djava.io.tmpdir=${environment.getValue("TMP")} -javaagent:$agent=${application.toRealPath()}|$work"
        environment.remove("TMP")
        val log = area.resolve("probe.log")
        val process =
            ProcessBuilder(launcher.toString())
                .directory(area.resolve("cwd").toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .also {
                    it.environment().clear()
                    it.environment().putAll(environment)
                }.start()
        val ended = process.waitFor(PROBE_WAIT_SECONDS, TimeUnit.SECONDS)
        if (!ended) process.destroyForcibly().waitFor(20, TimeUnit.SECONDS)
        val output = Files.readAllLines(log)
        output.filter { it.startsWith("PROBE:") }.forEach { report(it.removePrefix("PROBE: ").let { line -> "probe $line" }) }
        expect(ended && process.exitValue() == 0 && "PROBE: OK" in output) { "the runtime probe did not pass: ${output.takeLast(20)}" }
        expect(listed(area.resolve("cwd")).isEmpty()) { "the probe wrote into its working directory" }
    }

    /** Starts the launcher as a person would, and closes its window through [SafeWindowCloser] only. */
    private fun runWindow(
        root: Path,
        launcher: Path,
        application: Path,
    ) {
        val area = Files.createDirectories(root.resolve("window"))
        val environment = isolatedEnvironment(area)
        environment["JAVA_TOOL_OPTIONS"] = "-Djava.io.tmpdir=${environment.getValue("TMP")}"
        environment.remove("TMP")
        val log = area.resolve("application.log")
        val application0 =
            ProcessBuilder(launcher.toString())
                .directory(area.resolve("cwd").toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .also {
                    it.environment().clear()
                    it.environment().putAll(environment)
                }.start()
        val run = application0.pid()
        report("window run: launcher started as process $run")
        val closer = SafeWindowCloser(SystemCommands) { pid -> isInProcessTree(pid, run) }
        val started = mutableListOf<ProcessHandle>()
        try {
            val found = awaitWindow(application0, closer, MAIN_WINDOW_TITLE)
            if (found !is WindowChoice.One) {
                problems += "no window was closed: ${SafeWindowCloser.describe(found)}"
                return
            }
            report("window ${found.window.id} of process ${found.window.pid}, title exactly \"$MAIN_WINDOW_TITLE\"")
            started += application0.descendants().toList()
            report("window ${windowClassOf(found.window)}")
            checkTheRunningJvm(run, launcher, application)
            when (val outcome = closer.close(MAIN_WINDOW_TITLE)) {
                is CloseOutcome.Refused -> {
                    problems += "the window was not closed: ${outcome.why}"
                    return
                }
                is CloseOutcome.Closed -> report("close request sent to ${outcome.window.id} only")
            }
            if (!application0.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                problems += "the application did not exit after its window was closed"
                return
            }
            val exit = application0.exitValue()
            report("exit code $exit")
            expect(exit == 0) { "the application exited with $exit" }

            val data = listed(area.resolve("data/pnp-tracker"))
            report("temporary data folder: $data")
            expect("pnp.db" in data) { "no database in the temporary data folder" }
            expect(data.none { it.endsWith("-wal") || it.endsWith("-shm") }) { "the database was not closed cleanly: $data" }
            val state = filesUnder(area.resolve("state"))
            report("state folder files: ${state.size}")
            expect(state.isEmpty()) { "an ordinary run wrote into the state folder" }
            expect(listed(area.resolve("cwd")).isEmpty()) { "the application wrote into its working directory" }
            val home = filesUnder(area.resolve("home"))
            report("HOME after the run: ${home.map { area.resolve("home").relativize(it) }}")
            expect(home.isEmpty()) { "the application wrote into HOME outside the XDG folders" }
            report("java.io.tmpdir after the run: ${filesUnder(area.resolve("tmp")).map { area.resolve("tmp").relativize(it) }}")
            val output = Files.readAllLines(log)
            val trouble = output.filter { "Exception" in it || it.trimStart().startsWith("at ") }
            report("exception lines in the application's output: ${trouble.size}")
            expect(trouble.isEmpty()) { "the application printed an exception: ${trouble.take(5)}" }
        } finally {
            val left = mutableListOf<String>()
            stopIfStillRunning(application0, started, left)
            problems += left
        }
    }

    /** The JVM behind the window is the package's: its launcher, its libjvm.so, no other Java. */
    private fun checkTheRunningJvm(
        run: Long,
        launcher: Path,
        application: Path,
    ) {
        val home = application.toRealPath()
        val processes =
            listOf(run) +
                ProcessHandle
                    .of(run)
                    .get()
                    .descendants()
                    .map { it.pid() }
                    .toList()
        val jvm =
            processes.firstNotNullOfOrNull { pid ->
                val maps = runCatching { Files.readAllLines(Path.of("/proc/$pid/maps")) }.getOrNull() ?: return@firstNotNullOfOrNull null
                val libraries = maps.mapNotNull { it.split(Regex("\\s+")).getOrNull(5) }.filter { it.startsWith("/") }.toSet()
                if (libraries.any { it.endsWith("libjvm.so") }) pid to libraries else null
            }
        if (jvm == null) {
            problems += "no process of the run has a JVM loaded"
            return
        }
        val (pid, libraries) = jvm
        val exe = Path.of("/proc/$pid/exe").toRealPath()
        expect(exe == launcher.toRealPath()) { "the JVM runs as $exe, not the package's launcher" }
        val javaLibraries =
            libraries.filter {
                "/jvm/" in it ||
                    it.endsWith("libjvm.so") ||
                    it.endsWith("libjava.so") ||
                    "/lib/runtime/" in it
            }
        expect(javaLibraries.isNotEmpty() && javaLibraries.all { Path.of(it).startsWith(home) }) {
            "Java libraries from outside the package: ${javaLibraries.filterNot { Path.of(it).startsWith(home) }}"
        }
        report("running JVM: process $pid is the package's launcher; libjvm.so and every Java library from the package")
        val system = libraries.filterNot { Path.of(it).startsWith(home) }.filter { ".so" in it }.sorted()
        val owners =
            if (Files.isExecutable(Path.of("/usr/bin/pacman"))) {
                system
                    .mapNotNull { library ->
                        val query = ProcessBuilder("/usr/bin/pacman", "-Qoq", library).redirectErrorStream(true).start()
                        val owner =
                            query.inputStream
                                .bufferedReader()
                                .readText()
                                .trim()
                        if (query.waitFor() == 0) owner else null
                    }.toSortedSet()
            } else {
                sortedSetOf()
            }
        report("system libraries the running application mapped: ${system.size}, from packages $owners")
    }

    /** No PATH, no JAVA_HOME, no JDK options; a HOME, three XDG folders and a working directory of its own. */
    private fun isolatedEnvironment(area: Path): MutableMap<String, String> {
        val environment = mutableMapOf<String, String>()
        // The display this run may use, and — where there is no GL, as in a
        // container — the renderer the machine running the check chose.
        listOf("DISPLAY", "XAUTHORITY", "LANG", "LC_ALL", "XDG_RUNTIME_DIR", "SKIKO_RENDER_API").forEach { name ->
            System.getenv(name)?.let { environment[name] = it }
        }
        environment["PATH"] = Files.createDirectories(area.resolve("empty-path")).toString()
        environment["HOME"] = Files.createDirectories(area.resolve("home")).toString()
        environment["XDG_DATA_HOME"] = Files.createDirectories(area.resolve("data")).toString()
        environment["XDG_CONFIG_HOME"] = Files.createDirectories(area.resolve("config")).toString()
        environment["XDG_STATE_HOME"] = Files.createDirectories(area.resolve("state")).toString()
        // Not the application's own folder: graphics drivers keep a shader cache
        // here, and without it they fall back to $HOME/.cache, which this check
        // reads as the application writing into HOME.
        environment["XDG_CACHE_HOME"] = Files.createDirectories(area.resolve("cache")).toString()
        environment["TMP"] = Files.createDirectories(area.resolve("tmp")).toString()
        Files.createDirectories(area.resolve("cwd"))
        return environment
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${command.first()} failed: $errors" }
        return output
    }
}

/** One line of `tar -tv` / `bsdtar -tv`. */
class TarEntry(
    val mode: String,
    val owner: String,
    val name: String,
    val linkTarget: String?,
) {
    val type: Char get() = mode.first()
}

/** Reads `-rw-r--r-- 0/0 1250 1970-01-02 02:00 name` (GNU tar) and bsdtar's `mode links uid gid size Mon d  y name`. */
fun tarEntryOf(line: String): TarEntry {
    val fields = line.trim().split(Regex("\\s+"))
    val mode = fields[0]
    val (owner, rest) =
        if ('/' in fields[1]) {
            fields[1] to fields.drop(5)
        } else {
            "${fields[2]}/${fields[3]}" to fields.drop(8)
        }
    val text = rest.joinToString(" ")
    val arrow = text.indexOf(" -> ")
    return if (arrow >= 0) {
        TarEntry(mode, owner, text.substring(0, arrow), text.substring(arrow + 4))
    } else {
        TarEntry(mode, owner, text, null)
    }
}

/** Every regular file under [directory], or none if it is not there. */
fun filesUnder(directory: Path): List<Path> =
    if (!Files.isDirectory(directory)) {
        emptyList()
    } else {
        Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList() }
    }

/** Relative path → SHA-256 of every file under [directory]. */
fun digestsOf(directory: Path): Map<String, String> =
    filesUnder(directory).associate { file ->
        directory.relativize(file).toString() to
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(file))
                .joinToString("") { "%02x".format(it) }
    }

private fun indexOf(
    haystack: ByteArray,
    needle: ByteArray,
): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    val first = needle[0]
    var i = 0
    val last = haystack.size - needle.size
    while (i <= last) {
        if (haystack[i] == first) {
            var j = 1
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
        }
        i++
    }
    return -1
}

/** Deletes the one directory this run made, and refuses anything that is not plainly that. */
private fun deleteOwnTemporaryRoot(root: Path) {
    val systemTemporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
    check(root.parent.toRealPath() == systemTemporary && root.name.startsWith(TEMPORARY_PREFIX)) { "not this run's folder" }
    // A directory the package unpacked read-only must still be emptied.
    Files.walk(root).use { entries ->
        entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { it.toFile().setWritable(true, true) }
    }
    Files.walk(root).use { entries ->
        entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
