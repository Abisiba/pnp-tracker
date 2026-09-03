package dev.pnptracker.platform.exportfiles

import dev.pnptracker.domain.export.ExportFailure
import dev.pnptracker.domain.export.ExportFileGateway
import dev.pnptracker.domain.export.ExportFileHandle
import dev.pnptracker.domain.export.TaskExportException
import dev.pnptracker.domain.export.csvFileNameOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop end of exporting: it knows about paths, and nothing above it does.
 *
 * A chosen destination becomes an [ExportFileHandle] that exposes only the file
 * name. The [Path] stays in here, which is what keeps it out of the screen, out
 * of every message and out of the log.
 *
 * The name the user typed is settled here too, by the same rule wherever it came
 * from: a name with no extension gets `.csv`, one that already ends in `.csv`
 * keeps it, and anything else is refused rather than written as a CSV under a
 * name that says otherwise.
 */
class DesktopExportFileGateway(
    private val picker: ExportFilePicker,
    private val writer: AtomicFileWriter = AtomicFileWriter(),
) : ExportFileGateway {
    override suspend fun chooseDestination(suggestedName: String): ExportFileHandle? {
        val chosen = picker.chooseDestination(suggestedName) ?: return null
        val name = chosen.fileName?.toString().orEmpty()
        if (name.isEmpty()) throw TaskExportException(ExportFailure.UNSUPPORTED_FILE_TYPE)
        // Throws for a name this does not write, before anything is read or made.
        val target = chosen.resolveSibling(csvFileNameOf(name))
        return PathExportFileHandle(target, writer)
    }
}

private class PathExportFileHandle(
    private val file: Path,
    private val writer: AtomicFileWriter,
) : ExportFileHandle {
    override val fileName: String = file.fileName?.toString().orEmpty()

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) { Files.exists(file) }

    override suspend fun write(content: String) {
        withContext(Dispatchers.IO) { writer.write(file, content) }
    }
}
