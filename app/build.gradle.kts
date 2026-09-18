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
version = "0.1.0"

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
