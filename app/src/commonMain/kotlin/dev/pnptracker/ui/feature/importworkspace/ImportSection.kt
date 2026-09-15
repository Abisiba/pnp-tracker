package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreen

/**
 * The import section: choosing a file, and reviewing what an earlier choice
 * saved.
 *
 * These are two different jobs, so they are two screens rather than one crowded
 * one. Which is showing is decided here and nowhere else, which keeps the file
 * picking flow from having to know that reviewing exists.
 *
 * The list of unfinished imports is what makes an interrupted review survive the
 * application closing: the drafts are in the database, so they are simply still
 * there the next time this section opens — to be continued, or removed.
 */
@Composable
fun ImportSection(
    importController: ImportController,
    reviewController: ImportReviewController,
    confirmationController: ImportConfirmationController,
    rollbackController: ImportRollbackController,
    unfinishedController: UnfinishedImportsController,
    modifier: Modifier = Modifier,
) {
    var openBatchId: EntityId? by remember { mutableStateOf(null) }

    val batchId = openBatchId
    if (batchId != null) {
        ImportReviewScreen(
            controller = reviewController,
            confirmation = confirmationController,
            batchId = batchId,
            onBack = { openBatchId = null },
            modifier = modifier,
        )
        return
    }

    // The file picking screen already scrolls; the resumable list goes inside that
    // same column rather than into a second one wrapped around it.
    ImportScreen(
        controller = importController,
        onOpenReview = { savedBatchId -> openBatchId = savedBatchId },
        modifier = modifier,
    ) {
        // A draft reaches the review screen from here only once its records
        // have been read again at the moment of opening (PLAN 11.4.5).
        UnfinishedImportsSection(
            controller = unfinishedController,
            onOpen = { resumedBatchId -> openBatchId = resumedBatchId },
        )
        // The two lists are different things and are labelled as such: one is a
        // review to come back to, the other is work already done that PLAN
        // 11.4.4 lets the user undo.
        SettledImportsSection(controller = rollbackController)
    }
}
