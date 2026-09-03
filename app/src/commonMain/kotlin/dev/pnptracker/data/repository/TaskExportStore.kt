package dev.pnptracker.data.repository

import dev.pnptracker.data.database.dao.TaskExportDao
import dev.pnptracker.domain.export.ExportedTask
import dev.pnptracker.domain.export.exportedTasksOf

/**
 * The one thing the export screen needs of storage.
 *
 * Kept as an interface because driving the whole flow in a test should not need
 * a database, and because the screen has no business knowing there is one.
 */
interface TaskExportSource {
    /**
     * Every task that will be written out, from one reading of the database.
     *
     * @throws dev.pnptracker.domain.export.TaskExportException if there is
     *   nothing to write, or if a stored record breaks a guarantee the rest of
     *   the application maintains.
     */
    suspend fun exportedTasks(): List<ExportedTask>
}

/**
 * Reads the export's two queries and hands the result to the pure fold.
 *
 * Everything that decides anything is in [exportedTasksOf]; this is the join
 * between that and Room, and it is deliberately thin enough to have nothing of
 * its own to get wrong. It writes nothing, and there is no method here that
 * could.
 */
class TaskExportStore(
    private val exportDao: TaskExportDao,
) : TaskExportSource {
    override suspend fun exportedTasks(): List<ExportedTask> {
        val snapshot = exportDao.snapshot()
        return exportedTasksOf(snapshot.tasks, snapshot.colors)
    }
}
