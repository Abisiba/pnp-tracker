import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.ktlint)
}

// The one version this application has (master §33 R5). Everything that says a
// version — AppInfo, the backup documents, the launcher, the archive and the Arch
// package — is derived from it; nothing else writes one down.
version = "0.1.2"

// Jars and archives carry no build moment and no file-system order, so two
// builds of the same sources produce the same package (Faz 3 / İş 11).
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// AppInfo reads the version from a constant generated here, so the code cannot
// drift from the build.
val generateApplicationVersion by tasks.registering {
    val applicationVersion = project.version.toString()
    val output = layout.buildDirectory.dir("generated/applicationVersion/kotlin")
    inputs.property("version", applicationVersion)
    outputs.dir(output)
    doLast {
        val file = output.get().file("dev/pnptracker/ApplicationVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package dev.pnptracker\n\n" +
                "/** Generated from the Gradle project version; do not edit. */\n" +
                "internal const val APPLICATION_VERSION: String = \"$applicationVersion\"\n",
        )
    }
}

kotlin {
    jvmToolchain(21)

    jvm("desktop")

    sourceSets {
        commonMain {
            kotlin.srcDir(generateApplicationVersion)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(libs.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
            // The backup document is a common concern: PLAN 14.4.1 has one JSON
            // contract, written and read by shared code, so the library sits here
            // rather than in the desktop source set.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.sqlite.bundled)
                // Reading .xlsx is a JVM concern; the snapshot model it produces is common.
                implementation(libs.poi.ooxml)
            }
        }
    }
}

dependencies {
    // Only the desktop target is generated for; the general `ksp(...)` configuration is deprecated.
    add("kspDesktop", libs.room.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.pnptracker.resources"
}

compose.desktop {
    application {
        mainClass = "dev.pnptracker.MainKt"

        // The self-contained Linux application (PLAN 18, Faz 3 / İş 11): jpackage's
        // application image with a jlink runtime of only the modules below.
        nativeDistributions {
            packageName = "pnp-tracker"
            packageVersion = project.version.toString()
            description = "PnP Üretim Takipçisi"
            // Compose adds java.base, java.desktop, java.logging and jdk.crypto.ec
            // itself; these are what `suggestRuntimeModules` (jdeps) found beyond
            // them. The package check runs the real features on this runtime.
            modules("java.instrument", "java.security.jgss", "java.xml.crypto", "jdk.unsupported")
            linux {
                iconFile.set(rootProject.file("packaging/linux/pnp-tracker.png"))
            }
        }
    }
}

// A real window on this desktop: the application is started with temporary XDG
// folders and its one main window is closed by the test sources' verified closer.
// It needs a display and opens a window, so it is never part of `check`.
tasks.register<JavaExec>("desktopWindowSmoke") {
    group = "verification"
    description = "Opens the application with temporary folders and closes its own window, and only that one."
    val desktopTest = kotlin.jvm("desktop").compilations.getByName("test")
    classpath = files(desktopTest.output.allOutputs, desktopTest.runtimeDependencyFiles)
    mainClass = "dev.pnptracker.platform.desktop.DesktopWindowSmokeKt"
    // `-PsmokeScenario=damaged-database` starts it on a damaged database (PLAN 14.7.4).
    providers.gradleProperty("smokeScenario").orNull?.let { args(it) }
}

ktlint {
    filter {
        // Generated sources (Compose resources, Room and KSP output) are not ours to format.
        exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
    }
}

// ---------------------------------------------------------------- Linux archive

// The machine's architecture as Linux names it (uname -m), which is what the
// archive and the Arch package say.
val linuxArch: String =
    when (val arch = System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> error("No Linux package for the architecture $arch")
    }
val linuxPackageName = "pnp-tracker-${project.version}"

// The application image jpackage made, as the archive will hold it: one top
// directory, a README and the version, and the one file Compose writes with the
// build's clock normalised (see normaliseRepackedJars).
val stageLinuxApplication by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the self-contained Linux application directory for the archive."
    val distributable = tasks.named("createDistributable")
    dependsOn(distributable)
    val version = project.version.toString()
    val arch = linuxArch
    from(layout.buildDirectory.dir("compose/binaries/main/app/pnp-tracker"))
    from(rootProject.file("packaging/linux/README.txt"))
    // The application's own license travels with the application (Faz 3 / İş 15).
    from(rootProject.file("LICENSE"))
    into(layout.buildDirectory.dir("linux/stage/$linuxPackageName"))
    // jlink leaves the runtime's legal notices read-only; a second staging must
    // still be able to replace them.
    doFirst {
        destinationDir.walkTopDown().forEach { it.setWritable(true, true) }
    }
    doLast {
        val root = destinationDir
        root.resolve("VERSION").writeText("name=pnp-tracker\nversion=$version\narch=$arch\n")
        normaliseRepackedJars(root.resolve("lib/app"))
        writeThirdPartyNotices(root)
    }
}

/**
 * Compose takes the Skia native library out of skiko's runtime jar and writes the
 * rest back with java.util.zip at the build's clock (FileUtils.transformJar) —
 * the only file of the image two builds disagree on, and its name carries its
 * digest, so the launcher's .cfg disagrees too. The jar holds nothing but its
 * manifest. It is written again here with the same entries and bytes at a fixed
 * time, named by its digest, and the one .cfg line naming it follows.
 */
fun normaliseRepackedJars(appDirectory: File) {
    val cfg = appDirectory.resolve("pnp-tracker.cfg")
    appDirectory.listFiles { file -> file.name.startsWith("skiko-awt-runtime-") && file.name.endsWith(".jar") }!!.forEach { jar ->
        val entries =
            ZipFile(jar).use { zip ->
                zip.entries().toList().map { it.name to zip.getInputStream(it).readBytes() }
            }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, content) ->
                val entry = ZipEntry(name)
                entry.setTimeLocal(LocalDateTime.of(1980, 2, 1, 0, 0))
                out.putNextEntry(entry)
                out.write(content)
                out.closeEntry()
            }
        }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)
        val stem = jar.name.substringBeforeLast('-')
        val renamed = appDirectory.resolve("$stem-$digest.jar")
        jar.delete()
        renamed.writeBytes(bytes.toByteArray())
        cfg.writeText(cfg.readText().replace("\$APPDIR/${jar.name}\n", "\$APPDIR/${renamed.name}\n"))
        check(renamed.name in cfg.readText() && jar.name !in cfg.readText()) { "the launcher does not name ${renamed.name}" }
        val again =
            ZipFile(renamed).use { zip ->
                zip.entries().toList().map { it.name to zip.getInputStream(it).readBytes() }
            }
        check(again.map { it.first } == entries.map { it.first } && again.zip(entries).all { (a, b) -> a.second.contentEquals(b.second) }) {
            "normalising ${jar.name} changed what it holds"
        }
    }
}

/**
 * The notices for everything the package carries beside our own code, taken from
 * the package itself (Faz 3 / İş 15): the embedded runtime's `release` file and
 * its `legal/` tree, and every jar under `lib/app`, with the licence and notice
 * files those jars really carry copied out beside this document. Nothing is
 * guessed — an artifact that says nothing about its licence is written down as
 * saying nothing, and no licence of a third party is presented as ours.
 */
fun writeThirdPartyNotices(application: File) {
    val ownJar = "app-desktop-${project.version}"
    val release =
        application
            .resolve("lib/runtime/release")
            .readLines()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { (key, value) -> key to value.trim('"') }
    val modules = application.resolve("lib/runtime/legal").listFiles()!!.map { it.name }.sorted()

    val texts = application.resolve("third-party")
    texts.deleteRecursively()
    val licenceEntry = Regex("^META-INF/(LICENSE|LICENCE|NOTICE|COPYING)([-._].*)?$", RegexOption.IGNORE_CASE)
    val digestSuffix = Regex("-[0-9a-f]{16,40}$")

    val rows =
        application
            .resolve("lib/app")
            .listFiles { file -> file.name.endsWith(".jar") }!!
            .map { jar ->
                val stem = digestSuffix.replace(jar.name.removeSuffix(".jar"), "")
                val name = stem.substringBeforeLast('-')
                val version = stem.substringAfterLast('-')
                Triple(name, version, jar)
            }.filterNot { (name, version, _) -> "$name-$version" == ownJar }
            .sortedBy { (name, version, _) -> "$name $version" }
            .map { (name, version, jar) ->
                var declared = "—"
                var carried = "—"
                ZipFile(jar).use { zip ->
                    zip.getEntry("META-INF/MANIFEST.MF")?.let { entry ->
                        val manifest =
                            zip
                                .getInputStream(entry)
                                .readBytes()
                                .decodeToString()
                                .replace("\r\n", "\n")
                                .replace("\n ", "")
                        manifest
                            .lineSequence()
                            .firstOrNull { it.startsWith("Bundle-License:") }
                            ?.let { declared = it.removePrefix("Bundle-License:").trim().replace("|", "/") }
                    }
                    val files = zip.entries().toList().filter { !it.isDirectory && licenceEntry.matches(it.name) }
                    if (files.isNotEmpty()) {
                        val directory = texts.resolve("$name-$version").apply { mkdirs() }
                        files.sortedBy { it.name }.forEach { entry ->
                            directory.resolve(entry.name.substringAfterLast('/')).writeBytes(zip.getInputStream(entry).readBytes())
                        }
                        carried = "`third-party/$name-$version/`"
                    }
                }
                "| $name | $version | $declared | $carried |"
            }

    application.resolve("THIRD_PARTY_NOTICES.md").writeText(
        buildString {
            appendLine("# Üçüncü taraf bildirimleri — PnP Üretim Takipçisi")
            appendLine()
            appendLine("Bu dosya paketin kendi içeriğinden üretilir (`:app:stageLinuxApplication`).")
            appendLine("Uygulamanın kendi kaynak kodu MIT lisanslıdır; yanındaki `LICENSE` dosyasına")
            appendLine("bakın. Aşağıdakiler uygulamayla birlikte dağıtılan **başka** projelerdir ve")
            appendLine("kendi lisanslarıyla gelirler; MIT lisansı onları kapsamaz.")
            appendLine()
            appendLine("## Java çalışma ortamı")
            appendLine()
            appendLine("```text")
            listOf("IMPLEMENTOR", "IMPLEMENTOR_VERSION", "JAVA_VERSION", "JAVA_VERSION_DATE", "OS_ARCH").forEach { key ->
                release[key]?.let { appendLine("$key=$it") }
            }
            appendLine("```")
            appendLine()
            val base = application.resolve("lib/runtime/legal/java.base")
            base.resolve("LICENSE").takeIf { it.isFile }?.let { licence ->
                appendLine("Çalışma ortamının kendi lisansı, paketteki `lib/runtime/legal/java.base/LICENSE`")
                appendLine("dosyasının ilk satırıyla: **${licence.readLines().first { it.isNotBlank() }.trim()}**.")
                if (base.resolve("ASSEMBLY_EXCEPTION").isFile) {
                    appendLine("Yanında `ASSEMBLY_EXCEPTION` dosyası da dağıtılır (Classpath istisnası).")
                }
                appendLine()
            }
            appendLine("Çalışma ortamı `jlink` ile ${modules.size} modüle indirilmiştir. Bu modüllerin")
            appendLine("kendi lisans ve bildirim metinleri paketin içinde, `lib/runtime/legal/<modül>/`")
            appendLine("altında dağıtılır:")
            appendLine()
            appendLine("```text")
            modules.forEach { appendLine(it) }
            appendLine("```")
            appendLine()
            appendLine("## Kütüphaneler")
            appendLine()
            appendLine("| Bileşen | Sürüm | Artefaktın bildirdiği lisans | Pakete giren lisans metni |")
            appendLine("| --- | --- | --- | --- |")
            rows.forEach { appendLine(it) }
            appendLine()
            appendLine("Tablodaki bazı artefaktlar kendi içinde ne lisans adı ne de lisans metni taşır;")
            appendLine("bu dosya onların lisansını **tahmin etmez**. Bu bileşenlerin lisansı kendi proje")
            appendLine("sayfalarında ve Maven POM dosyalarında yayımlanır.")
        },
    )
}

// `pnp-tracker-<version>-linux-<arch>.tar.gz`: one top directory, permissions as
// jpackage left them, entries in a fixed order at a fixed time (Faz 3 / İş 11).
val packageLinuxArchive by tasks.registering(Tar::class) {
    group = "distribution"
    description = "Packages the self-contained Linux application as a reproducible tar.gz."
    compression = Compression.GZIP
    archiveFileName.set("$linuxPackageName-linux-$linuxArch.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("linux/dist"))
    from(stageLinuxApplication) { into(linuxPackageName) }
}

// The runtime probe the package check loads into the package's own JVM as an
// agent: test code, never shipped.
val linuxPackageProbeJar by tasks.registering(Jar::class) {
    val desktopTest = kotlin.jvm("desktop").compilations.getByName("test")
    dependsOn(desktopTest.compileTaskProvider)
    from(desktopTest.output.classesDirs) { include("dev/pnptracker/packaging/PackageRuntimeProbe*") }
    archiveFileName.set("package-runtime-probe.jar")
    destinationDirectory.set(layout.buildDirectory.dir("linux/check"))
    manifest { attributes("Premain-Class" to "dev.pnptracker.packaging.PackageRuntimeProbe") }
}

// The archive's contract, checked on the archive itself outside the repository,
// and its real launcher run twice — the features on the embedded runtime, then
// the window, closed by SafeWindowCloser. Needs a display; never part of `check`.
tasks.register<JavaExec>("verifyLinuxPackage") {
    group = "verification"
    description = "Checks the self-contained Linux archive and runs it from a temporary directory."
    val desktopTest = kotlin.jvm("desktop").compilations.getByName("test")
    dependsOn(packageLinuxArchive, linuxPackageProbeJar)
    classpath = files(desktopTest.output.allOutputs, desktopTest.runtimeDependencyFiles)
    mainClass = "dev.pnptracker.packaging.LinuxPackageCheckKt"
    val archive = packageLinuxArchive.flatMap { it.archiveFile }
    val probe = linuxPackageProbeJar.flatMap { it.archiveFile }
    val version = project.version.toString()
    val arch = linuxArch
    val sample = file("src/desktopTest/resources/sample-import.xlsx")
    val repository = rootProject.projectDir
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "archive",
                archive.get().asFile.absolutePath,
                version,
                arch,
                probe.get().asFile.absolutePath,
                sample.absolutePath,
                repository.absolutePath,
            )
        },
    )
}

/** The digest the PKGBUILD, the checksum file and the release notes all quote. */
fun sha256Of(file: File): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

// ---------------------------------------------------------------- Arch package

// `pnp-tracker-<version>-1-<arch>.pkg.tar.zst` (Faz 3 / İş 12): makepkg over the
// PKGBUILD template, packaging the very archive `packageLinuxArchive` made — no
// second build of the application. makepkg runs in a directory of its own under
// the system temporary directory, with a HOME of its own, so neither this
// repository's path nor the user's makepkg settings reach the package, and it
// is never allowed near pacman: --nodeps, nothing is installed.
val packageArch by tasks.registering {
    group = "distribution"
    description = "Builds the Garuda/Arch Linux package from the self-contained archive."
    dependsOn(packageLinuxArchive)
    val archive = packageLinuxArchive.flatMap { it.archiveFile }
    val template = rootProject.file("packaging/arch/PKGBUILD")
    val desktop = rootProject.file("packaging/arch/pnp-tracker.desktop")
    val version = project.version.toString()
    val arch = linuxArch
    val output = layout.buildDirectory.dir("arch")
    inputs.file(archive)
    inputs.files(template, desktop)
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val archiveFile = archive.get().asFile
        val pkgbuild =
            template
                .readText()
                .replace("@PKGVER@", version)
                .replace("@ARCH@", arch)
                .replace("@ARCHIVE@", archiveFile.name)
                .replace("@ARCHIVE_SHA256@", sha256Of(archiveFile))
                .replace("@DESKTOP_SHA256@", sha256Of(desktop))
        check(!Regex("@[A-Z0-9_]+@").containsMatchIn(pkgbuild)) { "the PKGBUILD template has a value nobody filled in" }

        val work = File(System.getProperty("java.io.tmpdir"), "pnp-tracker-makepkg")
        val marker = work.resolve(".made-by-pnp-tracker-build")
        if (work.exists()) {
            check(marker.isFile) { "$work exists and was not made by this build; it is left alone" }
            work.walkTopDown().forEach { it.setWritable(true, true) }
            work.deleteRecursively()
        }
        try {
            val start = work.resolve("start").apply { mkdirs() }
            marker.writeText("")
            start.resolve("PKGBUILD").writeText(pkgbuild)
            archiveFile.copyTo(start.resolve(archiveFile.name))
            desktop.copyTo(start.resolve(desktop.name))
            val home = work.resolve("home").apply { mkdirs() }
            val built = work.resolve("out").apply { mkdirs() }
            val process =
                ProcessBuilder("makepkg", "--nodeps", "--noconfirm", "--nosign", "--noprogressbar", "--force", "--clean")
                    .directory(start)
                    .redirectErrorStream(true)
                    .also { builder ->
                        val environment = builder.environment()
                        environment.clear()
                        environment["PATH"] = "/usr/bin:/bin"
                        environment["HOME"] = home.path
                        environment["XDG_CONFIG_HOME"] = home.resolve(".config").path
                        environment["LANG"] = "C.UTF-8"
                        environment["PKGDEST"] = built.path
                        environment["SRCDEST"] = start.path
                        environment["BUILDDIR"] = work.resolve("build").path
                        environment["PACKAGER"] = "pnp-tracker local build <build@pnp-tracker.invalid>"
                        environment["SOURCE_DATE_EPOCH"] = "0"
                    }.start()
            val log = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "makepkg failed:\n$log" }
            logger.lifecycle(log.lines().filter { it.startsWith("==>") }.joinToString("\n"))
            val destination = output.get().asFile
            destination.deleteRecursively()
            destination.resolve("dist").mkdirs()
            destination.resolve("PKGBUILD").writeText(pkgbuild)
            val made = built.listFiles()!!.single { it.name.endsWith(".pkg.tar.zst") }
            check(made.name == "pnp-tracker-$version-1-$arch.pkg.tar.zst") { "makepkg made ${made.name}" }
            made.copyTo(destination.resolve("dist/${made.name}"))
        } finally {
            if (marker.isFile) {
                work.walkTopDown().forEach { it.setWritable(true, true) }
                work.deleteRecursively()
            }
        }
    }
}

// The Arch package's contract: its four places, its metadata, the version, the
// desktop entry, no path of this machine; then installed into a temporary root,
// /usr/bin/pnp-tracker run as in verifyLinuxPackage, and its files removed as
// pacman would without touching the user's areas. Needs a display.
tasks.register<JavaExec>("verifyArchPackage") {
    group = "verification"
    description = "Checks the Arch package and runs it from a temporary root."
    val desktopTest = kotlin.jvm("desktop").compilations.getByName("test")
    dependsOn(packageArch, linuxPackageProbeJar)
    classpath = files(desktopTest.output.allOutputs, desktopTest.runtimeDependencyFiles)
    mainClass = "dev.pnptracker.packaging.LinuxPackageCheckKt"
    val version = project.version.toString()
    val arch = linuxArch
    val packageFile = layout.buildDirectory.file("arch/dist/pnp-tracker-$version-1-$arch.pkg.tar.zst")
    val probe = linuxPackageProbeJar.flatMap { it.archiveFile }
    val sample = file("src/desktopTest/resources/sample-import.xlsx")
    val repository = rootProject.projectDir
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "arch",
                packageFile.get().asFile.absolutePath,
                version,
                arch,
                probe.get().asFile.absolutePath,
                sample.absolutePath,
                repository.absolutePath,
            )
        },
    )
}

// ---------------------------------------------------------------- release

// Faz 3 / İş 16: what a tagged release is made of. The release workflow calls
// these two tasks and nothing else, so the rules live here, in the build, and
// not in a second copy written in shell.

// A tag may only publish the version the build says it is. `project.version` is
// the one version source (master §33 R5), so `v<version>` is the one tag that
// may go on: anything else stops here, before a package is made or a release is
// created.
tasks.register("checkReleaseTag") {
    group = "distribution"
    description = "Fails unless -PreleaseTag is exactly v<project.version>."
    val expected = "v${project.version}"
    val given = providers.gradleProperty("releaseTag")
    doLast {
        val tag = given.orNull
        check(!tag.isNullOrBlank()) { "no tag was given: pass -PreleaseTag=$expected" }
        check(tag == expected) {
            "the tag $tag does not match the version this build makes; the only tag that may publish it is $expected"
        }
        logger.lifecycle("release tag $tag matches the project version")
    }
}

// The files a release is made of, in one directory: the two packages, one
// SHA256SUMS over them both (`sha256sum -c SHA256SUMS` in that directory), the
// licence, the notices and the user documents. Nothing is signed — there is no
// key — so the checksums are what a download is checked against.
val packageRelease by tasks.registering {
    group = "distribution"
    description = "Collects the release artifacts and writes SHA256SUMS over the two packages."
    dependsOn(packageLinuxArchive, packageArch)
    val archive = packageLinuxArchive.flatMap { it.archiveFile }
    val archPackage = layout.buildDirectory.file("arch/dist/pnp-tracker-${project.version}-1-$linuxArch.pkg.tar.zst")
    val notices = layout.buildDirectory.file("linux/stage/$linuxPackageName/THIRD_PARTY_NOTICES.md")
    val documents =
        listOf(
            rootProject.file("LICENSE"),
            rootProject.file("docs/kullanim-kilavuzu.md"),
            rootProject.file("docs/ornek-ice-aktarma.md"),
            rootProject.file("docs/ornek-ice-aktarma.csv"),
        )
    val output = layout.buildDirectory.dir("release")
    inputs.files(archive, archPackage, notices)
    inputs.files(documents)
    outputs.dir(output)
    doLast {
        val destination = output.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()
        val packages = listOf(archive.get().asFile, archPackage.get().asFile).sortedBy { it.name }
        packages.forEach { it.copyTo(destination.resolve(it.name)) }
        (documents + notices.get().asFile).forEach { it.copyTo(destination.resolve(it.name)) }
        destination.resolve("SHA256SUMS").writeText(packages.joinToString("") { "${sha256Of(it)}  ${it.name}\n" })
        destination.listFiles()!!.sortedBy { it.name }.forEach { logger.lifecycle("release: ${it.name} (${it.length()} bytes)") }
    }
}
