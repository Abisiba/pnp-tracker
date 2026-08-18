package dev.pnptracker.platform.xlsx

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readSymbolicLink
import kotlin.test.assertTrue
import kotlin.test.fail

/** The anonymised workbook committed under `src/desktopTest/resources`. */
const val FIXTURE_NAME = "sample-import.xlsx"

/**
 * Copies the fixture into [directory] so a test can watch what the reader does
 * to a file it owns, without ever touching the committed one.
 */
fun copyFixtureInto(
    directory: Path,
    name: String = FIXTURE_NAME,
): Path {
    val target = directory.resolve(name)
    val resource =
        XlsxWorkbookReader::class.java.classLoader.getResourceAsStream(FIXTURE_NAME)
            ?: fail("$FIXTURE_NAME is missing from the desktop test resources")
    resource.use { input -> Files.copy(input, target) }
    return target
}

fun sha256Of(file: Path): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(Files.readAllBytes(file))
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

/**
 * Every path this process currently holds open.
 *
 * Linux publishes the descriptor table, so a leaked handle can be seen directly
 * rather than guessed at from whether a delete happens to succeed.
 */
fun openHandlesTo(file: Path): List<String> {
    val absolute = file.toAbsolutePath().toString()
    return Path
        .of("/proc/self/fd")
        .listDirectoryEntries()
        .mapNotNull { entry -> runCatching { entry.readSymbolicLink().toString() }.getOrNull() }
        .filter { it == absolute }
}

/** Fails if the reader left anything of its own beside the file it read. */
fun assertNoSiblingsCreated(
    directory: Path,
    expected: Set<String>,
) {
    val actual = directory.listDirectoryEntries().map { it.fileName.toString() }.toSet()
    assertTrue(actual == expected, "reader left files behind: ${actual - expected}")
}
