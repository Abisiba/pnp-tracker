package dev.pnptracker.ui

import dev.pnptracker.resources.Res
import dev.pnptracker.resources.app_name
import dev.pnptracker.resources.app_version_label
import dev.pnptracker.resources.app_window_title
import dev.pnptracker.resources.games_description
import dev.pnptracker.resources.games_title
import dev.pnptracker.resources.home_description
import dev.pnptracker.resources.home_title
import dev.pnptracker.resources.import_cancel
import dev.pnptracker.resources.import_choose_another_file
import dev.pnptracker.resources.import_choose_file
import dev.pnptracker.resources.import_choosing_file
import dev.pnptracker.resources.import_column_count_row
import dev.pnptracker.resources.import_column_counts_title
import dev.pnptracker.resources.import_description
import dev.pnptracker.resources.import_duplicate_count
import dev.pnptracker.resources.import_duplicate_entry
import dev.pnptracker.resources.import_duplicate_question
import dev.pnptracker.resources.import_duplicate_title
import dev.pnptracker.resources.import_error_damaged
import dev.pnptracker.resources.import_error_empty_sheet
import dev.pnptracker.resources.import_error_encrypted
import dev.pnptracker.resources.import_error_file_changed
import dev.pnptracker.resources.import_error_file_not_found
import dev.pnptracker.resources.import_error_legacy_xls
import dev.pnptracker.resources.import_error_not_readable
import dev.pnptracker.resources.import_error_not_xlsx
import dev.pnptracker.resources.import_error_safety_limit
import dev.pnptracker.resources.import_error_title
import dev.pnptracker.resources.import_error_unsupported_column
import dev.pnptracker.resources.import_error_unsupported_layout
import dev.pnptracker.resources.import_file_label
import dev.pnptracker.resources.import_game_cell_count
import dev.pnptracker.resources.import_green_hint_count
import dev.pnptracker.resources.import_idle_hint
import dev.pnptracker.resources.import_import_anyway
import dev.pnptracker.resources.import_multiline_cell_count
import dev.pnptracker.resources.import_nothing_created_note
import dev.pnptracker.resources.import_raw_block_count
import dev.pnptracker.resources.import_reading_file
import dev.pnptracker.resources.import_save_draft
import dev.pnptracker.resources.import_saved_summary
import dev.pnptracker.resources.import_saved_title
import dev.pnptracker.resources.import_saving
import dev.pnptracker.resources.import_sheet_accessibility_label
import dev.pnptracker.resources.import_sheet_cell_count
import dev.pnptracker.resources.import_sheet_label
import dev.pnptracker.resources.import_sheet_selection_title
import dev.pnptracker.resources.import_sheet_visibility_hidden
import dev.pnptracker.resources.import_sheet_visibility_very_hidden
import dev.pnptracker.resources.import_sheet_visibility_visible
import dev.pnptracker.resources.import_summary_title
import dev.pnptracker.resources.import_title
import dev.pnptracker.resources.import_warning_hidden_sheet
import dev.pnptracker.resources.import_warning_row_without_game_name
import dev.pnptracker.resources.import_warnings_title
import dev.pnptracker.resources.navigation_accessibility_label
import dev.pnptracker.resources.navigation_games
import dev.pnptracker.resources.navigation_home
import dev.pnptracker.resources.navigation_import
import dev.pnptracker.resources.navigation_section_label
import dev.pnptracker.resources.navigation_state_not_selected
import dev.pnptracker.resources.navigation_state_selected
import dev.pnptracker.resources.source_column_board
import dev.pnptracker.resources.source_column_borrowed
import dev.pnptracker.resources.source_column_card
import dev.pnptracker.resources.source_column_game
import dev.pnptracker.resources.source_column_missing
import dev.pnptracker.resources.source_column_special
import dev.pnptracker.resources.source_column_three_d
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

    object Import {
        val chooseFile = Res.string.import_choose_file
        val chooseAnotherFile = Res.string.import_choose_another_file
        val saveDraft = Res.string.import_save_draft
        val cancel = Res.string.import_cancel
        val importAnyway = Res.string.import_import_anyway

        val choosingFile = Res.string.import_choosing_file
        val readingFile = Res.string.import_reading_file
        val saving = Res.string.import_saving

        val idleHint = Res.string.import_idle_hint

        /** Takes the file name. */
        val fileLabel = Res.string.import_file_label

        /** Takes the sheet name. */
        val sheetLabel = Res.string.import_sheet_label
        val sheetSelectionTitle = Res.string.import_sheet_selection_title

        /** Takes the number of filled cells. */
        val sheetCellCount = Res.string.import_sheet_cell_count
        val summaryTitle = Res.string.import_summary_title

        /** Each takes a count. */
        val rawBlockCount = Res.string.import_raw_block_count
        val gameCellCount = Res.string.import_game_cell_count
        val greenHintCount = Res.string.import_green_hint_count
        val multiLineCellCount = Res.string.import_multiline_cell_count
        val columnCountsTitle = Res.string.import_column_counts_title

        /** Takes the column name and its count. */
        val columnCountRow = Res.string.import_column_count_row
        val nothingCreatedNote = Res.string.import_nothing_created_note

        val warningsTitle = Res.string.import_warnings_title

        /** Takes the affected row numbers. */
        val warningRowWithoutGameName = Res.string.import_warning_row_without_game_name
        val warningHiddenSheet = Res.string.import_warning_hidden_sheet

        val duplicateTitle = Res.string.import_duplicate_title

        /** Takes how many earlier imports were found. */
        val duplicateCount = Res.string.import_duplicate_count

        /** Takes the file name, the sheet name and the status. */
        val duplicateEntry = Res.string.import_duplicate_entry
        val duplicateQuestion = Res.string.import_duplicate_question

        val savedTitle = Res.string.import_saved_title

        /** Takes the file name, the sheet name and the number of cells. */
        val savedSummary = Res.string.import_saved_summary

        val sheetAccessibilityLabel = Res.string.import_sheet_accessibility_label
    }

    object SheetVisibilityNames {
        val visible = Res.string.import_sheet_visibility_visible
        val hidden = Res.string.import_sheet_visibility_hidden
        val veryHidden = Res.string.import_sheet_visibility_very_hidden
    }

    object SourceColumnNames {
        val game = Res.string.source_column_game
        val threeD = Res.string.source_column_three_d
        val card = Res.string.source_column_card
        val board = Res.string.source_column_board
        val special = Res.string.source_column_special
        val missing = Res.string.source_column_missing
        val borrowed = Res.string.source_column_borrowed
    }

    object ImportErrors {
        val title = Res.string.import_error_title
        val fileNotFound = Res.string.import_error_file_not_found
        val notReadable = Res.string.import_error_not_readable
        val notAnXlsxFile = Res.string.import_error_not_xlsx
        val legacyXls = Res.string.import_error_legacy_xls
        val damaged = Res.string.import_error_damaged
        val encrypted = Res.string.import_error_encrypted
        val safetyLimit = Res.string.import_error_safety_limit
        val fileChanged = Res.string.import_error_file_changed
        val unsupportedLayout = Res.string.import_error_unsupported_layout

        /** Takes the column number. */
        val unsupportedColumn = Res.string.import_error_unsupported_column
        val emptySheet = Res.string.import_error_empty_sheet
    }

    object Accessibility {
        val navigationLabel = Res.string.navigation_accessibility_label
        val selected = Res.string.navigation_state_selected
        val notSelected = Res.string.navigation_state_not_selected
    }
}
