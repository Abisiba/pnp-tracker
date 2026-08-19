package dev.pnptracker.platform.files

import dev.pnptracker.platform.xlsx.FIXTURE_NAME
import dev.pnptracker.platform.xlsx.copyFixtureInto
import dev.pnptracker.platform.xlsx.openHandlesTo
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileFingerprintTest {
    private lateinit var directory: Path
    private val fingerprint = FileFingerprint()

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("pnp-fingerprint")
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun removeTemporaryDirectory() {
        check(directory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $directory, which is not under the temporary directory"
        }
        Files.walk(directory).forEach { path ->
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
        directory.deleteRecursively()
    }

    private fun write(
        name: String,
        content: String,
    ): Path = Files.writeString(directory.resolve(name), content)

    @Test
    fun `the same content always gives the same fingerprint`() {
        val file = write("a.xlsx", "aynı içerik")

        assertEquals(fingerprint.of(file), fingerprint.of(file))
    }

    @Test
    fun `different content gives a different fingerprint`() {
        val first = write("a.xlsx", "içerik bir")
        val second = write("b.xlsx", "içerik iki")

        assertTrue(fingerprint.of(first) != fingerprint.of(second))
    }

    @Test
    fun `the file name is not part of the fingerprint`() {
        val first = write("kitap.xlsx", "aynı içerik")
        val second = write("bambaska-ad.xlsx", "aynı içerik")

        assertEquals(
            fingerprint.of(first),
            fingerprint.of(second),
            "two copies of one file must be recognised as the same file",
        )
    }

    @Test
    fun `a fingerprint is sixty four lower case hex characters`() {
        val hex = fingerprint.of(write("a.xlsx", "içerik"))

        assertEquals(64, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" }, "must be lower case hex, was: $hex")
    }

    @Test
    fun `it matches the digest of the whole file for a file larger than the read buffer`() {
        // Several times the 64 KiB buffer, so more than one block is folded in.
        val bytes = Random(seed = 7).nextBytes(512 * 1024)
        val file = directory.resolve("buyuk.bin")
        Files.write(file, bytes)
        val expected =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

        assertEquals(expected, fingerprint.of(file))
    }

    @Test
    fun `the file is closed again afterwards`() {
        val file = copyFixtureInto(directory)

        fingerprint.of(file)

        assertEquals(emptyList(), openHandlesTo(file), "the fingerprint left the file open")
        // Nothing was written beside it either.
        assertEquals(
            setOf(FIXTURE_NAME),
            Files
                .list(directory)
                .use { it.toList() }
                .map { it.fileName.toString() }
                .toSet(),
        )
    }

    @Test
    fun `a missing file is reported plainly and not as an empty digest`() {
        assertFailsWith<NoSuchFileException> { fingerprint.of(directory.resolve("yok.xlsx")) }
    }

    @Test
    fun `fingerprinting leaves the source file untouched`() {
        val file = copyFixtureInto(directory)
        val sizeBefore = Files.size(file)
        val modifiedBefore = Files.getLastModifiedTime(file)
        val digestBefore = fingerprint.of(file)

        repeat(3) { fingerprint.of(file) }

        assertEquals(digestBefore, fingerprint.of(file))
        assertEquals(sizeBefore, Files.size(file))
        assertEquals(modifiedBefore, Files.getLastModifiedTime(file))
    }
}
