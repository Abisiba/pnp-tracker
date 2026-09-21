package dev.pnptracker.packaging

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The project's licence (Faz 3 / İş 15).
 *
 * `LICENSE` must be the MIT licence, word for word, with nothing but the
 * copyright line filled in — a licence that has been edited is not the licence
 * people think they are reading. The places that repeat the choice (the Arch
 * recipe, the archive's README, the project README and the user guide) are held
 * to the same answer, and no source file carries a licence header: the one
 * licence lives in the one file.
 */
class LicenseTest {
    private fun repository(): Path {
        var directory = Path.of("").toAbsolutePath()
        while (!Files.isDirectory(directory.resolve(".git"))) {
            directory = requireNotNull(directory.parent) { "the repository root is not above the working directory" }
        }
        return directory
    }

    private val copyright = "Copyright (c) 2026 PNP Tracker contributors"

    /** The MIT licence as the Open Source Initiative publishes it. */
    private val mit =
        """
        MIT License

        $copyright

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software, and to permit persons to whom the Software is
        furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in all
        copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
        IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
        FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
        AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
        LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
        OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
        SOFTWARE.
        """.trimIndent() + "\n"

    @Test
    fun `LICENSE is the MIT licence, word for word`() {
        val file = repository().resolve("LICENSE")
        assertTrue(Files.isRegularFile(file), "kökte LICENSE dosyası yok")
        assertEquals(mit, Files.readString(file), "LICENSE standart MIT metninden ayrılmış")
    }

    @Test
    fun `the copyright line names no person and no address`() {
        val text = Files.readString(repository().resolve("LICENSE"))
        val line = text.lines().single { it.startsWith("Copyright") }
        assertEquals(copyright, line)
        assertFalse("@" in text, "LICENSE bir e-posta adresi taşıyor")
        assertFalse(Regex("/home/|${System.getProperty("user.name")}").containsMatchIn(text), "LICENSE bir yol veya kullanıcı adı taşıyor")
    }

    @Test
    fun `the Arch recipe declares MIT and installs the licence where Arch keeps it`() {
        val pkgbuild = Files.readString(repository().resolve("packaging/arch/PKGBUILD"))
        assertTrue("license=('MIT')" in pkgbuild, "PKGBUILD lisansı MIT olarak bildirmiyor")
        assertFalse("LicenseRef-unknown" in pkgbuild, "PKGBUILD hâlâ lisanssız durumu anlatıyor")
        val dollar = '$'
        assertTrue(
            """install -Dm644 "${dollar}srcdir/pnp-tracker-${dollar}pkgver/LICENSE" """ +
                """"${dollar}pkgdir/usr/share/licenses/${dollar}pkgname/LICENSE"""" in pkgbuild,
            "PKGBUILD lisansı /usr/share/licenses altına kurmuyor",
        )
        assertTrue("THIRD_PARTY_NOTICES.md" in pkgbuild, "PKGBUILD üçüncü taraf bildirimlerini kurmuyor")
        // The package description must survive the licence change.
        assertTrue("pkgdesc='PnP masa oyunları" in pkgbuild, "paket açıklaması kaybolmuş")
    }

    @Test
    fun `the build puts the licence and the notices into the application directory`() {
        val build = Files.readString(repository().resolve("app/build.gradle.kts"))
        assertTrue("""from(rootProject.file("LICENSE"))""" in build, "arşiv LICENSE dosyasını taşımıyor")
        assertTrue("writeThirdPartyNotices(root)" in build, "üçüncü taraf bildirimleri üretilmiyor")
        assertTrue("THIRD_PARTY_NOTICES.md" in build, "bildirim dosyasının adı build'de yok")
    }

    @Test
    fun `the archive README and the project documents say the same thing`() {
        val archiveReadme = Files.readString(repository().resolve("packaging/linux/README.txt"))
        assertFalse("henüz bir açık kaynak lisansı seçilmemiştir" in archiveReadme, "arşiv README'si hâlâ lisanssız diyor")
        assertTrue("MIT" in archiveReadme && "LICENSE" in archiveReadme, "arşiv README'si lisansı anlatmıyor")
        assertTrue("THIRD_PARTY_NOTICES.md" in archiveReadme, "arşiv README'si bildirim dosyasını anmıyor")
        // The description of the directory is still there; the licence did not replace it.
        assertTrue("bin/pnp-tracker" in archiveReadme && "XDG" in archiveReadme, "arşiv README'sinin içerik açıklaması kaybolmuş")

        listOf("README.md", "docs/kullanim-kilavuzu.md").forEach { name ->
            val text = Files.readString(repository().resolve(name))
            assertTrue("MIT" in text, "$name lisansı söylemiyor")
            assertTrue("LICENSE" in text, "$name LICENSE dosyasına götürmüyor")
        }
    }

    @Test
    fun `no source file carries a licence header`() {
        val sources = repository().resolve("app/src")
        Files.walk(sources).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .forEach { file ->
                    val first = Files.readAllLines(file).firstOrNull { it.isNotBlank() } ?: return@forEach
                    assertFalse(
                        first.startsWith("/*") || first.startsWith("//") && Regex("(?i)copyright|licen[cs]e").containsMatchIn(first),
                        "$file bir lisans başlığıyla başlıyor; lisans tek dosyada durur",
                    )
                }
        }
    }
}
