package dev.pnptracker.domain.export

/** The one extension this writes, and the one it will accept. */
const val CSV_EXTENSION: String = ".csv"

/**
 * The name a file will actually be written under.
 *
 * A name with no extension gets `.csv`, because somebody typing `görevler` means
 * a CSV — it is the only thing on offer. A name that already ends in `.csv` is
 * left exactly as it is, whatever case it was typed in: turning `GÖREVLER.CSV`
 * into `GÖREVLER.CSV.csv` would be the application being pedantic about
 * something no file system cares about.
 *
 * A name ending in anything else is refused rather than quietly given a second
 * extension or, worse, written as a CSV under a name that promises something
 * different. That is stricter than it might be — `görevler.2026` is refused too
 * — but the alternative is a list of extensions this application has opinions
 * about, and there is no honest way to write that list.
 *
 * @throws TaskExportException with [ExportFailure.UNSUPPORTED_FILE_TYPE].
 */
fun csvFileNameOf(chosenName: String): String {
    val name = chosenName
    require(name.isNotEmpty()) { "A file needs a name" }

    val lastDot = name.lastIndexOf('.')
    // A leading dot is a hidden file rather than an extension, so `.görevler`
    // has no extension and becomes `.görevler.csv`.
    if (lastDot <= 0) return name + CSV_EXTENSION
    if (name.regionMatches(lastDot, CSV_EXTENSION, 0, CSV_EXTENSION.length, ignoreCase = true) &&
        lastDot + CSV_EXTENSION.length == name.length
    ) {
        return name
    }
    throw TaskExportException(ExportFailure.UNSUPPORTED_FILE_TYPE)
}

/** What the save dialog offers before the user types anything. */
const val SUGGESTED_EXPORT_FILE_NAME: String = "gorevler.csv"
