package dev.pnptracker.packaging

import dev.pnptracker.platform.desktop.Commands
import dev.pnptracker.platform.desktop.SystemCommands
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/*
 * Whether the Arch package declares everything its own files load (Faz 3, after
 * the 0.1.1 defect).
 *
 * The package carries a Java runtime, and which system libraries that runtime
 * needs depends on the JDK it was built with: Temurin bundles freetype,
 * harfbuzz, libjpeg, lcms and giflib inside the image, Arch's jdk21-openjdk
 * links the system ones instead. The 0.1.1 package was built by the second and
 * shipped a `depends` list derived from the first, so four libraries were never
 * declared. Nothing noticed, because every machine that built or checked it
 * happened to have them.
 *
 * That last sentence is the trap this guards against, so being installed here is
 * never an answer. A library counts as declared only when the package that owns
 * it is inside the transitive closure of what the recipe actually declares; a
 * package that merely happens to be installed on the build machine resolves
 * nothing.
 *
 * Only `DT_NEEDED` is checked, because that is what `depends` is for: a library
 * the application reaches through `dlopen` — the GL driver behind libglvnd, the
 * GTK file dialog — is not linked and is not declared, and those are covered by
 * running the real application in the package smokes instead.
 */

/** One `DT_NEEDED` entry: [elf] is the file inside the package, [soname] what it asks for. */
data class NeededLibrary(
    val elf: String,
    val soname: String,
)

/** Where a needed library comes from. */
sealed interface Provider {
    /** The package ships it itself. */
    data class Bundled(
        val path: String,
    ) : Provider

    /** The C library and the dynamic loader, which every ELF on the system needs. */
    data class BaseSystem(
        val owner: String,
    ) : Provider

    /** An Arch package the recipe declares, or one those declarations pull in. */
    data class Declared(
        val owner: String,
        val direct: Boolean,
    ) : Provider

    /**
     * Nothing declared provides it. [owner] is the package that owns it on this
     * machine, when there is one — which makes the finding actionable, and is
     * never itself a reason to pass.
     */
    data class Undeclared(
        val owner: String?,
    ) : Provider
}

data class Resolution(
    val needed: NeededLibrary,
    val provider: Provider,
)

/** What the check found, in the order a person would want to read it. */
class DependencyReport(
    val resolutions: List<Resolution>,
) {
    val undeclared: List<Resolution> get() = resolutions.filter { it.provider is Provider.Undeclared }

    /** One line per library, saying who needs it and who provides it. */
    fun lines(): List<String> =
        resolutions
            .groupBy { it.needed.soname }
            .toSortedMap()
            .map { (soname, uses) ->
                val who = uses.map { it.needed.elf }.distinct().sorted()
                val from =
                    when (val provider = uses.first().provider) {
                        is Provider.Bundled -> "pakette: ${provider.path}"
                        is Provider.BaseSystem -> "temel sistem: ${provider.owner}"
                        is Provider.Declared ->
                            if (provider.direct) "depends: ${provider.owner}" else "geçişli: ${provider.owner}"
                        is Provider.Undeclared ->
                            "BİLDİRİLMEMİŞ" +
                                (
                                    provider.owner?.let { " (bu makinede $it paketinde)" }
                                        ?: " (hiçbir pakette bulunamadı)"
                                )
                    }
                "$soname ← ${who.joinToString(", ")} — $from"
            }
}

/** The packages Arch treats as the C library and the loader; every ELF needs them. */
private val BASE_SYSTEM = setOf("glibc", "linux-api-headers", "filesystem")

/**
 * Decides where every needed library comes from.
 *
 * Nothing about the machine running this is consulted: [bundled] is what the
 * package itself carries, [declaredClosure] is what the recipe declares plus
 * what those declarations pull in, and [ownerOfSoname] only puts a name on a
 * library that turned out not to be declared.
 */
fun resolveDependencies(
    needed: List<NeededLibrary>,
    bundled: Map<String, String>,
    declared: Set<String>,
    declaredClosure: Set<String>,
    ownerOfSoname: (String) -> String?,
): DependencyReport =
    DependencyReport(
        needed.map { library ->
            val bundledPath = bundled[library.soname]
            val owner = ownerOfSoname(library.soname)
            val provider =
                when {
                    bundledPath != null -> Provider.Bundled(bundledPath)
                    owner != null && owner in BASE_SYSTEM && owner in declaredClosure -> Provider.BaseSystem(owner)
                    owner != null && owner in declaredClosure -> Provider.Declared(owner, direct = owner in declared)
                    else -> Provider.Undeclared(owner)
                }
            Resolution(library, provider)
        },
    )

/** Every ELF file under [root], found by its magic bytes rather than its name. */
fun elfFilesUnder(root: Path): List<Path> {
    val magic = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    return Files
        .walk(root)
        .use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList() }
        .filter { file ->
            runCatching {
                Files.newInputStream(file).use { stream ->
                    val head = ByteArray(4)
                    stream.readNBytes(head, 0, 4) == 4 && head.contentEquals(magic)
                }
            }.getOrDefault(false)
        }.sorted()
}

/** The `DT_NEEDED` entries of one ELF file, as `readelf` prints them. */
fun neededLibrariesOf(
    file: Path,
    commands: Commands = SystemCommands,
): List<String> {
    val result = commands.run(listOf("readelf", "--dynamic", "--wide", file.toString()))
    if (result.exitCode != 0) return emptyList()
    return NEEDED_LINE
        .findAll(result.output)
        .map { it.groupValues[1] }
        .toList()
}

/**
 * `readelf` is translated, so the words between the tag and the name are
 * whatever language the machine speaks — "Shared library" here, "Paylaşımlı
 * kitaplık" on the machine this was written on. Only the tag and the brackets
 * are the same everywhere, so only those are matched.
 */
private val NEEDED_LINE = Regex("""\(NEEDED\)[^\[\n]*\[([^\]]+)]""")

/**
 * Why a reading of the package is not to be believed, if it is not.
 *
 * A check that cannot fail is worse than no check: the first version of this one
 * read `readelf` output in English only, found nothing on a Turkish machine, and
 * reported a clean package. Every ELF file here links the C library, so a
 * package whose files ask for nothing means the reader is broken.
 */
fun credibilityProblem(
    elfFiles: Int,
    needed: List<NeededLibrary>,
): String? =
    when {
        elfFiles == 0 -> "the package holds no ELF file at all"
        needed.isEmpty() -> "$elfFiles ELF files and not one DT_NEEDED entry: the reader found nothing"
        needed.none { it.soname.startsWith("libc.so") } ->
            "no file in the package asks for the C library, which cannot be true: the reader is not reading"
        else -> null
    }

/** The sonames the package carries itself, mapped to where they sit inside it. */
fun bundledSonames(
    root: Path,
    elfFiles: List<Path>,
): Map<String, String> = elfFiles.associate { file -> file.fileName.toString() to root.relativize(file).toString() }

/**
 * Every package the recipe's [depends] pull in, walked through Arch's own
 * metadata. A package that cannot be asked about is left out rather than
 * assumed: an answer nobody gave must not resolve anything.
 */
fun transitiveDependencies(
    depends: List<String>,
    commands: Commands = SystemCommands,
): Set<String> {
    val seen = mutableSetOf<String>()
    val waiting = ArrayDeque(depends)
    while (waiting.isNotEmpty()) {
        val name = waiting.removeFirst()
        if (!seen.add(name)) continue
        val result = commands.run(listOf("pacman", "-Qi", name))
        if (result.exitCode != 0) continue
        dependsOf(result.output).forEach { if (it !in seen) waiting.addLast(it) }
    }
    return seen
}

/** The `Depends On` field of `pacman -Qi`, in whatever language pacman is speaking. */
internal fun dependsOf(description: String): List<String> {
    val line =
        description
            .lines()
            .firstOrNull { it.substringBefore(':').trim().let { field -> field == "Depends On" || field.startsWith("Bağımlılık") } }
            ?: return emptyList()
    val value = line.substringAfter(':').trim()
    if (value.isEmpty() || value == "None" || value == "Yok") return emptyList()
    return value
        .split(Regex("\\s+"))
        .map { it.split('=', '>', '<').first() }
        .filter { it.isNotEmpty() }
}

/** The package that owns [soname] on this machine, asked only to name a library that is not declared. */
fun ownerOfSoname(
    soname: String,
    commands: Commands = SystemCommands,
): String? {
    listOf("/usr/lib/$soname", "/usr/lib64/$soname", "/lib/$soname").forEach { candidate ->
        val result = commands.run(listOf("pacman", "-Qoq", candidate))
        if (result.exitCode == 0) {
            val owner =
                result.output
                    .trim()
                    .lines()
                    .firstOrNull()
                    ?.trim()
            if (!owner.isNullOrEmpty()) return owner
        }
    }
    return null
}

/** The `depend` entries of a package's `.PKGINFO`. */
fun declaredDependsOf(pkginfo: String): List<String> =
    pkginfo
        .lines()
        .mapNotNull { line -> line.substringAfter("depend = ", "").takeIf { it.isNotEmpty() && line.startsWith("depend = ") } }
        .map { it.split('=', '>', '<').first().trim() }
