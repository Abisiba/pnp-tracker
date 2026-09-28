package dev.pnptracker.packaging

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Checks the Windows installer and the application image it carries (PLAN 14.8.6).
 *
 * Runs as a program rather than a test because it needs the packaging tasks to
 * have run, and `check` may not depend on those: they need a Windows host and
 * they take minutes. It says `PACKAGE: PASSED` and exits 0, or prints what it
 * found and exits 1. It reads; it installs nothing and removes nothing.
 *
 * What it deliberately does not do is claim anything about a real installation.
 * Whether the installer actually installs per user, makes a Start menu entry and
 * leaves the user's data behind when it is removed is proved by running it, and
 * that is a separate step which says so when it has not happened.
 */
fun main(arguments: Array<String>) {
    require(arguments.size == 4) {
        "usage: WindowsPackageCheck <installer> <application image> <version> <repository>"
    }
    val installer = File(arguments[0])
    val image = File(arguments[1])
    val version = arguments[2]
    val repository = File(arguments[3])

    val problems = mutableListOf<String>()

    fun expect(
        condition: Boolean,
        what: String,
    ) {
        if (!condition) problems += what
    }

    // ---------------------------------------------------------------- the installer
    expect(installer.isFile, "the installer is not a file: ${installer.name}")
    if (installer.isFile) {
        expect(installer.name == "pnp-tracker-$version-windows-x86_64.exe", "the installer is named ${installer.name}")
        // A self-contained installer carries a runtime and every jar; anything of
        // a few megabytes would mean jpackage produced a stub.
        expect(installer.length() > 40L * 1024 * 1024, "the installer is only ${installer.length()} bytes")
        val head = installer.inputStream().use { it.readNBytes(2) }
        expect(head.contentEquals(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte())), "the installer is not a Windows executable")
    }

    // ------------------------------------------------------- the application image
    val runtime = image.resolve("runtime")
    val applicationJars = image.resolve("app")
    expect(image.resolve("pnp-tracker.exe").isFile, "the launcher pnp-tracker.exe is missing")
    expect(runtime.isDirectory, "the embedded runtime is missing")
    expect(applicationJars.isDirectory, "the application's jars are missing")
    expect(image.resolve("LICENSE").isFile, "LICENSE is missing from the installed application")
    expect(image.resolve("THIRD_PARTY_NOTICES.md").isFile, "THIRD_PARTY_NOTICES.md is missing from the installed application")
    expect(image.resolve("README.txt").isFile, "README.txt is missing from the installed application")

    val licence = repository.resolve("LICENSE")
    if (licence.isFile && image.resolve("LICENSE").isFile) {
        expect(image.resolve("LICENSE").readBytes().contentEquals(licence.readBytes()), "the installed LICENSE is not the repository's")
    }

    val versionFile = image.resolve("VERSION")
    expect(versionFile.isFile, "VERSION is missing")
    if (versionFile.isFile) {
        val said = versionFile.readText()
        expect("version=$version" in said, "VERSION does not say $version")
        expect("arch=x86_64" in said, "VERSION does not say the architecture")
    }

    // The runtime is the application's own, and it is a real one: jlink writes a
    // `release` file, and the virtual machine itself is inside the image.
    val release = runtime.resolve("release")
    expect(release.isFile, "the runtime has no release file")
    if (release.isFile) {
        expect("JAVA_VERSION" in release.readText(), "the runtime's release file names no Java version")
    }
    val virtualMachine = runtime.walkTopDown().firstOrNull { it.name == "jvm.dll" }
    expect(virtualMachine != null, "jvm.dll is not inside the package")
    // A runtime meant to be shipped, not a whole JDK.
    listOf("jdk.compiler", "jdk.jlink", "jdk.javadoc").forEach { tool ->
        expect(!runtime.resolve("legal/$tool").exists(), "the runtime carries the tool module $tool")
    }

    // ------------------------------------------------------- no system Java at all
    val configuration = applicationJars.resolve("pnp-tracker.cfg")
    expect(configuration.isFile, "pnp-tracker.cfg is missing")
    if (configuration.isFile) {
        val text = configuration.readText()
        listOf("JAVA_HOME", "Program Files\\Java", "/usr/lib/jvm", "app.runtime").forEach { forbidden ->
            expect(forbidden !in text, "pnp-tracker.cfg names $forbidden")
        }
        // Only $APPDIR, the way the Linux check asks it: an absolute path in here
        // would mean the package only runs on the machine that built it.
        expect(!Regex("""[A-Za-z]:\\""").containsMatchIn(text), "pnp-tracker.cfg holds an absolute path")
    }
    expect(
        image.walkTopDown().none { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".class")) },
        "the package carries source or loose class files",
    )

    // ------------------------------------------------------------- nothing personal
    // PLAN 14.8.6: neither the image's text nor the installer's bytes may carry the
    // name of whoever built it or the path they built it in. Windows writes some
    // strings as UTF-16, so both encodings are looked for.
    val secrets =
        listOfNotNull(
            repository.absolutePath,
            System.getProperty("user.name")?.takeIf { it.length > 2 },
            "\\.gradle\\caches",
            ".gradle/caches",
        )
    val textOfImage =
        image
            .walkTopDown()
            .filter { it.isFile && (it.extension in setOf("cfg", "txt", "md", "xml", "json") || it.name == "VERSION") }
            .joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
    secrets.forEach { secret ->
        expect(secret !in textOfImage, "the application image's text carries `$secret`")
    }
    if (installer.isFile) {
        val bytes = installer.readBytes()
        secrets.forEach { secret ->
            expect(!bytes.contains(secret.toByteArray(StandardCharsets.US_ASCII)), "the installer carries `$secret`")
            expect(!bytes.contains(secret.toByteArray(StandardCharsets.UTF_16LE)), "the installer carries `$secret` as UTF-16")
        }
    }

    if (problems.isEmpty()) {
        println("PACKAGE: installer ${installer.name}, ${installer.length()} bytes")
        val said = if (release.isFile) release.readLines() else emptyList()
        val java = said.firstOrNull { it.startsWith("JAVA_VERSION") } ?: "JAVA_VERSION=?"
        println("PACKAGE: runtime $java, virtual machine ${virtualMachine?.name ?: "?"}")
        val legal = runtime.resolve("legal").list()
        val modules = legal?.sorted() ?: emptyList()
        println("PACKAGE: runtime modules ${modules.size}: ${modules.joinToString(" ")}")
        println("PACKAGE: PASSED")
    } else {
        problems.forEach { println("PACKAGE: $it") }
        println("PACKAGE: FAILED (${problems.size})")
        kotlin.system.exitProcess(1)
    }
}

/** Whether [pattern] appears anywhere in this array. */
private fun ByteArray.contains(pattern: ByteArray): Boolean {
    if (pattern.isEmpty() || pattern.size > size) return false
    outer@ for (start in 0..size - pattern.size) {
        for (offset in pattern.indices) {
            if (this[start + offset] != pattern[offset]) continue@outer
        }
        return true
    }
    return false
}
