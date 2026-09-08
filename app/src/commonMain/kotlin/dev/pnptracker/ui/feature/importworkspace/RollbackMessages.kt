package dev.pnptracker.ui.feature.importworkspace

import dev.pnptracker.domain.importrollback.CellObstacle
import dev.pnptracker.domain.importrollback.ImportRollbackFailure
import dev.pnptracker.domain.importrollback.TaskObstacle
import dev.pnptracker.ui.Strings
import org.jetbrains.compose.resources.StringResource

/**
 * What to tell the user about an import that was not taken back.
 *
 * Every one of PLAN 11.4.4's refusals has its own sentence, because each leaves
 * the user in a different place: one is a mistake to correct, one is an import
 * too old to have kept what a safe rollback needs, and two are the user's own
 * later decisions being protected. A single general apology would flatten all
 * of that into "something went wrong", and the raw enum name would be worse
 * still — it is a word from the source, not a sentence in anybody's language.
 *
 * The `when` is exhaustive with no fallback, so a refusal added later cannot
 * reach the screen without being given words first.
 */
fun messageOf(failure: ImportRollbackFailure): StringResource =
    when (failure) {
        ImportRollbackFailure.BATCH_NOT_FOUND -> Strings.Rollback.errorBatchNotFound
        ImportRollbackFailure.BATCH_NOT_CONFIRMED -> Strings.Rollback.errorNotConfirmed
        ImportRollbackFailure.ALREADY_ROLLED_BACK -> Strings.Rollback.errorAlreadyRolledBack
        ImportRollbackFailure.NO_CELL_SNAPSHOT -> Strings.Rollback.errorNoCellSnapshot
        ImportRollbackFailure.TASKS_WERE_EDITED -> Strings.Rollback.errorTasksWereEdited
        ImportRollbackFailure.CELLS_WERE_EDITED -> Strings.Rollback.errorCellsWereEdited
        ImportRollbackFailure.PROVENANCE_BROKEN -> Strings.Rollback.errorProvenanceBroken
        ImportRollbackFailure.COULD_NOT_SAVE -> Strings.Rollback.errorCouldNotSave
    }

/**
 * Why one task stands in the way, in the user's words.
 *
 * Said per task rather than only once for the whole refusal, because the tasks
 * in one blocked import are usually blocked for different reasons, and "one of
 * these was edited" leaves the user to work out which.
 */
fun reasonOf(obstacle: TaskObstacle): StringResource =
    when (obstacle) {
        TaskObstacle.EDITED -> Strings.Rollback.taskEdited
        TaskObstacle.HAS_PROGRESS_EVENT -> Strings.Rollback.taskHasProgress
        TaskObstacle.HAS_HISTORY_EVENT -> Strings.Rollback.taskHasHistory
        TaskObstacle.DELETED -> Strings.Rollback.taskDeleted
        TaskObstacle.NOT_ANCHORED -> Strings.Rollback.taskNotAnchored
        TaskObstacle.PROVENANCE_MISSING -> Strings.Rollback.taskProvenanceMissing
    }

/** Why one cell stands in the way, in the user's words. */
fun reasonOf(obstacle: CellObstacle): StringResource =
    when (obstacle) {
        CellObstacle.SNAPSHOT_MISSING -> Strings.Rollback.cellSnapshotMissing
        CellObstacle.DOCUMENT_CHANGED -> Strings.Rollback.cellDocumentChanged
        CellObstacle.STRUCTURE_CHANGED -> Strings.Rollback.cellStructureChanged
    }
