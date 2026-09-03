package dev.pnptracker.ui.navigation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import dev.pnptracker.ui.feature.home.HomeScreen
import dev.pnptracker.ui.feature.importreview.ImportController
import dev.pnptracker.ui.feature.importworkspace.ImportConfirmationController
import dev.pnptracker.ui.feature.importworkspace.ImportReviewController
import dev.pnptracker.ui.feature.importworkspace.ImportSection
import dev.pnptracker.ui.feature.pools.PoolControllers
import dev.pnptracker.ui.feature.pools.PoolScreen
import dev.pnptracker.ui.textsOf
import dev.pnptracker.ui.theme.ThemeMode
import org.jetbrains.compose.resources.stringResource

private val SidebarWidth = 236.dp
private val NavigationItemShape = RoundedCornerShape(28.dp)
private val SelectionMarkerWidth = 14.dp

/** Shown before the name of the section that is open. */
private const val SELECTION_MARKER = "\u25B8"

/**
 * The window: a permanent sidebar on the left, the open section on the right.
 */
@Composable
fun AppScaffold(
    appInfo: AppInfo,
    navigation: AppNavigationState,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    importController: ImportController,
    reviewController: ImportReviewController,
    confirmationController: ImportConfirmationController,
    gameTableController: GameTableController,
    exportController: ExportController,
    colorCatalogueController: ColorCatalogueController,
    poolControllers: PoolControllers,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(poolControllers) { poolControllers.observeNavigationSummary() }
    val summary = poolControllers.summary
    // The Special pool can stop being offered while it is the section on screen:
    // its last task deleted, or the game it was in removed. Standing on a section
    // that is no longer there would leave a screen nothing can navigate away from
    // by its own name, so the window moves to the 3D pool and says nothing more
    // about it (PLAN 9 hides the pool; it does not explain the hiding).
    LaunchedEffect(summary.showsSpecial, navigation.currentScreen) {
        if (!summary.showsSpecial && navigation.currentScreen == Screen.Pool(PoolType.SPECIAL)) {
            navigation.navigateTo(Screen.threeDPool)
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationSidebar(
                appInfo = appInfo,
                navigation = navigation,
                summary = summary,
                themeMode = themeMode,
                onToggleTheme = onToggleTheme,
            )
            VerticalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (val screen = navigation.currentScreen) {
                    Screen.Home -> HomeScreen()
                    Screen.Games ->
                        GameTableScreen(
                            controller = gameTableController,
                            exportAction = { TaskExportAction(exportController) },
                        )
                    Screen.Colors -> ColorCatalogueScreen(colorCatalogueController)
                    Screen.Import -> ImportSection(importController, reviewController, confirmationController)
                    // Keyed by the pool, so moving between two of them starts the
                    // new pool's reads and stops the old one's rather than
                    // leaving both running.
                    is Screen.Pool -> key(screen.poolType) { PoolScreen(poolControllers.of(screen.poolType)) }
                }
            }
        }
    }
}

@Composable
private fun NavigationSidebar(
    appInfo: AppInfo,
    navigation: AppNavigationState,
    summary: PoolNavigationSummary,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
) {
    val navigationLabel = stringResource(Strings.Accessibility.navigationLabel)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.width(SidebarWidth).fillMaxHeight(),
    ) {
        Column(modifier = Modifier.fillMaxHeight().padding(vertical = 16.dp)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = stringResource(Strings.App.name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Strings.App.versionLabel, appInfo.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The list takes whatever height is left and scrolls inside it, so a
            // short window never pushes the theme control off the bottom.
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 20.dp)
                        .selectableGroup()
                        .semantics { contentDescription = navigationLabel },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Strings.Navigation.sectionLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                )
                Screen.offered(summary).forEach { screen ->
                    NavigationEntry(
                        screen = screen,
                        selected = navigation.isCurrent(screen),
                        activeCount = (screen as? Screen.Pool)?.let { summary.activeCountOf(it.poolType) },
                        onSelect = { navigation.navigateTo(screen) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            ThemeControl(themeMode = themeMode, onToggleTheme = onToggleTheme)
        }
    }
}

@Composable
private fun NavigationEntry(
    screen: Screen,
    selected: Boolean,
    activeCount: Int?,
    onSelect: () -> Unit,
) {
    val selectionText =
        stringResource(if (selected) Strings.Accessibility.selected else Strings.Accessibility.notSelected)
    // How much work a pool is holding is part of what the entry says, so a
    // reader hears it without opening the pool. PLAN 9 keeps the Special pool
    // showing `0 aktif` after its last task is finished, which is exactly the
    // number this carries.
    val stateText =
        activeCount?.let { selectionText + ", " + stringResource(Strings.Pool.navActiveCount, it.toString()) }
            ?: selectionText
    NavigationDrawerItem(
        selected = selected,
        onClick = onSelect,
        // PLAN 9 asks for the number itself to be on screen: the Special pool
        // stays offered after its last task is done and says `0 aktif`, which
        // only means anything if it can be seen. The reader already hears the
        // count from the entry's state, so the badge is drawn and not spoken —
        // otherwise it would be said twice.
        badge =
            activeCount?.let { count ->
                {
                    Text(
                        text = stringResource(Strings.Pool.navActiveBadge, count.toString()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            },
        icon = {
            // A shape, not only a colour, says which section is open.
            Box(modifier = Modifier.width(SelectionMarkerWidth)) {
                if (selected) Text(text = SELECTION_MARKER)
            }
        },
        label = {
            Text(
                text = stringResource(textsOf(screen).navigationLabel),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        modifier =
            Modifier
                .padding(horizontal = 12.dp)
                .focusOutline(NavigationItemShape)
                .semantics { stateDescription = stateText },
    )
}

@Composable
private fun ThemeControl(
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(
            text = stringResource(Strings.Theme.sectionLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
        // The label names the theme it switches to, so the control is readable
        // without seeing which one is active.
        TextButton(
            onClick = onToggleTheme,
            modifier = Modifier.fillMaxWidth().focusOutline(NavigationItemShape),
        ) {
            Text(
                text =
                    stringResource(
                        when (themeMode) {
                            ThemeMode.LIGHT -> Strings.Theme.switchToDark
                            ThemeMode.DARK -> Strings.Theme.switchToLight
                        },
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Draws a ring around whatever holds keyboard focus.
 *
 * The border is always laid out and only changes colour, so focusing an entry
 * does not move the ones below it.
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
