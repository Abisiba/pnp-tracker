package dev.pnptracker.domain.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What a file will actually be called.
 *
 * One rule, applied wherever the name came from, so a name typed into the dialog
 * and a name arriving some other way cannot be treated differently.
 */
class ExportFileNameTest {
    @Test
    fun `a name with no extension gets one`() {
        assertEquals("gorevler.csv", csvFileNameOf("gorevler"))
        assertEquals("görevler 2026.csv", csvFileNameOf("görevler 2026"))
    }

    @Test
    fun `a name that already ends in csv is left alone`() {
        assertEquals("gorevler.csv", csvFileNameOf("gorevler.csv"))
    }

    @Test
    fun `capitals in the extension are still the extension`() {
        assertEquals("GOREVLER.CSV", csvFileNameOf("GOREVLER.CSV"), "the name was given a second extension")
        assertEquals("gorevler.Csv", csvFileNameOf("gorevler.Csv"))
    }

    @Test
    fun `a hidden file has no extension and gets one`() {
        assertEquals(".gorevler.csv", csvFileNameOf(".gorevler"))
    }

    @Test
    fun `a dotted name is kept as long as it ends in csv`() {
        assertEquals("gorevler.2026.csv", csvFileNameOf("gorevler.2026.csv"))
    }

    @Test
    fun `any other extension is refused rather than quietly written as a csv`() {
        listOf("gorevler.xlsx", "gorevler.json", "gorevler.txt", "gorevler.2026", "gorevler.").forEach { name ->
            val refused = assertFailsWith<TaskExportException>(name) { csvFileNameOf(name) }
            assertEquals(ExportFailure.UNSUPPORTED_FILE_TYPE, refused.failure, name)
        }
    }

    @Test
    fun `the name offered before anything is typed is a csv`() {
        assertEquals(SUGGESTED_EXPORT_FILE_NAME, csvFileNameOf(SUGGESTED_EXPORT_FILE_NAME))
    }
}
