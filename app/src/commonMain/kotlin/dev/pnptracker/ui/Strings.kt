package dev.pnptracker.ui

import dev.pnptracker.resources.Res
import dev.pnptracker.resources.aim_cell_label
import dev.pnptracker.resources.aim_choose_cell
import dev.pnptracker.resources.aim_choose_pool
import dev.pnptracker.resources.aim_choose_tracking
import dev.pnptracker.resources.aim_materialized
import dev.pnptracker.resources.aim_no_cells
import dev.pnptracker.resources.aim_none
import dev.pnptracker.resources.aim_ready
import dev.pnptracker.resources.aim_title
import dev.pnptracker.resources.app_name
import dev.pnptracker.resources.app_version_label
import dev.pnptracker.resources.app_window_title
import dev.pnptracker.resources.colors_count
import dev.pnptracker.resources.colors_create
import dev.pnptracker.resources.colors_description
import dev.pnptracker.resources.colors_discard
import dev.pnptracker.resources.colors_error_color_gone
import dev.pnptracker.resources.colors_error_could_not_save
import dev.pnptracker.resources.colors_error_name_is_alias
import dev.pnptracker.resources.colors_error_name_used
import dev.pnptracker.resources.colors_hex_hint
import dev.pnptracker.resources.colors_hex_invalid
import dev.pnptracker.resources.colors_hex_label
import dev.pnptracker.resources.colors_hex_shared
import dev.pnptracker.resources.colors_loading
import dev.pnptracker.resources.colors_name_label
import dev.pnptracker.resources.colors_name_required
import dev.pnptracker.resources.colors_preview
import dev.pnptracker.resources.colors_save
import dev.pnptracker.resources.colors_swatch
import dev.pnptracker.resources.colors_title
import dev.pnptracker.resources.column_notes
import dev.pnptracker.resources.confirm_action
import dev.pnptracker.resources.confirm_dialog_accept
import dev.pnptracker.resources.confirm_dialog_body
import dev.pnptracker.resources.confirm_dialog_cancel
import dev.pnptracker.resources.confirm_dialog_cancel_note
import dev.pnptracker.resources.confirm_dialog_title
import dev.pnptracker.resources.confirm_done_body
import dev.pnptracker.resources.confirm_done_title
import dev.pnptracker.resources.confirm_error_already_confirmed
import dev.pnptracker.resources.confirm_error_batch_not_found
import dev.pnptracker.resources.confirm_error_could_not_save
import dev.pnptracker.resources.confirm_error_no_cells
import dev.pnptracker.resources.confirm_error_no_drafts
import dev.pnptracker.resources.confirm_error_not_a_draft
import dev.pnptracker.resources.confirm_error_pool_missing
import dev.pnptracker.resources.confirm_error_target_missing
import dev.pnptracker.resources.confirm_error_target_not_task_capable
import dev.pnptracker.resources.confirm_error_target_unavailable
import dev.pnptracker.resources.confirm_error_target_wrong_column
import dev.pnptracker.resources.confirm_error_tracking_missing
import dev.pnptracker.resources.confirm_error_unprocessed
import dev.pnptracker.resources.confirm_loading
import dev.pnptracker.resources.confirm_no_games_created
import dev.pnptracker.resources.confirm_problems_title
import dev.pnptracker.resources.confirm_read_only
import dev.pnptracker.resources.confirm_ready
import dev.pnptracker.resources.confirm_running
import dev.pnptracker.resources.confirm_section_title
import dev.pnptracker.resources.confirm_summary
import dev.pnptracker.resources.confirm_unavailable
import dev.pnptracker.resources.confirm_unprocessed_acknowledge
import dev.pnptracker.resources.confirm_unprocessed_warning
import dev.pnptracker.resources.games_back
import dev.pnptracker.resources.games_cell_open
import dev.pnptracker.resources.games_cells_empty
import dev.pnptracker.resources.games_cells_empty_hint
import dev.pnptracker.resources.games_cells_title
import dev.pnptracker.resources.games_completed_badge
import dev.pnptracker.resources.games_completion_note
import dev.pnptracker.resources.games_create
import dev.pnptracker.resources.games_description
import dev.pnptracker.resources.games_discard
import dev.pnptracker.resources.games_empty_hint
import dev.pnptracker.resources.games_empty_title
import dev.pnptracker.resources.games_error_could_not_save
import dev.pnptracker.resources.games_error_game_unavailable
import dev.pnptracker.resources.games_loading
import dev.pnptracker.resources.games_mark_active
import dev.pnptracker.resources.games_mark_completed
import dev.pnptracker.resources.games_name_label
import dev.pnptracker.resources.games_name_required
import dev.pnptracker.resources.games_open
import dev.pnptracker.resources.games_save
import dev.pnptracker.resources.games_title
import dev.pnptracker.resources.games_unavailable
import dev.pnptracker.resources.games_unavailable_title
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
import dev.pnptracker.resources.navigation_colors
import dev.pnptracker.resources.navigation_games
import dev.pnptracker.resources.navigation_home
import dev.pnptracker.resources.navigation_import
import dev.pnptracker.resources.navigation_section_label
import dev.pnptracker.resources.navigation_state_not_selected
import dev.pnptracker.resources.navigation_state_selected
import dev.pnptracker.resources.pool_board
import dev.pnptracker.resources.pool_card
import dev.pnptracker.resources.pool_special
import dev.pnptracker.resources.pool_three_d
import dev.pnptracker.resources.review_back
import dev.pnptracker.resources.review_cell_location
import dev.pnptracker.resources.review_completion_hint_pending
import dev.pnptracker.resources.review_could_not_save
import dev.pnptracker.resources.review_create_draft
import dev.pnptracker.resources.review_draft_count
import dev.pnptracker.resources.review_draft_discard
import dev.pnptracker.resources.review_draft_form_title
import dev.pnptracker.resources.review_draft_name_label
import dev.pnptracker.resources.review_draft_name_required
import dev.pnptracker.resources.review_draft_only_note
import dev.pnptracker.resources.review_draft_save
import dev.pnptracker.resources.review_draft_source
import dev.pnptracker.resources.review_drafts_empty
import dev.pnptracker.resources.review_drafts_empty_hint
import dev.pnptracker.resources.review_drafts_of_selected
import dev.pnptracker.resources.review_drafts_title
import dev.pnptracker.resources.review_empty
import dev.pnptracker.resources.review_empty_title
import dev.pnptracker.resources.review_green_hint_pending
import dev.pnptracker.resources.review_loading
import dev.pnptracker.resources.review_mark_processed
import dev.pnptracker.resources.review_mark_unprocessed
import dev.pnptracker.resources.review_no_real_records
import dev.pnptracker.resources.review_open
import dev.pnptracker.resources.review_processed_accessibility
import dev.pnptracker.resources.review_processed_badge
import dev.pnptracker.resources.review_progress
import dev.pnptracker.resources.review_raw_blocks_title
import dev.pnptracker.resources.review_resumable_entry
import dev.pnptracker.resources.review_resumable_hint
import dev.pnptracker.resources.review_resumable_title
import dev.pnptracker.resources.review_select_cell_accessibility
import dev.pnptracker.resources.review_title
import dev.pnptracker.resources.review_unavailable
import dev.pnptracker.resources.review_unavailable_title
import dev.pnptracker.resources.source_column_board
import dev.pnptracker.resources.source_column_borrowed
import dev.pnptracker.resources.source_column_card
import dev.pnptracker.resources.source_column_game
import dev.pnptracker.resources.source_column_missing
import dev.pnptracker.resources.source_column_special
import dev.pnptracker.resources.source_column_three_d
import dev.pnptracker.resources.tasks_cell_label
import dev.pnptracker.resources.tasks_cell_required
import dev.pnptracker.resources.tasks_create
import dev.pnptracker.resources.tasks_empty
import dev.pnptracker.resources.tasks_empty_hint
import dev.pnptracker.resources.tasks_error_cell_holds_no_tasks
import dev.pnptracker.resources.tasks_error_cell_pool_mismatch
import dev.pnptracker.resources.tasks_error_cell_unavailable
import dev.pnptracker.resources.tasks_error_could_not_save
import dev.pnptracker.resources.tasks_loading
import dev.pnptracker.resources.tasks_name_label
import dev.pnptracker.resources.tasks_name_required
import dev.pnptracker.resources.tasks_needs_cell
import dev.pnptracker.resources.tasks_notes_label
import dev.pnptracker.resources.tasks_pool_label
import dev.pnptracker.resources.tasks_pool_required
import dev.pnptracker.resources.tasks_quantity_hint
import dev.pnptracker.resources.tasks_quantity_label
import dev.pnptracker.resources.tasks_quantity_unusable
import dev.pnptracker.resources.tasks_row_column
import dev.pnptracker.resources.tasks_row_from_import
import dev.pnptracker.resources.tasks_row_quantity
import dev.pnptracker.resources.tasks_row_quantity_unknown
import dev.pnptracker.resources.tasks_save
import dev.pnptracker.resources.tasks_title
import dev.pnptracker.resources.tasks_tracking_label
import dev.pnptracker.resources.tasks_tracking_required
import dev.pnptracker.resources.theme_section_label
import dev.pnptracker.resources.theme_switch_to_dark
import dev.pnptracker.resources.theme_switch_to_light
import dev.pnptracker.resources.tracking_checklist
import dev.pnptracker.resources.tracking_counted
import dev.pnptracker.resources.tracking_pipeline
import dev.pnptracker.resources.tracking_three_d_batch

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
        val colors = Res.string.navigation_colors
    }

    object ScreenTitles {
        val home = Res.string.home_title
        val games = Res.string.games_title
        val importReview = Res.string.import_title
        val colors = Res.string.colors_title
    }

    object ScreenDescriptions {
        val home = Res.string.home_description
        val games = Res.string.games_description
        val importReview = Res.string.import_description
        val colors = Res.string.colors_description
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

    /** Setting up the games the user tracks and the cells in them. */
    object Games {
        val loading = Res.string.games_loading
        val emptyTitle = Res.string.games_empty_title
        val emptyHint = Res.string.games_empty_hint

        val create = Res.string.games_create
        val nameLabel = Res.string.games_name_label
        val nameRequired = Res.string.games_name_required
        val save = Res.string.games_save
        val discard = Res.string.games_discard

        val open = Res.string.games_open
        val back = Res.string.games_back

        val completedBadge = Res.string.games_completed_badge
        val markCompleted = Res.string.games_mark_completed
        val markActive = Res.string.games_mark_active
        val completionNote = Res.string.games_completion_note

        val unavailableTitle = Res.string.games_unavailable_title
        val unavailable = Res.string.games_unavailable

        val cellsTitle = Res.string.games_cells_title
        val cellsEmpty = Res.string.games_cells_empty
        val cellsEmptyHint = Res.string.games_cells_empty_hint
        val cellOpen = Res.string.games_cell_open

        val errorCouldNotSave = Res.string.games_error_could_not_save
        val errorGameUnavailable = Res.string.games_error_game_unavailable
    }

    /**
     * The tasks section of the game detail screen.
     *
     * There is no word for "finished" anywhere in here, because the section does
     * not show one; a task's completion is worked out from pool counters that do
     * not exist yet.
     */
    object Tasks {
        val title = Res.string.tasks_title
        val loading = Res.string.tasks_loading
        val empty = Res.string.tasks_empty
        val emptyHint = Res.string.tasks_empty_hint
        val needsCell = Res.string.tasks_needs_cell

        val create = Res.string.tasks_create
        val nameLabel = Res.string.tasks_name_label
        val nameRequired = Res.string.tasks_name_required
        val cellLabel = Res.string.tasks_cell_label
        val cellRequired = Res.string.tasks_cell_required
        val poolLabel = Res.string.tasks_pool_label
        val poolRequired = Res.string.tasks_pool_required
        val trackingLabel = Res.string.tasks_tracking_label
        val trackingRequired = Res.string.tasks_tracking_required
        val quantityLabel = Res.string.tasks_quantity_label
        val quantityHint = Res.string.tasks_quantity_hint
        val quantityUnusable = Res.string.tasks_quantity_unusable
        val notesLabel = Res.string.tasks_notes_label
        val save = Res.string.tasks_save

        val rowColumn = Res.string.tasks_row_column
        val rowQuantity = Res.string.tasks_row_quantity
        val rowQuantityUnknown = Res.string.tasks_row_quantity_unknown
        val rowFromImport = Res.string.tasks_row_from_import

        val errorCouldNotSave = Res.string.tasks_error_could_not_save
        val errorCellUnavailable = Res.string.tasks_error_cell_unavailable
        val errorCellHoldsNoTasks = Res.string.tasks_error_cell_holds_no_tasks
        val errorCellPoolMismatch = Res.string.tasks_error_cell_pool_mismatch
    }

    /** The four production pools, under the names PLAN 3.4 gives them. */
    object Pools {
        val threeD = Res.string.pool_three_d
        val card = Res.string.pool_card
        val board = Res.string.pool_board
        val special = Res.string.pool_special
    }

    /** How progress on a task is counted. */
    object Tracking {
        val threeDBatch = Res.string.tracking_three_d_batch
        val pipeline = Res.string.tracking_pipeline
        val checklist = Res.string.tracking_checklist
        val counted = Res.string.tracking_counted
    }

    /**
     * The colour catalogue section.
     *
     * Every swatch the section draws is named in words beside it, because PLAN 17
     * does not let a colour be the only thing carrying a meaning.
     */
    object Colors {
        val loading = Res.string.colors_loading
        val count = Res.string.colors_count

        val create = Res.string.colors_create
        val nameLabel = Res.string.colors_name_label
        val nameRequired = Res.string.colors_name_required
        val hexLabel = Res.string.colors_hex_label
        val hexHint = Res.string.colors_hex_hint
        val hexInvalid = Res.string.colors_hex_invalid
        val hexShared = Res.string.colors_hex_shared
        val preview = Res.string.colors_preview
        val swatch = Res.string.colors_swatch
        val save = Res.string.colors_save
        val discard = Res.string.colors_discard

        val errorCouldNotSave = Res.string.colors_error_could_not_save
        val errorNameUsed = Res.string.colors_error_name_used
        val errorNameIsAlias = Res.string.colors_error_name_is_alias
        val errorColorGone = Res.string.colors_error_color_gone
    }

    /** The columns of the game table. */
    object Columns {
        val notes = Res.string.column_notes
    }

    /** Turning a reviewed import into real tasks. */
    object Confirm {
        val sectionTitle = Res.string.confirm_section_title
        val loading = Res.string.confirm_loading
        val unavailable = Res.string.confirm_unavailable

        /** Takes how many drafts there are and how many are ready. */
        val summary = Res.string.confirm_summary

        /** Takes how many tasks would be created. */
        val ready = Res.string.confirm_ready

        val noGamesCreated = Res.string.confirm_no_games_created
        val action = Res.string.confirm_action
        val dialogTitle = Res.string.confirm_dialog_title

        /** Takes how many tasks would be created. */
        val dialogBody = Res.string.confirm_dialog_body

        val dialogAccept = Res.string.confirm_dialog_accept
        val dialogCancel = Res.string.confirm_dialog_cancel
        val dialogCancelNote = Res.string.confirm_dialog_cancel_note
        val running = Res.string.confirm_running

        /** Takes how many cells are still unreviewed. */
        val unprocessedWarning = Res.string.confirm_unprocessed_warning

        val unprocessedAcknowledge = Res.string.confirm_unprocessed_acknowledge
        val doneTitle = Res.string.confirm_done_title

        /** Takes how many tasks and how many games were created. */
        val doneBody = Res.string.confirm_done_body

        val readOnly = Res.string.confirm_read_only
        val problemsTitle = Res.string.confirm_problems_title

        val errorBatchNotFound = Res.string.confirm_error_batch_not_found
        val errorAlreadyConfirmed = Res.string.confirm_error_already_confirmed
        val errorNotADraft = Res.string.confirm_error_not_a_draft
        val errorNoDrafts = Res.string.confirm_error_no_drafts
        val errorNoCells = Res.string.confirm_error_no_cells
        val errorUnprocessed = Res.string.confirm_error_unprocessed
        val errorTargetMissing = Res.string.confirm_error_target_missing
        val errorTargetUnavailable = Res.string.confirm_error_target_unavailable
        val errorTargetNotTaskCapable = Res.string.confirm_error_target_not_task_capable
        val errorTargetWrongColumn = Res.string.confirm_error_target_wrong_column
        val errorPoolMissing = Res.string.confirm_error_pool_missing
        val errorTrackingMissing = Res.string.confirm_error_tracking_missing
        val errorCouldNotSave = Res.string.confirm_error_could_not_save
    }

    /** Choosing which cell one draft's task will be written in. */
    object Aim {
        val title = Res.string.aim_title
        val none = Res.string.aim_none

        /** Takes the game name and the column name. */
        val cellLabel = Res.string.aim_cell_label

        val chooseCell = Res.string.aim_choose_cell
        val choosePool = Res.string.aim_choose_pool
        val chooseTracking = Res.string.aim_choose_tracking
        val ready = Res.string.aim_ready
        val noCells = Res.string.aim_no_cells
        val materialized = Res.string.aim_materialized
    }

    /** The two-pane workspace where a saved import is reviewed. */
    object Review {
        val title = Res.string.review_title
        val open = Res.string.review_open
        val back = Res.string.review_back

        val resumableTitle = Res.string.review_resumable_title
        val resumableHint = Res.string.review_resumable_hint

        /** Takes the file name and the sheet name. */
        val resumableEntry = Res.string.review_resumable_entry

        val loading = Res.string.review_loading
        val unavailableTitle = Res.string.review_unavailable_title
        val unavailable = Res.string.review_unavailable
        val emptyTitle = Res.string.review_empty_title

        /** Takes the file name and the sheet name. */
        val empty = Res.string.review_empty

        val rawBlocksTitle = Res.string.review_raw_blocks_title
        val draftsTitle = Res.string.review_drafts_title
        val draftsOfSelected = Res.string.review_drafts_of_selected
        val draftsEmpty = Res.string.review_drafts_empty
        val draftsEmptyHint = Res.string.review_drafts_empty_hint

        val markProcessed = Res.string.review_mark_processed
        val markUnprocessed = Res.string.review_mark_unprocessed
        val processedBadge = Res.string.review_processed_badge

        /** Takes the one based row and column numbers. */
        val cellLocation = Res.string.review_cell_location

        /** Takes how many cells are marked done and how many there are. */
        val progress = Res.string.review_progress

        /** Takes how many drafts there are. */
        val draftCount = Res.string.review_draft_count

        val couldNotSave = Res.string.review_could_not_save
        val greenHintPending = Res.string.review_green_hint_pending
        val completionHintPending = Res.string.review_completion_hint_pending
        val noRealRecords = Res.string.review_no_real_records

        val createDraft = Res.string.review_create_draft
        val draftFormTitle = Res.string.review_draft_form_title
        val draftNameLabel = Res.string.review_draft_name_label

        /** Takes the one based row and column numbers of the cell it came from. */
        val draftSource = Res.string.review_draft_source
        val draftSave = Res.string.review_draft_save
        val draftDiscard = Res.string.review_draft_discard
        val draftNameRequired = Res.string.review_draft_name_required
        val draftOnlyNote = Res.string.review_draft_only_note

        val selectCellAccessibility = Res.string.review_select_cell_accessibility
        val processedAccessibility = Res.string.review_processed_accessibility
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
