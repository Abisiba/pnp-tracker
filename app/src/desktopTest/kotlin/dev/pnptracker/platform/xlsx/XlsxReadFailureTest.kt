package dev.pnptracker.platform.xlsx

import org.apache.poi.EncryptedDocumentException
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.openxml4j.exceptions.ODFNotOfficeXmlFileException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the reader says when a file cannot be read.
 *
 * Each case asserts the narrow reason, not just that something went wrong, and
 * checks that the original library exception is still attached for developers.
 */
class XlsxReadFailureTest {
    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("pnp-xlsx-failure")
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun removeTemporaryDirectory() {
        check(directory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
            "refusing to delete $directory, which is not under the temporary directory"
        }
        Files.walk(directory).forEach { path -> Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
        directory.deleteRecursively()
    }

    @Test
    fun `a file that is not there is reported as missing`() {
        val failure =
            assertFailsWith<XlsxReadException> { XlsxWorkbookReader().read(directory.resolve("yok.xlsx")) }

        assertEquals(XlsxReadFailure.FILE_NOT_FOUND, failure.failure)
        assertEquals("yok.xlsx", failure.fileName)
    }

    @Test
    fun `a directory is not mistaken for a spreadsheet`() {
        val folder = Files.createDirectory(directory.resolve("klasor.xlsx"))

        val failure = assertFailsWith<XlsxReadException> { XlsxWorkbookReader().read(folder) }

        assertEquals(XlsxReadFailure.NOT_AN_XLSX_FILE, failure.failure)
    }

    @Test
    fun `a file the user may not read is reported as unreadable`() {
        val file = Files.createFile(directory.resolve("gizli.xlsx"))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))

        val failure = assertFailsWith<XlsxReadException> { XlsxWorkbookReader().read(file) }

        assertEquals(XlsxReadFailure.NOT_READABLE, failure.failure)
    }

    @Test
    fun `plain text with a spreadsheet name is rejected as not a spreadsheet`() {
        val file = directory.resolve("metin.xlsx")
        Files.writeString(file, "bu bir excel dosyasi degil")

        val failure = assertFailsWith<XlsxReadException> { XlsxWorkbookReader().read(file) }

        assertEquals(XlsxReadFailure.NOT_AN_XLSX_FILE, failure.failure)
        assertNotNull(failure.cause, "the library exception should stay attached as the cause")
    }

    @Test
    fun `a truncated workbook is reported as damaged rather than read half way`() {
        val whole = copyFixtureInto(directory, "kirik.xlsx")
        val bytes = Files.readAllBytes(whole)
        Files.write(whole, bytes.copyOf(bytes.size / 2))

        val failure = assertFailsWith<XlsxReadException> { XlsxWorkbookReader().read(whole) }

        assertTrue(
            failure.failure in setOf(XlsxReadFailure.DAMAGED_FILE, XlsxReadFailure.NOT_AN_XLSX_FILE),
            "a half file should be damaged or unrecognisable, was ${failure.failure}",
        )
        assertNotNull(failure.cause)
    }

    @Test
    fun `an old xls workbook is named as the old format rather than as damage`() {
        val file = directory.resolve("eski.xls")
        HSSFWorkbook().use { workbook ->
            workbook
                .createSheet("S")
                .createRow(0)
                .createCell(0)
                .setCellValue("eski")
            Files.newOutputStream(file).use { out -> workbook.write(out) }
        }

        val failure = assertFailsWith<XlsxReadException> { XlsxWorkbookReader().read(file) }

        assertEquals(XlsxReadFailure.LEGACY_XLS_FILE, failure.failure)
    }

    @Test
    fun `an error message names the file and the reason and nothing from inside it`() {
        val file = directory.resolve("metin.xlsx")
        Files.writeString(file, "gizli oyun listesi: Ornek Oyun A, Ornek Oyun B")

        val failure = assertFailsWith<XlsxReadException> { XlsxWorkbookReader().read(file) }

        val message = assertNotNull(failure.message)
        assertTrue(message.contains("metin.xlsx"))
        assertTrue(!message.contains("Ornek Oyun"), "file contents must never reach an error message")
        assertTrue(!message.contains(directory.toString()), "the path must not be in the message")
    }

    /**
     * Producing a genuinely encrypted workbook needs a cipher provider this
     * project does not depend on, so the mapping is asserted directly on the
     * exception the library raises for one.
     */
    @Test
    fun `an encrypted workbook maps to its own reason`() {
        assertEquals(
            XlsxReadFailure.ENCRYPTED,
            failureOf(EncryptedDocumentException("password protected")),
        )
    }

    @Test
    fun `other library failures map to the reason that fits them`() {
        assertEquals(XlsxReadFailure.NOT_AN_XLSX_FILE, failureOf(ODFNotOfficeXmlFileException("odf")))
        assertEquals(XlsxReadFailure.NOT_READABLE, failureOf(AccessDeniedException("f")))
        assertEquals(XlsxReadFailure.FILE_NOT_FOUND, failureOf(java.nio.file.NoSuchFileException("f")))
        assertEquals(XlsxReadFailure.DAMAGED_FILE, failureOf(IOException("truncated entry")))
        assertEquals(
            XlsxReadFailure.REJECTED_BY_SAFETY_LIMIT,
            failureOf(IOException("Zip bomb detected! The file would exceed the max")),
        )
    }
}
