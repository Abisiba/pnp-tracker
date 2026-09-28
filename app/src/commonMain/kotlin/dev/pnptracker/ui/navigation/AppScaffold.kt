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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.pools.PoolNavigationSummary
import dev.pnptracker.ui.Strings
import dev.pnptracker.ui.feature.colors.ColorCatalogueController
import dev.pnptracker.ui.feature.colors.ColorCatalogueScreen
import dev.pnptracker.ui.feature.export.ExportController
import dev.pnptracker.ui.feature.export.TaskExportAction
import dev.pnptracker.ui.feature.games.GameTableController
import dev.pnptracker.ui.feature.games.GameTableScreen
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
import org.jetbrains.compose.resources.stringResource

private val NavigationItemShape = RoundedCornerShape(12.dp)
private val SelectionUnderlineHeight = 3.dp
private val SelectionUnderlineWidth = 24.dp

/**
 * The window: one short line of navigation across the top, the open section
 * under it (PLAN 12.1).
 *
 * There is no sidebar and no home page. The table is the surface the application
 * is worked from, so it gets the whole width, and the five entries above it are
 * the five places PLAN 12.1 names — with the rest of the sections in the menu
 * under `Ayarlar`.
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
    // The Special pool can stop being reachable while it is the section on
    // screen: its last task deleted, or the game it was in removed. Standing on a
    // section nothing can name any more would leave a screen with no way out by
    // its own name, so the window moves to the 3D pool and says nothing more
    // about it (PLAN 9 hides the pool; it does not explain the hiding).
    LaunchedEffect(summary.showsSpecial, navigation.currentScreen) {
        if (!summary.showsSpecial && navigation.currentScreen == Screen.specialPool) {
            navigation.navigateTo(Screen.threeDPool)
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopNavigation(appInfo = appInfo, navigation = navigation, summary = summary)
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val screen = navigation.currentScreen) {
                    Screen.Games ->
                        GameTableScreen(
                            controller = gameTableController,
                            exportAction = { TaskExportAction(exportController) },
                            // The only way to the Special pool, because the
                            // navigation across the top does not carry it.
                            specialAction = {
                                if (Screen.specialPool in Screen.offered(summary)) {
                                    SpecialPoolEntry(
                                        activeCount = summary.activeCountOf(PoolType.SPECIAL),
                                        onOpen = { navigation.navigateTo(Screen.specialPool) },
                                    )
                                }
                            },
                        )
                    Screen.History -> HistoryScreen(historyController)
                    Screen.Colors -> ColorCatalogueScreen(colorCatalogueController)
                    Screen.Settings ->
                        SettingsScreen(
                            controller = backupController,
                            restoreController = restoreController,
                            retentionController = retentionController,
                            appearanceController = appearanceController,
                        )
                    Screen.Import ->
                        ImportSection(
                            importController = importController,
                            reviewController = reviewController,
                            confirmationController = confirmationController,
                            rollbackController = rollbackController,
                            unfinishedController = unfinishedController,
                            // The section is `İçe/Dışa Aktarma`, so it is where
                            // the menu under `Ayarlar` reaches the export too; the
                            // table keeps its own button (PLAN 12.1).
                            exportAction = { TaskExportAction(exportController) },
                        )
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
                if (screen == Screen.Settings) {
                    SettingsMenu(navigation = navigation)
                } else {
                    NavigationEntry(
                        label = stringResource(textsOf(screen).navigationLabel),
                        selected = navigation.isCurrent(screen),
                        activeCount = (screen as? Screen.Pool)?.let { summary.activeCountOf(it.poolType) },
                        onSelect = { navigation.navigateTo(screen) },
                    )
                }
            }
        }
    }
}

/**
 * `Ayarlar`, and the sections that live under it (PLAN 12.1).
 *
 * The entry reads as selected while any of them is open, so a user looking at the
 * colours can still see which of the five they are inside.
 */
@Composable
private fun SettingsMenu(navigation: AppNavigationState) {
    var open by remember { mutableStateOf(false) }
    val menuLabel = stringResource(Strings.Navigation.settingsMenu)
    Box {
        NavigationEntry(
            label = stringResource(Strings.Navigation.settings),
            selected = navigation.currentScreen in Screen.underSettings,
            onSelect = { open = true },
            contentDescription = menuLabel,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Screen.underSettings.forEach { screen ->
                val selected = navigation.isCurrent(screen)
                val selectionText =
                    stringResource(if (selected) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(textsOf(screen).navigationLabel),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        open = false
                        navigation.navigateTo(screen)
                    },
                    modifier =
                        Modifier.semantics {
                            this.selected = selected
                            stateDescription = selectionText
                        },
                )
            }
        }
    }
}

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
    contentDescription: String? = null,
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
                        if (contentDescription != null) this.contentDescription = contentDescription
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
 * The way into the Special pool, drawn with the table's own controls.
 *
 * PLAN 9 keeps the pool out of sight until there is special work; PLAN 12.1 keeps
 * it out of the navigation across the top. So it is reached from the table the
 * work is in, and it says how much work that is, the way the sidebar used to.
 */
@Composable
private fun SpecialPoolEntry(
    activeCount: Int,
    onOpen: () -> Unit,
) {
    TextButton(onClick = onOpen, modifier = Modifier.focusOutline(NavigationItemShape)) {
        Text(
            text =
                stringResource(Strings.Table.openSpecial) + " — " +
                    stringResource(Strings.Pool.navActiveBadge, activeCount.toString()),
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
