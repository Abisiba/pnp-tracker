package dev.pnptracker.ui.feature.importworkspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importreview.ImportScreen
import org.jetbrains.compose.resources.stringResource

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
 * there the next time this section opens.
 */
@Composable
fun ImportSection(
    importController: ImportController,
    reviewController: ImportReviewController,
    modifier: Modifier = Modifier,
) {
    var openBatchId: EntityId? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) { reviewController.observeDraftBatches() }

    val batchId = openBatchId
    if (batchId != null) {
        ImportReviewScreen(
            controller = reviewController,
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
        ResumableImports(
            controller = reviewController,
            onOpen = { resumedBatchId -> openBatchId = resumedBatchId },
        )
    }
}

@Composable
private fun ResumableImports(
    controller: ImportReviewController,
    onOpen: (EntityId) -> Unit,
) {
    val batches = controller.draftBatches
    if (batches.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Strings.Review.resumableTitle),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(Strings.Review.resumableHint),
            style = MaterialTheme.typography.bodyMedium,
        )
        batches.forEach { batch ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(Strings.Review.resumableEntry, batch.fileName, batch.sheetName),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = { onOpen(batch.batchId) }) {
                        Text(stringResource(Strings.Review.open))
                    }
                }
            }
        }
    }
}
