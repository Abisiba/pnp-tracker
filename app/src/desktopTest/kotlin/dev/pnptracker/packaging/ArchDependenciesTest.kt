package dev.pnptracker.packaging

import dev.pnptracker.platform.desktop.CommandResult
import dev.pnptracker.platform.desktop.Commands
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule that the 0.1.1 package broke, written down as a test.
 *
 * The package declared nine dependencies and linked four more: harfbuzz,
 * libjpeg-turbo, lcms2 and giflib, which Arch's JDK links and Temurin bundles.
 * Nothing caught it because every machine that built or checked the package had
 * them installed. So the resolution here never asks what is installed: it asks
 * what the recipe declares, and what Arch's own metadata says those declarations
 * pull in.
 */
class ArchDependenciesTest {
    /** What the runtime the release container builds really asks for. */
    private val archJdkNeeds =
        listOf(
            NeededLibrary("opt/pnp-tracker/lib/runtime/lib/libfontmanager.so", "libfreetype.so.6"),
            NeededLibrary("opt/pnp-tracker/lib/runtime/lib/libfontmanager.so", "libharfbuzz.so.0"),
            NeededLibrary("opt/pnp-tracker/lib/runtime/lib/libjavajpeg.so", "libjpeg.so.8"),
            NeededLibrary("opt/pnp-tracker/lib/runtime/lib/liblcms.so", "liblcms2.so.2"),
            NeededLibrary("opt/pnp-tracker/lib/runtime/lib/libsplashscreen.so", "libgif.so.7"),
            NeededLibrary("opt/pnp-tracker/lib/runtime/lib/libjava.so", "libc.so.6"),
            NeededLibrary("opt/pnp-tracker/lib/app/libskiko-linux-x64.so", "libGL.so.1"),
            NeededLibrary("opt/pnp-tracker/lib/runtime/lib/server/libjvm.so", "libstdc++.so.6"),
            NeededLibrary("opt/pnp-tracker/bin/pnp-tracker", "libapplauncher.so"),
        )

    /** Who owns what, as pacman would answer on any Arch machine. */
    private val owners =
        mapOf(
            "libfreetype.so.6" to "freetype2",
            "libharfbuzz.so.0" to "harfbuzz",
            "libjpeg.so.8" to "libjpeg-turbo",
            "liblcms2.so.2" to "lcms2",
            "libgif.so.7" to "giflib",
            "libc.so.6" to "glibc",
            "libGL.so.1" to "libglvnd",
            "libstdc++.so.6" to "libstdc++",
        )

    /** The package ships its own launcher library. */
    private val bundled = mapOf("libapplauncher.so" to "opt/pnp-tracker/lib/libapplauncher.so")

    private val declared =
        listOf(
            "glibc",
            "libstdc++",
            "libglvnd",
            "libx11",
            "libxext",
            "libxi",
            "libxrender",
            "libxtst",
            "fontconfig",
            "harfbuzz",
            "libjpeg-turbo",
            "lcms2",
            "giflib",
        )

    /** fontconfig pulls freetype2 in; nothing else here has dependencies worth walking. */
    private val closureOf: (List<String>) -> Set<String> = { direct ->
        (direct + if ("fontconfig" in direct) listOf("freetype2") else emptyList()).toSet()
    }

    private fun resolve(declaredNow: List<String>) =
        resolveDependencies(
            needed = archJdkNeeds,
            bundled = bundled,
            declared = declaredNow.toSet(),
            declaredClosure = closureOf(declaredNow),
            ownerOfSoname = { owners[it] },
        )

    @Test
    fun `the four libraries the 0_1_1 package missed are really mapped`() {
        val report = resolve(declared)
        assertTrue(report.undeclared.isEmpty(), "bildirilmemiş kalan var: ${report.undeclared.map { it.needed.soname }}")
        listOf(
            "libharfbuzz.so.0" to "harfbuzz",
            "libjpeg.so.8" to "libjpeg-turbo",
            "liblcms2.so.2" to "lcms2",
            "libgif.so.7" to "giflib",
        ).forEach { (soname, owner) ->
            val provider = report.resolutions.first { it.needed.soname == soname }.provider
            assertEquals(Provider.Declared(owner, direct = true), provider, "$soname yanlış eşlendi")
        }
        // And one that only arrives through another package's dependencies.
        assertEquals(
            Provider.Declared("freetype2", direct = false),
            report.resolutions.first { it.needed.soname == "libfreetype.so.6" }.provider,
        )
    }

    @Test
    fun `dropping any one of them turns the check red`() {
        listOf("harfbuzz", "libjpeg-turbo", "lcms2", "giflib").forEach { removed ->
            val report = resolve(declared - removed)
            val missing = report.undeclared.map { it.needed.soname }
            assertEquals(1, missing.size, "$removed çıkarılınca tam olarak bir kütüphane açıkta kalmalı: $missing")
            // The finding says which package would have provided it, so it can be acted on…
            val provider = report.undeclared.single().provider as Provider.Undeclared
            assertEquals(removed, provider.owner)
            // …and that name is never itself a reason to pass.
            assertTrue(report.undeclared.isNotEmpty())
        }
    }

    @Test
    fun `a library that is installed but not declared does not pass`() {
        // The exact shape of the 0.1.1 defect: the machine has harfbuzz, the recipe does not.
        val report = resolve(declared - "harfbuzz")
        val harfbuzz = report.resolutions.first { it.needed.soname == "libharfbuzz.so.0" }
        assertEquals(Provider.Undeclared("harfbuzz"), harfbuzz.provider)
        assertTrue("BİLDİRİLMEMİŞ" in report.lines().single { it.startsWith("libharfbuzz.so.0") })
    }

    @Test
    fun `a library nothing owns is reported rather than waved through`() {
        val report =
            resolveDependencies(
                needed = listOf(NeededLibrary("opt/pnp-tracker/lib/app/libmystery.so", "libnowhere.so.1")),
                bundled = emptyMap(),
                declared = declared.toSet(),
                declaredClosure = closureOf(declared),
                ownerOfSoname = { null },
            )
        assertEquals(1, report.undeclared.size)
        assertEquals(Provider.Undeclared(null), report.undeclared.single().provider)
        assertTrue("hiçbir pakette bulunamadı" in report.lines().single())
    }

    @Test
    fun `the C library counts as the base system, and only because it is declared`() {
        assertEquals(
            Provider.BaseSystem("glibc"),
            resolve(declared).resolutions.first { it.needed.soname == "libc.so.6" }.provider,
        )
        // Take glibc out of the recipe and it stops being an answer.
        val without = resolve(declared - "glibc")
        assertEquals(
            Provider.Undeclared("glibc"),
            without.resolutions.first { it.needed.soname == "libc.so.6" }.provider,
        )
    }

    @Test
    fun `what the package carries itself needs no declaration`() {
        val launcher = resolve(declared).resolutions.first { it.needed.soname == "libapplauncher.so" }
        assertEquals(Provider.Bundled("opt/pnp-tracker/lib/libapplauncher.so"), launcher.provider)
        // Bundled libraries are not worth a line in the report's summary of system libraries.
        assertTrue(resolve(declared).lines().none { it.startsWith("libapplauncher.so") && "BİLDİRİLMEMİŞ" in it })
    }

    @Test
    fun `nothing is resolved by a package that was never asked about`() {
        // pacman knows nothing here, so the closure is just the declarations, and
        // a transitive-only provider is no longer an answer.
        val silent = Commands { CommandResult(1, "") }
        assertEquals(declared.toSet(), transitiveDependencies(declared, silent))
        val report =
            resolveDependencies(
                needed = archJdkNeeds,
                bundled = bundled,
                declared = declared.toSet(),
                declaredClosure = transitiveDependencies(declared, silent),
                ownerOfSoname = { owners[it] },
            )
        assertEquals(listOf("libfreetype.so.6"), report.undeclared.map { it.needed.soname })
    }

    // ------------------------------------------------------------ the readers

    @Test
    fun `ELF files are found by their magic bytes, not their names`() {
        val root = Files.createTempDirectory("pnp-elf-test-")
        try {
            val elf = root.resolve("lib/notevenanextension").apply { parent.createDirectories() }
            Files.write(elf, byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0))
            val pretender = root.resolve("lib/libnotreally.so")
            Files.write(pretender, "#!/bin/sh\necho not an elf\n".encodeToByteArray())
            val tiny = root.resolve("lib/short")
            Files.write(tiny, byteArrayOf(0x7f, 'E'.code.toByte()))

            val found = elfFilesUnder(root).map { root.relativize(it).toString() }
            assertEquals(listOf("lib/notevenanextension"), found)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `readelf output is read for NEEDED and nothing else`() {
        val output =
            """
            Dynamic section at offset 0x1c2d8 contains 27 entries:
              Tag        Type                         Name/Value
             0x0000000000000001 (NEEDED)             Shared library: [libharfbuzz.so.0]
             0x0000000000000001 (NEEDED)             Shared library: [libc.so.6]
             0x000000000000000e (SONAME)             Library soname: [libfontmanager.so]
             0x000000000000000f (RPATH)              Library rpath: [${'$'}ORIGIN]
            """.trimIndent()
        val commands = Commands { CommandResult(0, output) }
        assertEquals(listOf("libharfbuzz.so.0", "libc.so.6"), neededLibrariesOf(Path.of("/whatever"), commands))
    }

    @Test
    fun `readelf is read in whatever language the machine speaks`() {
        // The words between the tag and the name are translated; this is what
        // the machine this was written on prints, and the first version of the
        // reader silently found nothing in it.
        val turkish =
            """
            Dinamik bölüm 0x20470 konumunda 33 girdi içeriyor:
              Etiket     Tip                          İsim/Değer
             0x0000000000000001 (NEEDED)             Paylaşımlı kitaplık: [libjvm.so]
             0x0000000000000001 (NEEDED)             Paylaşımlı kitaplık: [libc.so.6]
             0x000000000000000e (SONAME)             Kitaplık soname: [libjava.so]
            """.trimIndent()
        val commands = Commands { CommandResult(0, turkish) }
        assertEquals(listOf("libjvm.so", "libc.so.6"), neededLibrariesOf(Path.of("/whatever"), commands))
    }

    @Test
    fun `a reading that found nothing is refused instead of passing`() {
        val real = listOf(NeededLibrary("opt/pnp-tracker/lib/runtime/lib/libjava.so", "libc.so.6"))
        assertNull(credibilityProblem(elfFiles = 30, needed = real))
        // The shape of the bug this exists for: files were read, nothing was understood.
        assertTrue(credibilityProblem(elfFiles = 30, needed = emptyList())!!.contains("found nothing"))
        assertTrue(credibilityProblem(elfFiles = 0, needed = emptyList())!!.contains("no ELF file"))
        // Something was read, but not the one library every ELF here links.
        val implausible = listOf(NeededLibrary("opt/pnp-tracker/lib/app/libskiko-linux-x64.so", "libGL.so.1"))
        assertTrue(credibilityProblem(elfFiles = 30, needed = implausible)!!.contains("C library"))
    }

    @Test
    fun `a file readelf cannot read contributes nothing`() {
        val commands = Commands { CommandResult(1, "readelf: Error: Not an ELF file") }
        assertTrue(neededLibrariesOf(Path.of("/whatever"), commands).isEmpty())
    }

    @Test
    fun `pacman's dependency field is read in either language`() {
        val english =
            "Name            : fontconfig\n" +
                "Depends On      : bash  expat  freetype2  glibc  libexpat.so=1-64\n" +
                "Required By     : gtk3"
        assertEquals(listOf("bash", "expat", "freetype2", "glibc", "libexpat.so"), dependsOf(english))
        val turkish = "Adı                    : fontconfig\nBağımlılıkları         : bash  expat  freetype2  glibc>=2.27"
        assertEquals(listOf("bash", "expat", "freetype2", "glibc"), dependsOf(turkish))
        assertTrue(dependsOf("Depends On      : None").isEmpty())
        assertTrue(dependsOf("Bağımlılıkları         : Yok").isEmpty())
        assertTrue(dependsOf("nothing of the sort").isEmpty())
    }

    @Test
    fun `the declared list is read from the package's own metadata`() {
        val pkginfo =
            """
            pkgname = pnp-tracker
            pkgver = 0.1.2-1
            depend = glibc
            depend = harfbuzz
            depend = libjpeg-turbo>=3.0.0
            makedepend = something
            """.trimIndent()
        assertEquals(listOf("glibc", "harfbuzz", "libjpeg-turbo"), declaredDependsOf(pkginfo))
    }

    @Test
    fun `a package that is declared but not installed here is still found`() {
        // The release container installs only what it needs to build, so the
        // package providing a declared library is often not installed there.
        // pacman's files database answers for those; the installed-file lookup
        // cannot, and a check that trusted it alone failed a correct package.
        val installedLookupFails = "error: No package owns /usr/lib/libasound.so.2"
        val filesDatabase = "extra/alsa-lib 1.2.14-2\n    usr/lib/libasound.so.2\n"
        val commands =
            Commands { argv ->
                when {
                    argv.contains("-Qoq") -> CommandResult(1, installedLookupFails)
                    argv.contains("-Fq") -> CommandResult(0, filesDatabase)
                    else -> CommandResult(1, "")
                }
            }
        assertEquals("alsa-lib", ownerOfSoname("libasound.so.2", commands))
    }

    @Test
    fun `the files database is read in either shape, and silence is not an answer`() {
        assertEquals("alsa-lib", packageFromFilesDatabase("extra/alsa-lib 1.2.14-2\n    usr/lib/libasound.so.2\n"))
        assertEquals("alsa-lib", packageFromFilesDatabase("extra/alsa-lib\n"))
        // A database that was never synchronised says only that, and says it on stderr or as a warning.
        assertNull(packageFromFilesDatabase("uyarı: database file for 'core' does not exist (use '-Fy' to download)\n"))
        assertNull(packageFromFilesDatabase("warning: database file for 'extra' does not exist\n"))
        assertNull(packageFromFilesDatabase(""))
        assertNull(packageFromFilesDatabase("error: No package owns this\n"))
    }

    @Test
    fun `a package nobody owns is not invented`() {
        // Neither the installed files nor the files database know it.
        val commands = Commands { CommandResult(1, "error: No package owns /usr/lib/libnowhere.so.1") }
        assertNull(ownerOfSoname("libnowhere.so.1", commands))
    }

    @Test
    fun `the report says who needs what and who provides it, with no machine paths`() {
        val lines = resolve(declared).lines()
        assertTrue(lines.any { it.startsWith("libharfbuzz.so.0 ←") && "depends: harfbuzz" in it })
        assertTrue(lines.any { "libfontmanager.so" in it }, "hangi ELF istediği yazılmıyor")
        lines.forEach { line ->
            assertFalse("/home/" in line, "raporda makineye özel yol var: $line")
            assertFalse(System.getProperty("user.name") in line, "raporda kullanıcı adı var: $line")
        }
    }
}
