package dev.pnptracker.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pnptracker.AppInfo
import dev.pnptracker.domain.pools.PoolNavigationSummary
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.colors.ColorCatalogueController
import dev.pnptracker.ui.feature.colors.ColorCatalogueScreen
import dev.pnptracker.ui.feature.export.ExportController
import dev.pnptracker.ui.feature.export.ExportScreenState
import dev.pnptracker.ui.feature.export.TaskExportAction
import dev.pnptracker.ui.feature.export.TaskExportStatus
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.games.GameTableScreen
import dev.pnptracker.ui.feature.games.TableExport
import dev.pnptracker.ui.feature.history.HistoryController
import dev.pnptracker.ui.feature.history.HistoryScreen
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.importworkspace.ImportReviewController
import dev.pnptracker.ui.feature.importworkspace.ImportRollbackController
import dev.pnptracker.ui.feature.importworkspace.ImportSection
import dev.pnptracker.ui.feature.importworkspace.UnfinishedImportsController
import dev.pnptracker.ui.feature.pools.PoolControllers
import dev.pnptracker.ui.feature.pools.PoolScreen
import dev.pnptracker.ui.feature.settings.AppearanceController
import dev.pnptracker.ui.feature.settings.BackupController
import dev.pnptracker.ui.feature.settings.RestoreController
import dev.pnptracker.ui.feature.settings.RetentionController
import dev.pnptracker.ui.feature.settings.SettingsScreen
import dev.pnptracker.ui.textsOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val NavigationItemShape = RoundedCornerShape(12.dp)
private val SelectionUnderlineHeight = 3.dp
private val SelectionUnderlineWidth = 24.dp

/**
 * The window: one short line of navigation across the top, the open section
 * under it (PLAN 12.1).
 *
 * There is no sidebar and no home page. The table is the surface the application
 * is worked from, so it gets the whole width; the entries above it are the table,
 * the four pools and `Ayarlar`, whose page holds the rest of the sections.
 */
@Composable
fun AppScaffold(
    appInfo: AppInfo,
    navigation: AppNavigationState,
    appearanceController: AppearanceController,
    importController: ImportController,
    reviewController: ImportReviewController,
    confirmationController: ImportConfirmationController,
    rollbackController: ImportRollbackController,
    unfinishedController: UnfinishedImportsController,
    gameTableController: GameTableController,
    exportController: ExportController,
    backupController: BackupController,
    restoreController: RestoreController,
    retentionController: RetentionController,
    colorCatalogueController: ColorCatalogueController,
    poolControllers: PoolControllers,
    historyController: HistoryController,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(poolControllers) { poolControllers.observeNavigationSummary() }
    val summary = poolControllers.summary

    val exportScope = rememberCoroutineScope()
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopNavigation(appInfo = appInfo, navigation = navigation, summary = summary)
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val screen = navigation.currentScreen) {
                    Screen.Games ->
                        GameTableScreen(
                            controller = gameTableController,
                            // The export lives in the table's `⋯` menu; what it has
                            // to say is drawn under the table's toolbar.
                            export =
                                TableExport(
                                    start = { exportScope.launch { exportController.exportTasks() } },
                                    enabled =
                                        !exportController.isBusy &&
                                            exportController.state !is ExportScreenState.ConfirmingOverwrite,
                                    status = { TaskExportStatus(exportController) },
                                ),
                        )
                    Screen.History -> SettingsPage(navigation) { HistoryScreen(historyController) }
                    Screen.Colors -> SettingsPage(navigation) { ColorCatalogueScreen(colorCatalogueController) }
                    Screen.Settings ->
                        SettingsPage(navigation) {
                            SettingsScreen(
                                controller = backupController,
                                restoreController = restoreController,
                                retentionController = retentionController,
                                appearanceController = appearanceController,
                            )
                        }
                    Screen.Import ->
                        SettingsPage(navigation) {
                            ImportSection(
                                importController = importController,
                                reviewController = reviewController,
                                confirmationController = confirmationController,
                                rollbackController = rollbackController,
                                unfinishedController = unfinishedController,
                                // The tab is `İçe/Dışa Aktarma`, so it is where
                                // the settings page reaches the export too; the
                                // table keeps its own button (PLAN 12.1).
                                exportAction = { TaskExportAction(exportController) },
                            )
                        }
                    // Keyed by the pool, so moving between two of them starts the
                    // new pool's reads and stops the old one's rather than
                    // leaving both running.
                    is Screen.Pool -> key(screen.poolType) { PoolScreen(poolControllers.of(screen.poolType)) }
                }
            }
        }
    }
}

/**
 * The one line of navigation, with the application's name at the start of it.
 *
 * It scrolls sideways rather than wrapping, so the smallest window PLAN 17 allows
 * with the text scaled up still reaches every entry instead of losing the last
 * one off the edge.
 */
@Composable
private fun TopNavigation(
    appInfo: AppInfo,
    navigation: AppNavigationState,
    summary: PoolNavigationSummary,
) {
    val navigationLabel = stringResource(Strings.Accessibility.navigationLabel)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .selectableGroup()
                    .semantics { contentDescription = navigationLabel },
        ) {
            Text(
                text = stringResource(Strings.App.name),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text(
                text = stringResource(Strings.App.versionLabel, appInfo.version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Screen.topLevel.forEach { screen ->
                NavigationEntry(
                    label = stringResource(textsOf(screen).navigationLabel),
                    // `Ayarlar` reads as selected while any of its tabs is open, so
                    // a user looking at the colours can still see where they are.
                    selected =
                        if (screen == Screen.Settings) {
                            navigation.currentScreen in Screen.underSettings
                        } else {
                            navigation.isCurrent(screen)
                        },
                    activeCount = (screen as? Screen.Pool)?.let { summary.activeCountOf(it.poolType) },
                    onSelect = { navigation.navigateTo(screen) },
                )
            }
        }
    }
}

/**
 * The settings page: its own tabs across the top, the open tab under them.
 *
 * `Ayarlar` in the navigation opens this page straight away rather than a menu.
 * The tabs are the settings themselves — appearance, backups — and the three
 * sections about the collection as a whole: import and export, the colours and
 * the history. They are drawn the way the navigation above them is, so which tab
 * is open is said by the underline and in words as well as by colour.
 */
@Composable
private fun SettingsPage(
    navigation: AppNavigationState,
    content: @Composable () -> Unit,
) {
    val sectionsLabel = stringResource(Strings.Navigation.settingsSections)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 16.dp, top = 8.dp)
                    .selectableGroup()
                    .semantics { contentDescription = sectionsLabel },
        ) {
            Screen.underSettings.forEach { screen ->
                NavigationEntry(
                    label = stringResource(settingsTabLabelOf(screen)),
                    selected = navigation.isCurrent(screen),
                    onSelect = { navigation.navigateTo(screen) },
                )
            }
        }
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/**
 * What a tab of the settings page is called. The settings' own tab is `Genel`,
 * because `Ayarlar` is already the name of the whole page.
 */
private fun settingsTabLabelOf(screen: Screen) =
    if (screen == Screen.Settings) Strings.Navigation.settingsGeneral else textsOf(screen).navigationLabel

/**
 * One entry of the navigation.
 *
 * The selected one is drawn in the accent colour **and** underlined, so which
 * section is open is never carried by colour alone (PLAN 17).
 *
 * A pool entry says how much work it is holding, which PLAN 9 asks for in the
 * number itself and not only in a screen reader: the badge is drawn, and the
 * count travels once more in the entry's spoken state. Drawn but not spoken,
 * because saying it in both places would say it twice.
 */
@Composable
private fun NavigationEntry(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    activeCount: Int? = null,
) {
    val selectionText =
        stringResource(if (selected) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
    val stateText =
        activeCount?.let { selectionText + ", " + stringResource(Strings.Pool.navActiveCount, it.toString()) }
            ?: selectionText
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick = onSelect,
            shape = NavigationItemShape,
            modifier =
                Modifier
                    .focusOutline(NavigationItemShape)
                    .semantics {
                        this.selected = selected
                        stateDescription = stateText
                    },
        ) {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (activeCount != null) {
                Text(
                    text = stringResource(Strings.Pool.navActiveBadge, activeCount.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).clearAndSetSemantics {},
                )
            }
        }
        // Always laid out and only changing colour, so selecting an entry does
        // not move the row it is in.
        Box(
            modifier =
                Modifier
                    .width(SelectionUnderlineWidth)
                    .height(SelectionUnderlineHeight)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clearAndSetSemantics {},
        )
    }
}

/**
 * Draws a ring around whatever holds keyboard focus.
 *
 * The border is always laid out and only changes colour, so focusing an entry
 * does not move the ones beside it.
 */
@Composable
private fun Modifier.focusOutline(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusEvent { focused = it.hasFocus }
        .border(
            width = 2.dp,
            color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = shape,
        )
}
