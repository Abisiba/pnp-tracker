package dev.pnptracker.ui.feature.export

import dev.pnptracker.domain.export.TaskExportNames
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.ui.columnNameOf
import dev.pnptracker.ui.poolNameOf
import org.jetbrains.compose.resources.getString

/**
 * The words the file calls the pools and the columns by.
 *
 * Read out of the interface's own catalogue, and not out of a second list kept
 * beside it: a user comparing `Mukavva` in the exported file with `Mukavva` on
 * the table is entitled to be reading about the same thing, and two lists would
 * eventually stop agreeing about that.
 *
 * Nine lookups, once per export. They touch no database and are read after the
 * destination is settled, so a cancelled export costs none of them either.
 */
suspend fun exportNames(): TaskExportNames =
    TaskExportNames(
        pools = PoolType.entries.associateWith { getString(poolNameOf(it)) },
        columns = CellColumnType.entries.associateWith { getString(columnNameOf(it)) },
    )
