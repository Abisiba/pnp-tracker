package dev.pnptracker.ui

import dev.pnptracker.resources.Res
import dev.pnptracker.resources.app_name
import dev.pnptracker.resources.app_version_label
import dev.pnptracker.resources.app_window_title
import dev.pnptracker.resources.games_description
import dev.pnptracker.resources.games_title
import dev.pnptracker.resources.home_description
import dev.pnptracker.resources.home_title
import dev.pnptracker.resources.import_description
import dev.pnptracker.resources.import_title
import dev.pnptracker.resources.navigation_accessibility_label
import dev.pnptracker.resources.navigation_games
import dev.pnptracker.resources.navigation_home
import dev.pnptracker.resources.navigation_import
import dev.pnptracker.resources.navigation_section_label
import dev.pnptracker.resources.navigation_state_not_selected
import dev.pnptracker.resources.navigation_state_selected
import dev.pnptracker.resources.theme_section_label
import dev.pnptracker.resources.theme_switch_to_dark
import dev.pnptracker.resources.theme_switch_to_light

/**
 * Every text the user can read, in one place.
 *
 * The words themselves live in `composeResources/values/strings.xml`; this
 * object only gives them grouped, compile-checked names so a screen never spells
 * out Turkish inside a layout. Adding a second language means adding a second
 * values folder, and nothing here changes.
 *
 * Route names, SQL, test fixtures and developer facing error messages are not
 * user text and do not belong here.
 */
object Strings {
    object App {
        val name = Res.string.app_name
        val windowTitle = Res.string.app_window_title

        /** Takes the version as its single argument. */
        val versionLabel = Res.string.app_version_label
    }

    object Navigation {
        val sectionLabel = Res.string.navigation_section_label
        val home = Res.string.navigation_home
        val games = Res.string.navigation_games
        val importReview = Res.string.navigation_import
    }

    object ScreenTitles {
        val home = Res.string.home_title
        val games = Res.string.games_title
        val importReview = Res.string.import_title
    }

    object ScreenDescriptions {
        val home = Res.string.home_description
        val games = Res.string.games_description
        val importReview = Res.string.import_description
    }

    object Theme {
        val sectionLabel = Res.string.theme_section_label
        val switchToDark = Res.string.theme_switch_to_dark
        val switchToLight = Res.string.theme_switch_to_light
    }

    object Accessibility {
        val navigationLabel = Res.string.navigation_accessibility_label
        val selected = Res.string.navigation_state_selected
        val notSelected = Res.string.navigation_state_not_selected
    }
}
