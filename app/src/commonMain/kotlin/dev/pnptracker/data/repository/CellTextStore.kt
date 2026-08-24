package dev.pnptracker.data.repository

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.dao.CellSegmentDao
import dev.pnptracker.domain.games.CellTextException
import dev.pnptracker.domain.games.CellTextFailure
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import kotlin.time.Clock

/** Writing what a cell says. */
interface CellTextEditing {
    /**
     * Makes one cell of one game say exactly [exactText].
     *
     * The text is stored character for character. A finished game is edited like
     * any other — PLAN 5.3 keeps a finished game editable and PLAN 12.4 lets the
     * user work in whichever view they are in — and a deleted one is not edited
     * at all.
     *
     * @return true when this call changed something.
     * @throws CellTextException if the game is gone or the cell holds a task.
     */
    suspend fun savePlainText(
        gameId: EntityId,
        columnType: CellColumnType,
        exactText: String,
    ): Boolean
}

/**
 * The user writing in a cell.
 *
 * Thin on purpose: the rules about what a cell may look like afterwards belong
 * to the one transaction that enforces them, so all this adds is the clock, the
 * source of identities, and turning a storage refusal into something a screen
 * can say.
 */
class CellTextStore(
    private val cellSegmentDao: CellSegmentDao,
    private val idGenerator: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
) : CellTextEditing {
    override suspend fun savePlainText(
        gameId: EntityId,
        columnType: CellColumnType,
        exactText: String,
    ): Boolean =
        try {
            cellSegmentDao.savePlainText(
                gameId = gameId,
                columnType = columnType,
                exactText = exactText,
                clock = clock,
                idGenerator = idGenerator,
            )
        } catch (cause: SQLiteException) {
            // Only a recognised storage refusal becomes something the user is
            // told about; a broken invariant travels out untouched.
            throw CellTextException(CellTextFailure.COULD_NOT_SAVE, cause)
        }
}
