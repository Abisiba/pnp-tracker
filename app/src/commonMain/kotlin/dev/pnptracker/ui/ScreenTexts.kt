package dev.pnptracker.ui

import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.ui.navigation.Screen
import org.jetbrains.compose.resources.StringResource

/**
 * What a screen is called and what it is for.
 *
 * The navigation name and the heading are separate entries so they can diverge
 * per language, even though they read the same in Turkish today.
 */
data class ScreenTexts(
    val navigationLabel: StringResource,
    val title: StringResource,
    val description: StringResource,
)

/**
 * The texts of [screen].
 *
 * Exhaustive on purpose: a new screen will not compile until it has been given
 * a name and a description.
 */
fun textsOf(screen: Screen): ScreenTexts =
    when (screen) {
        Screen.Home ->
            ScreenTexts(
                navigationLabel = Strings.Navigation.home,
                title = Strings.ScreenTitles.home,
                description = Strings.ScreenDescriptions.home,
            )

        Screen.Games ->
            ScreenTexts(
                navigationLabel = Strings.Navigation.games,
                title = Strings.ScreenTitles.games,
                description = Strings.ScreenDescriptions.games,
            )

        Screen.Import ->
            ScreenTexts(
                navigationLabel = Strings.Navigation.importReview,
                title = Strings.ScreenTitles.importReview,
                description = Strings.ScreenDescriptions.importReview,
            )

        Screen.Colors ->
            ScreenTexts(
                navigationLabel = Strings.Navigation.colors,
                title = Strings.ScreenTitles.colors,
                description = Strings.ScreenDescriptions.colors,
            )

        // The sidebar names a pool in the plural the plan uses for the section
        // (`Kartlar`), while the table column beside it names the one kind of
        // work a cell holds (`Kart`). The heading over the pool is the section's
        // own name, so both readings stay whatever the catalogue says they are.
        is Screen.Pool ->
            ScreenTexts(
                navigationLabel = poolNavigationNameOf(screen.poolType),
                title = poolNavigationNameOf(screen.poolType),
                description = poolDescriptionOf(screen.poolType),
            )
    }

/** What the sidebar calls one pool. */
fun poolNavigationNameOf(poolType: PoolType): StringResource =
    when (poolType) {
        PoolType.THREE_D -> Strings.Pool.navThreeD
        PoolType.CARD -> Strings.Pool.navCard
        PoolType.BOARD -> Strings.Pool.navBoard
        PoolType.SPECIAL -> Strings.Pool.navSpecial
    }

/** What one pool holds, in a sentence under its heading. */
fun poolDescriptionOf(poolType: PoolType): StringResource =
    when (poolType) {
        PoolType.THREE_D -> Strings.Pool.introThreeD
        PoolType.CARD -> Strings.Pool.introCard
        PoolType.BOARD -> Strings.Pool.introBoard
        PoolType.SPECIAL -> Strings.Pool.introSpecial
    }
