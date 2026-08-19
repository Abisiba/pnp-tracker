package dev.pnptracker.ui.feature.importreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pnptracker.data.repository.EarlierImport
import dev.pnptracker.domain.importprep.ImportWarning
import dev.pnptracker.domain.importprep.ImportWarningKind
import dev.pnptracker.domain.importprep.PreparedImportDraft
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.ui.Strings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val MAX_CONTENT_WIDTH = 720.dp

/**
 * Choosing a spreadsheet and saving what it holds as a draft import.
 *
 * The screen only ever shows the file's name. It never sees the path, so it
 * cannot show one, and it reports a draft as saved only once the write has
 * actually come back.
 */
@Composable
fun ImportScreen(
    controller: ImportController,
    onOpenReview: (EntityId) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Strings.ScreenTitles.importReview),
            style = MaterialTheme.typography.headlineSmall,
        )

        when (val state = controller.state) {
            ImportScreenState.Idle ->
                IdleSection(onChooseFile = { scope.launch { controller.chooseFile() } })

            ImportScreenState.ChoosingFile -> BusySection(stringResource(Strings.Import.choosingFile))

            ImportScreenState.ReadingFile -> BusySection(stringResource(Strings.Import.readingFile))

            is ImportScreenState.SheetSelection ->
                SessionSection(
                    session = state.session,
                    isSaving = false,
                    askForSheet = true,
                    onSelectSheet = { name -> scope.launch { controller.selectSheet(name) } },
                    onSave = { scope.launch { controller.saveDraft() } },
                    onChooseAnother = { scope.launch { controller.chooseFile() } },
                )

            is ImportScreenState.PreviewReady ->
                SessionSection(
                    session = state.session,
                    isSaving = false,
                    askForSheet = state.session.sheets.size > 1,
                    onSelectSheet = { name -> scope.launch { controller.selectSheet(name) } },
                    onSave = { scope.launch { controller.saveDraft() } },
                    onChooseAnother = { scope.launch { controller.chooseFile() } },
                )

            is ImportScreenState.Saving ->
                SessionSection(
                    session = state.session,
                    isSaving = true,
                    askForSheet = false,
                    onSelectSheet = {},
                    onSave = {},
                    onChooseAnother = {},
                )

            is ImportScreenState.DuplicateWarning ->
                DuplicateSection(
                    earlierImports = state.session.earlierImports,
                    onConfirm = { scope.launch { controller.confirmDuplicateImport() } },
                    onCancel = { controller.cancelDuplicateImport() },
                )

            is ImportScreenState.Saved ->
                SavedSection(
                    fileName = state.summary.fileName,
                    sheetName = state.summary.sheetName,
                    rawBlockCount = state.summary.rawBlockCount,
                    onChooseAnother = { scope.launch { controller.chooseFile() } },
                    onOpenReview = { onOpenReview(state.summary.batchId) },
                )

            is ImportScreenState.Failed ->
                FailedSection(
                    state = state,
                    onChooseAnother = { scope.launch { controller.chooseFile() } },
                )
        }

        // Anything the section wants below the flow shares this one scrolling
        // column; a second scroll wrapped around this one would leave the inner
        // one with no height to measure against.
        footer()
    }
}

@Composable
private fun IdleSection(onChooseFile: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(Strings.ScreenDescriptions.importReview),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH),
        )
        Text(
            text = stringResource(Strings.Import.idleHint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH),
        )
        Button(onClick = onChooseFile) { Text(stringResource(Strings.Import.chooseFile)) }
    }
}

@Composable
private fun BusySection(message: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SessionSection(
    session: ImportSession,
    isSaving: Boolean,
    askForSheet: Boolean,
    onSelectSheet: (String) -> Unit,
    onSave: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH),
    ) {
        Text(
            text = stringResource(Strings.Import.fileLabel, session.fileName),
            style = MaterialTheme.typography.titleMedium,
        )

        if (askForSheet) {
            SheetChooser(
                sheets = session.sheets,
                selectedSheetName = session.selectedSheetName,
                enabled = !isSaving,
                onSelectSheet = onSelectSheet,
            )
        } else {
            session.selectedSheetName?.let { name ->
                Text(text = stringResource(Strings.Import.sheetLabel, name))
            }
        }

        when (val preparation = session.preparation) {
            is SheetPreparation.Ready -> DraftSummary(preparation.draft)
            is SheetPreparation.Rejected ->
                ProblemCard(
                    title = stringResource(Strings.ImportErrors.title),
                    detail = detailFor(preparation.failure, preparation.columnIndex),
                )

            null -> Unit
        }

        if (session.hasEarlierImports) {
            Text(
                text = stringResource(Strings.Import.duplicateCount, session.earlierImports.size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (isSaving) {
            BusySection(stringResource(Strings.Import.saving))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave, enabled = session.canSave) {
                    Text(stringResource(Strings.Import.saveDraft))
                }
                OutlinedButton(onClick = onChooseAnother) {
                    Text(stringResource(Strings.Import.chooseAnotherFile))
                }
            }
        }
    }
}

@Composable
private fun SheetChooser(
    sheets: List<SheetChoice>,
    selectedSheetName: String?,
    enabled: Boolean,
    onSelectSheet: (String) -> Unit,
) {
    val label = stringResource(Strings.Import.sheetAccessibilityLabel)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.selectableGroup().semantics { contentDescription = label },
    ) {
        Text(
            text = stringResource(Strings.Import.sheetSelectionTitle),
            style = MaterialTheme.typography.titleSmall,
        )
        sheets.forEach { sheet ->
            val selected = sheet.name == selectedSheetName
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { onSelectSheet(sheet.name) },
                    enabled = enabled && !sheet.isEmpty,
                )
                Text(
                    text = sheet.name,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                // Visibility and size are written out, so the choice never rests
                // on a colour or on a position alone.
                Text(
                    text = stringResource(nameOf(sheet.visibility)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Strings.Import.sheetCellCount, sheet.contentCellCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DraftSummary(draft: PreparedImportDraft) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Strings.Import.summaryTitle),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(Strings.Import.rawBlockCount, draft.rawBlockCount))
            Text(stringResource(Strings.Import.gameCellCount, draft.detectedGameCellCount))
            Text(stringResource(Strings.Import.greenHintCount, draft.pendingGameCompletionHintCount))
            Text(stringResource(Strings.Import.multiLineCellCount, draft.multiLineCellCount))

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(Strings.Import.columnCountsTitle),
                style = MaterialTheme.typography.titleSmall,
            )
            draft.blockCountsByColumnType.entries
                .sortedBy { it.key.ordinal }
                .forEach { (columnType, count) ->
                    Text(
                        stringResource(
                            Strings.Import.columnCountRow,
                            stringResource(nameOf(columnType)),
                            count,
                        ),
                    )
                }

            if (draft.warnings.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(Strings.Import.warningsTitle),
                    style = MaterialTheme.typography.titleSmall,
                )
                draft.warnings.forEach { warning -> Text(warningText(warning)) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(Strings.Import.nothingCreatedNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun warningText(warning: ImportWarning): String =
    when (warning.kind) {
        ImportWarningKind.ROW_WITHOUT_GAME_NAME ->
            stringResource(
                Strings.Import.warningRowWithoutGameName,
                // Rows are shown the way a spreadsheet numbers them, from one.
                warning.rowIndexes.joinToString(", ") { (it + 1).toString() },
            )

        ImportWarningKind.HIDDEN_SHEET -> stringResource(Strings.Import.warningHiddenSheet)
    }

@Composable
private fun DuplicateSection(
    earlierImports: List<EarlierImport>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH),
    ) {
        Text(
            text = stringResource(Strings.Import.duplicateTitle),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(Strings.Import.duplicateCount, earlierImports.size))
        earlierImports.forEach { earlier ->
            Text(
                text =
                    stringResource(
                        Strings.Import.duplicateEntry,
                        earlier.fileName,
                        earlier.sheetName,
                        earlier.status.name,
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(stringResource(Strings.Import.duplicateQuestion))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onConfirm) { Text(stringResource(Strings.Import.importAnyway)) }
            TextButton(onClick = onCancel) { Text(stringResource(Strings.Import.cancel)) }
        }
    }
}

@Composable
private fun SavedSection(
    fileName: String,
    sheetName: String,
    rawBlockCount: Int,
    onChooseAnother: () -> Unit,
    onOpenReview: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH),
    ) {
        Text(
            text = stringResource(Strings.Import.savedTitle),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(Strings.Import.savedSummary, fileName, sheetName, rawBlockCount))
        Button(onClick = onOpenReview) {
            Text(stringResource(Strings.Review.open))
        }
        OutlinedButton(onClick = onChooseAnother) {
            Text(stringResource(Strings.Import.chooseAnotherFile))
        }
    }
}

@Composable
private fun FailedSection(
    state: ImportScreenState.Failed,
    onChooseAnother: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH),
    ) {
        ProblemCard(
            title = stringResource(Strings.ImportErrors.title),
            detail = detailFor(state.failure, state.columnIndex),
        )
        Button(onClick = onChooseAnother) { Text(stringResource(Strings.Import.chooseAnotherFile)) }
    }
}

@Composable
private fun detailFor(
    failure: dev.pnptracker.domain.importprep.ImportFailure,
    columnIndex: Int?,
): String =
    if (columnIndex == null) {
        stringResource(messageFor(failure))
    } else {
        stringResource(messageFor(failure), columnIndex)
    }

/**
 * A problem, said in words.
 *
 * There is no cause, no stack trace and nothing from inside the file here: an
 * error tells the user what to do next, and the details a developer needs stay
 * attached to the exception.
 */
@Composable
private fun ProblemCard(
    title: String,
    detail: String,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text = detail)
        }
    }
}
