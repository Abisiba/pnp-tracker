package dev.pnptracker.domain.spreadsheet

/**
 * A whole spreadsheet file, read into memory and detached from it.
 *
 * Only the [fileName] is kept. Where the file sat on disk is the user's private
 * business and is of no use later, so the path is deliberately not part of the
 * model — and cannot be, since nothing here is a file handle.
 */
data class WorkbookSnapshot(
    val fileName: String,
    val sheets: List<SheetSnapshot>,
) {
    init {
        require(fileName.isNotBlank()) { "A workbook snapshot records the name of the file it came from" }
        require(!fileName.contains('/') && !fileName.contains('\\')) {
            "Only the file name is kept, never a path, but got: $fileName"
        }
    }

    fun sheetNamed(name: String): SheetSnapshot? = sheets.firstOrNull { it.name == name }
}
