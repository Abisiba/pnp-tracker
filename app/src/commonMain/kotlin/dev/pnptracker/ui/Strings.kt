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
import dev.pnptracker.resources.cell_discard
import dev.pnptracker.resources.cell_edit
import dev.pnptracker.resources.cell_edit_action
import dev.pnptracker.resources.cell_editor_hint
import dev.pnptracker.resources.cell_editor_open_elsewhere
import dev.pnptracker.resources.cell_error_cell_gone
import dev.pnptracker.resources.cell_error_could_not_save
import dev.pnptracker.resources.cell_error_crosses_task
import dev.pnptracker.resources.cell_error_game_gone
import dev.pnptracker.resources.cell_error_stale_document
import dev.pnptracker.resources.cell_save
import dev.pnptracker.resources.cell_saving
import dev.pnptracker.resources.cell_task_color_drop
import dev.pnptracker.resources.cell_task_color_drop_short
import dev.pnptracker.resources.cell_task_color_empty
import dev.pnptracker.resources.cell_task_color_floor
import dev.pnptracker.resources.cell_task_color_gone
import dev.pnptracker.resources.cell_task_color_label
import dev.pnptracker.resources.cell_task_color_move_down
import dev.pnptracker.resources.cell_task_color_move_down_short
import dev.pnptracker.resources.cell_task_color_move_up
import dev.pnptracker.resources.cell_task_color_move_up_short
import dev.pnptracker.resources.cell_task_color_none
import dev.pnptracker.resources.cell_task_color_none_chosen
import dev.pnptracker.resources.cell_task_color_order_label
import dev.pnptracker.resources.cell_task_color_overflow
import dev.pnptracker.resources.cell_task_color_required
import dev.pnptracker.resources.cell_task_color_search
import dev.pnptracker.resources.cell_task_color_slot
import dev.pnptracker.resources.cell_task_color_unknown
import dev.pnptracker.resources.cell_task_completed
import dev.pnptracker.resources.cell_task_create
import dev.pnptracker.resources.cell_task_description
import dev.pnptracker.resources.cell_task_description_unknown_quantity
import dev.pnptracker.resources.cell_task_discard
import dev.pnptracker.resources.cell_task_error_cell_gone
import dev.pnptracker.resources.cell_task_error_cell_no_tasks
import dev.pnptracker.resources.cell_task_error_color_gone
import dev.pnptracker.resources.cell_task_error_could_not_save
import dev.pnptracker.resources.cell_task_error_duplicate_color
import dev.pnptracker.resources.cell_task_error_game_gone
import dev.pnptracker.resources.cell_task_error_invalid_selection
import dev.pnptracker.resources.cell_task_error_line_break
import dev.pnptracker.resources.cell_task_error_name_empty
import dev.pnptracker.resources.cell_task_error_no_task
import dev.pnptracker.resources.cell_task_error_quantity
import dev.pnptracker.resources.cell_task_error_segment_gone
import dev.pnptracker.resources.cell_task_error_segment_not_text
import dev.pnptracker.resources.cell_task_error_stale
import dev.pnptracker.resources.cell_task_hint
import dev.pnptracker.resources.cell_task_mode_label
import dev.pnptracker.resources.cell_task_mode_many
import dev.pnptracker.resources.cell_task_mode_many_hint
import dev.pnptracker.resources.cell_task_mode_multicolor
import dev.pnptracker.resources.cell_task_mode_multicolor_hint
import dev.pnptracker.resources.cell_task_mode_single
import dev.pnptracker.resources.cell_task_name_label
import dev.pnptracker.resources.cell_task_no_color
import dev.pnptracker.resources.cell_task_notes_label
import dev.pnptracker.resources.cell_task_panel_title
import dev.pnptracker.resources.cell_task_quantity_hint
import dev.pnptracker.resources.cell_task_quantity_invalid
import dev.pnptracker.resources.cell_task_quantity_label
import dev.pnptracker.resources.cell_task_quantity_mark
import dev.pnptracker.resources.cell_task_row_add
import dev.pnptracker.resources.cell_task_row_duplicate
import dev.pnptracker.resources.cell_task_row_floor
import dev.pnptracker.resources.cell_task_row_remove
import dev.pnptracker.resources.cell_task_row_remove_short
import dev.pnptracker.resources.cell_task_row_title
import dev.pnptracker.resources.cell_task_save
import dev.pnptracker.resources.cell_task_save_many
import dev.pnptracker.resources.cell_task_save_text_first
import dev.pnptracker.resources.cell_task_saving
import dev.pnptracker.resources.cell_task_saving_many
import dev.pnptracker.resources.cell_task_select_hint
import dev.pnptracker.resources.colors_base_label
import dev.pnptracker.resources.colors_base_none
import dev.pnptracker.resources.colors_brightness_label
import dev.pnptracker.resources.colors_brightness_state
import dev.pnptracker.resources.colors_count
import dev.pnptracker.resources.colors_create
import dev.pnptracker.resources.colors_delete_action
import dev.pnptracker.resources.colors_delete_confirm
import dev.pnptracker.resources.colors_delete_examples
import dev.pnptracker.resources.colors_delete_irreversible
import dev.pnptracker.resources.colors_delete_losing
import dev.pnptracker.resources.colors_delete_more
import dev.pnptracker.resources.colors_delete_sample
import dev.pnptracker.resources.colors_delete_short
import dev.pnptracker.resources.colors_delete_title
import dev.pnptracker.resources.colors_delete_unused
import dev.pnptracker.resources.colors_delete_used
import dev.pnptracker.resources.colors_deleting
import dev.pnptracker.resources.colors_description
import dev.pnptracker.resources.colors_discard
import dev.pnptracker.resources.colors_edit_action
import dev.pnptracker.resources.colors_edit_short
import dev.pnptracker.resources.colors_edit_title
import dev.pnptracker.resources.colors_error_changed
import dev.pnptracker.resources.colors_error_color_gone
import dev.pnptracker.resources.colors_error_could_not_save
import dev.pnptracker.resources.colors_error_name_is_alias
import dev.pnptracker.resources.colors_error_name_used
import dev.pnptracker.resources.colors_hex_shared
import dev.pnptracker.resources.colors_loading
import dev.pnptracker.resources.colors_name_label
import dev.pnptracker.resources.colors_name_required
import dev.pnptracker.resources.colors_new_action
import dev.pnptracker.resources.colors_new_title
import dev.pnptracker.resources.colors_notice_dismiss
import dev.pnptracker.resources.colors_preview_of
import dev.pnptracker.resources.colors_preview_unnamed
import dev.pnptracker.resources.colors_removed
import dev.pnptracker.resources.colors_restore_action
import dev.pnptracker.resources.colors_restore_block_alias
import dev.pnptracker.resources.colors_restore_block_name
import dev.pnptracker.resources.colors_restore_block_race
import dev.pnptracker.resources.colors_restore_blocked
import dev.pnptracker.resources.colors_restore_blocked_note
import dev.pnptracker.resources.colors_restore_confirm
import dev.pnptracker.resources.colors_restore_explains
import dev.pnptracker.resources.colors_restore_item
import dev.pnptracker.resources.colors_restore_missing
import dev.pnptracker.resources.colors_restore_nothing_missing
import dev.pnptracker.resources.colors_restore_title
import dev.pnptracker.resources.colors_restored
import dev.pnptracker.resources.colors_restoring
import dev.pnptracker.resources.colors_save
import dev.pnptracker.resources.colors_saving
import dev.pnptracker.resources.colors_stranded
import dev.pnptracker.resources.colors_stranded_dismiss
import dev.pnptracker.resources.colors_swatch
import dev.pnptracker.resources.colors_swatch_of
import dev.pnptracker.resources.colors_title
import dev.pnptracker.resources.colors_value
import dev.pnptracker.resources.colors_wheel_hint
import dev.pnptracker.resources.colors_wheel_label
import dev.pnptracker.resources.colors_wheel_state
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
import dev.pnptracker.resources.navigation_pool_active_count
import dev.pnptracker.resources.navigation_pool_board
import dev.pnptracker.resources.navigation_pool_card
import dev.pnptracker.resources.navigation_pool_special
import dev.pnptracker.resources.navigation_pool_three_d
import dev.pnptracker.resources.navigation_section_label
import dev.pnptracker.resources.navigation_state_not_selected
import dev.pnptracker.resources.navigation_state_selected
import dev.pnptracker.resources.pool_board
import dev.pnptracker.resources.pool_card
import dev.pnptracker.resources.pool_empty
import dev.pnptracker.resources.pool_error
import dev.pnptracker.resources.pool_group_failures
import dev.pnptracker.resources.pool_group_missing
import dev.pnptracker.resources.pool_group_summary
import dev.pnptracker.resources.pool_group_summary_unknown
import dev.pnptracker.resources.pool_intro_board
import dev.pnptracker.resources.pool_intro_card
import dev.pnptracker.resources.pool_intro_special
import dev.pnptracker.resources.pool_intro_three_d
import dev.pnptracker.resources.pool_loading
import dev.pnptracker.resources.pool_section_awaiting_color
import dev.pnptracker.resources.pool_section_multicolor
import dev.pnptracker.resources.pool_section_single_color
import dev.pnptracker.resources.pool_special
import dev.pnptracker.resources.pool_special_checklist
import dev.pnptracker.resources.pool_special_counted
import dev.pnptracker.resources.pool_special_game
import dev.pnptracker.resources.pool_special_remaining
import dev.pnptracker.resources.pool_stage_badge
import dev.pnptracker.resources.pool_stage_count
import dev.pnptracker.resources.pool_stage_count_of
import dev.pnptracker.resources.pool_stage_details_close
import dev.pnptracker.resources.pool_stage_details_open
import dev.pnptracker.resources.pool_stage_done
import dev.pnptracker.resources.pool_task_color_current
import dev.pnptracker.resources.pool_task_colors
import dev.pnptracker.resources.pool_task_failures
import dev.pnptracker.resources.pool_task_missing
import dev.pnptracker.resources.pool_task_open
import dev.pnptracker.resources.pool_task_primary_done
import dev.pnptracker.resources.pool_task_primary_pending
import dev.pnptracker.resources.pool_task_quantity
import dev.pnptracker.resources.pool_task_quantity_unknown
import dev.pnptracker.resources.pool_task_spoken
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
import dev.pnptracker.resources.stage_cut
import dev.pnptracker.resources.stage_glue
import dev.pnptracker.resources.stage_laminate
import dev.pnptracker.resources.stage_print
import dev.pnptracker.resources.table_add_game
import dev.pnptracker.resources.table_add_game_hint
import dev.pnptracker.resources.table_cell_description
import dev.pnptracker.resources.table_cell_empty
import dev.pnptracker.resources.table_cell_empty_description
import dev.pnptracker.resources.table_cell_more
import dev.pnptracker.resources.table_column_game
import dev.pnptracker.resources.table_completed_mark
import dev.pnptracker.resources.table_empty_completed
import dev.pnptracker.resources.table_empty_completed_hint
import dev.pnptracker.resources.table_empty_library
import dev.pnptracker.resources.table_empty_library_hint
import dev.pnptracker.resources.table_empty_ongoing
import dev.pnptracker.resources.table_empty_ongoing_hint
import dev.pnptracker.resources.table_label
import dev.pnptracker.resources.table_loading
import dev.pnptracker.resources.table_row_completed
import dev.pnptracker.resources.table_row_description
import dev.pnptracker.resources.table_row_ongoing
import dev.pnptracker.resources.table_view_all
import dev.pnptracker.resources.table_view_completed
import dev.pnptracker.resources.table_view_label
import dev.pnptracker.resources.table_view_ongoing
import dev.pnptracker.resources.task_convert_accept
import dev.pnptracker.resources.task_convert_body
import dev.pnptracker.resources.task_convert_cancel
import dev.pnptracker.resources.task_convert_history_warning
import dev.pnptracker.resources.task_convert_irreversible
import dev.pnptracker.resources.task_convert_title
import dev.pnptracker.resources.task_edit_color_floor
import dev.pnptracker.resources.task_edit_colors_label
import dev.pnptracker.resources.task_edit_error_color_count
import dev.pnptracker.resources.task_edit_error_color_gone
import dev.pnptracker.resources.task_edit_error_could_not_save
import dev.pnptracker.resources.task_edit_error_duplicate_color
import dev.pnptracker.resources.task_edit_error_name_empty
import dev.pnptracker.resources.task_edit_error_name_line_break
import dev.pnptracker.resources.task_edit_error_quantity
import dev.pnptracker.resources.task_edit_error_quantity_below_progress
import dev.pnptracker.resources.task_edit_error_quantity_locked
import dev.pnptracker.resources.task_edit_error_task_gone
import dev.pnptracker.resources.task_edit_hint
import dev.pnptracker.resources.task_edit_name_invalid
import dev.pnptracker.resources.task_edit_name_label
import dev.pnptracker.resources.task_edit_save
import dev.pnptracker.resources.task_edit_saving
import dev.pnptracker.resources.task_edit_title
import dev.pnptracker.resources.task_menu_convert
import dev.pnptracker.resources.task_menu_edit
import dev.pnptracker.resources.task_menu_hint
import dev.pnptracker.resources.task_menu_open
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

    /** What a pool screen says, and what the sidebar says about it. */
    object Pool {
        val navThreeD = Res.string.navigation_pool_three_d
        val navCard = Res.string.navigation_pool_card
        val navBoard = Res.string.navigation_pool_board
        val navSpecial = Res.string.navigation_pool_special
        val navActiveCount = Res.string.navigation_pool_active_count
        val introThreeD = Res.string.pool_intro_three_d
        val introCard = Res.string.pool_intro_card
        val introBoard = Res.string.pool_intro_board
        val introSpecial = Res.string.pool_intro_special
        val empty = Res.string.pool_empty
        val loading = Res.string.pool_loading
        val sectionAwaitingColor = Res.string.pool_section_awaiting_color
        val sectionSingleColor = Res.string.pool_section_single_color
        val sectionMulticolor = Res.string.pool_section_multicolor
        val groupSummary = Res.string.pool_group_summary
        val groupSummaryUnknown = Res.string.pool_group_summary_unknown
        val groupMissing = Res.string.pool_group_missing
        val groupFailures = Res.string.pool_group_failures
        val quantity = Res.string.pool_task_quantity
        val quantityUnknown = Res.string.pool_task_quantity_unknown
        val missing = Res.string.pool_task_missing
        val failures = Res.string.pool_task_failures
        val primaryDone = Res.string.pool_task_primary_done
        val primaryPending = Res.string.pool_task_primary_pending
        val open = Res.string.pool_task_open
        val colors = Res.string.pool_task_colors
        val currentColor = Res.string.pool_task_color_current
        val stageBadge = Res.string.pool_stage_badge
        val stageDone = Res.string.pool_stage_done
        val stageDetailsOpen = Res.string.pool_stage_details_open
        val stageDetailsClose = Res.string.pool_stage_details_close
        val stageCount = Res.string.pool_stage_count
        val stageCountOf = Res.string.pool_stage_count_of
        val checklist = Res.string.pool_special_checklist
        val counted = Res.string.pool_special_counted
        val remaining = Res.string.pool_special_remaining
        val game = Res.string.pool_special_game
        val spokenTask = Res.string.pool_task_spoken
        val error = Res.string.pool_error
        val stagePrint = Res.string.stage_print
        val stageLaminate = Res.string.stage_laminate
        val stageGlue = Res.string.stage_glue
        val stageCut = Res.string.stage_cut
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
        val newAction = Res.string.colors_new_action
        val newTitle = Res.string.colors_new_title
        val nameLabel = Res.string.colors_name_label
        val nameRequired = Res.string.colors_name_required
        val hexShared = Res.string.colors_hex_shared
        val swatch = Res.string.colors_swatch
        val save = Res.string.colors_save
        val saving = Res.string.colors_saving
        val discard = Res.string.colors_discard

        val baseLabel = Res.string.colors_base_label
        val baseNone = Res.string.colors_base_none
        val wheelLabel = Res.string.colors_wheel_label
        val wheelState = Res.string.colors_wheel_state
        val wheelHint = Res.string.colors_wheel_hint
        val brightnessLabel = Res.string.colors_brightness_label
        val brightnessState = Res.string.colors_brightness_state
        val previewOf = Res.string.colors_preview_of
        val previewUnnamed = Res.string.colors_preview_unnamed

        val stranded = Res.string.colors_stranded
        val strandedDismiss = Res.string.colors_stranded_dismiss

        val editShort = Res.string.colors_edit_short
        val editAction = Res.string.colors_edit_action
        val editTitle = Res.string.colors_edit_title
        val value = Res.string.colors_value
        val swatchOf = Res.string.colors_swatch_of

        val deleteShort = Res.string.colors_delete_short
        val deleteAction = Res.string.colors_delete_action
        val deleteTitle = Res.string.colors_delete_title
        val deleteUnused = Res.string.colors_delete_unused
        val deleteUsed = Res.string.colors_delete_used
        val deleteLosing = Res.string.colors_delete_losing
        val deleteExamples = Res.string.colors_delete_examples
        val deleteSample = Res.string.colors_delete_sample
        val deleteMore = Res.string.colors_delete_more
        val deleteIrreversible = Res.string.colors_delete_irreversible
        val deleteConfirm = Res.string.colors_delete_confirm
        val deleting = Res.string.colors_deleting
        val removed = Res.string.colors_removed

        val restoreAction = Res.string.colors_restore_action
        val restoreTitle = Res.string.colors_restore_title
        val restoreExplains = Res.string.colors_restore_explains
        val restoreMissing = Res.string.colors_restore_missing
        val restoreItem = Res.string.colors_restore_item
        val restoreBlocked = Res.string.colors_restore_blocked
        val restoreBlockName = Res.string.colors_restore_block_name
        val restoreBlockAlias = Res.string.colors_restore_block_alias
        val restoreBlockRace = Res.string.colors_restore_block_race
        val restoreBlockedNote = Res.string.colors_restore_blocked_note
        val restoreConfirm = Res.string.colors_restore_confirm
        val restoring = Res.string.colors_restoring
        val restored = Res.string.colors_restored
        val restoreNothingMissing = Res.string.colors_restore_nothing_missing
        val noticeDismiss = Res.string.colors_notice_dismiss

        val errorCouldNotSave = Res.string.colors_error_could_not_save
        val errorNameUsed = Res.string.colors_error_name_used
        val errorNameIsAlias = Res.string.colors_error_name_is_alias
        val errorColorGone = Res.string.colors_error_color_gone
        val errorChanged = Res.string.colors_error_changed
    }

    /** The columns of the game table. */
    object Columns {
        val notes = Res.string.column_notes

        /** The first column, which holds the game's name rather than a cell. */
        val game = Res.string.table_column_game
    }

    /** The game table itself: its three views, its cells and its one action. */
    object Table {
        val label = Res.string.table_label
        val loading = Res.string.table_loading

        val viewLabel = Res.string.table_view_label
        val viewOngoing = Res.string.table_view_ongoing
        val viewCompleted = Res.string.table_view_completed
        val viewAll = Res.string.table_view_all

        val emptyOngoing = Res.string.table_empty_ongoing
        val emptyOngoingHint = Res.string.table_empty_ongoing_hint
        val emptyCompleted = Res.string.table_empty_completed
        val emptyCompletedHint = Res.string.table_empty_completed_hint
        val emptyLibrary = Res.string.table_empty_library
        val emptyLibraryHint = Res.string.table_empty_library_hint

        val cellEmpty = Res.string.table_cell_empty
        val cellEmptyDescription = Res.string.table_cell_empty_description
        val cellDescription = Res.string.table_cell_description
        val cellMore = Res.string.table_cell_more

        val rowCompleted = Res.string.table_row_completed
        val rowOngoing = Res.string.table_row_ongoing
        val rowDescription = Res.string.table_row_description

        /** Shown beside a finished game, so the green is never the only sign. */
        val completedMark = Res.string.table_completed_mark

        val addGame = Res.string.table_add_game
        val addGameHint = Res.string.table_add_game_hint
    }

    /** Writing in one cell of the table. */
    object Cell {
        val edit = Res.string.cell_edit
        val editAction = Res.string.cell_edit_action
        val editorHint = Res.string.cell_editor_hint
        val save = Res.string.cell_save
        val discard = Res.string.cell_discard
        val saving = Res.string.cell_saving

        val errorCouldNotSave = Res.string.cell_error_could_not_save
        val errorGameGone = Res.string.cell_error_game_gone
        val errorCellGone = Res.string.cell_error_cell_gone
        val errorCrossesTask = Res.string.cell_error_crosses_task
        val errorStaleDocument = Res.string.cell_error_stale_document

        val editorOpenElsewhere = Res.string.cell_editor_open_elsewhere
    }

    /**
     * Turning words the user selected in a cell into a task.
     *
     * The quantity mark is here rather than written into the screen for the same
     * reason every other word is: PLAN 17 keeps the wording where it can be found
     * and changed. It is also the one place `×` is decided, which matters because
     * the mark is something the table says about a task and never something
     * stored in anybody's text.
     */
    object CellTask {
        val create = Res.string.cell_task_create
        val selectHint = Res.string.cell_task_select_hint
        val saveTextFirst = Res.string.cell_task_save_text_first

        val panelTitle = Res.string.cell_task_panel_title
        val nameLabel = Res.string.cell_task_name_label
        val colorLabel = Res.string.cell_task_color_label
        val colorSearch = Res.string.cell_task_color_search
        val colorRequired = Res.string.cell_task_color_required
        val colorNone = Res.string.cell_task_color_none
        val colorEmpty = Res.string.cell_task_color_empty
        val quantityLabel = Res.string.cell_task_quantity_label
        val quantityHint = Res.string.cell_task_quantity_hint
        val quantityInvalid = Res.string.cell_task_quantity_invalid
        val notesLabel = Res.string.cell_task_notes_label
        val save = Res.string.cell_task_save
        val discard = Res.string.cell_task_discard
        val saving = Res.string.cell_task_saving
        val hint = Res.string.cell_task_hint

        /** Takes the quantity; shown beside a task and never stored as text. */
        val quantityMark = Res.string.cell_task_quantity_mark

        /** Takes the name, the quantity and the colours, in that order. */
        val description = Res.string.cell_task_description

        /** Takes the name and the colours, for a task whose count is unknown. */
        val descriptionUnknownQuantity = Res.string.cell_task_description_unknown_quantity

        val noColor = Res.string.cell_task_no_color
        val completed = Res.string.cell_task_completed

        val modeLabel = Res.string.cell_task_mode_label
        val modeSingle = Res.string.cell_task_mode_single
        val modeMany = Res.string.cell_task_mode_many
        val modeManyHint = Res.string.cell_task_mode_many_hint
        val modeMulticolor = Res.string.cell_task_mode_multicolor
        val modeMulticolorHint = Res.string.cell_task_mode_multicolor_hint

        val colorOrderLabel = Res.string.cell_task_color_order_label

        /** Takes the colour's place in the list, counting from one, and its name. */
        val colorSlot = Res.string.cell_task_color_slot

        /** Takes the colour's name. */
        val colorMoveUp = Res.string.cell_task_color_move_up

        /** Takes the colour's name. */
        val colorMoveDown = Res.string.cell_task_color_move_down
        val colorMoveUpShort = Res.string.cell_task_color_move_up_short
        val colorMoveDownShort = Res.string.cell_task_color_move_down_short

        /** Takes the colour's name. */
        val colorDrop = Res.string.cell_task_color_drop
        val colorDropShort = Res.string.cell_task_color_drop_short
        val colorFloor = Res.string.cell_task_color_floor
        val colorUnknown = Res.string.cell_task_color_unknown
        val colorGone = Res.string.cell_task_color_gone
        val colorNoneChosen = Res.string.cell_task_color_none_chosen
        val colorOverflow = Res.string.cell_task_color_overflow

        /** Takes the row's place in the panel, counting from one. */
        val rowTitle = Res.string.cell_task_row_title
        val rowAdd = Res.string.cell_task_row_add

        /** Takes the row's place in the panel, counting from one. */
        val rowRemove = Res.string.cell_task_row_remove
        val rowRemoveShort = Res.string.cell_task_row_remove_short
        val rowFloor = Res.string.cell_task_row_floor

        /** Takes the earlier task's place in the panel, counting from one. */
        val rowDuplicate = Res.string.cell_task_row_duplicate

        /** Takes how many tasks the batch will create. */
        val saveMany = Res.string.cell_task_save_many
        val savingMany = Res.string.cell_task_saving_many

        val errorGameGone = Res.string.cell_task_error_game_gone
        val errorCellGone = Res.string.cell_task_error_cell_gone
        val errorCellHoldsNoTasks = Res.string.cell_task_error_cell_no_tasks
        val errorSegmentGone = Res.string.cell_task_error_segment_gone
        val errorSegmentNotText = Res.string.cell_task_error_segment_not_text
        val errorStaleSelection = Res.string.cell_task_error_stale
        val errorInvalidSelection = Res.string.cell_task_error_invalid_selection
        val errorLineBreak = Res.string.cell_task_error_line_break
        val errorNameEmpty = Res.string.cell_task_error_name_empty
        val errorColorGone = Res.string.cell_task_error_color_gone
        val errorQuantity = Res.string.cell_task_error_quantity

        /** Takes the two places that name the same colour, counting from one. */
        val errorDuplicateColor = Res.string.cell_task_error_duplicate_color
        val errorNoTask = Res.string.cell_task_error_no_task
        val errorCouldNotSave = Res.string.cell_task_error_could_not_save
    }

    /** The menu that opens over one task in a cell. */
    object TaskMenu {
        /** Takes the task's name. */
        val open = Res.string.task_menu_open
        val edit = Res.string.task_menu_edit
        val convertToText = Res.string.task_menu_convert
        val hint = Res.string.task_menu_hint
    }

    /** Changing what a task already written down is. */
    object TaskEdit {
        val title = Res.string.task_edit_title
        val nameLabel = Res.string.task_edit_name_label
        val nameInvalid = Res.string.task_edit_name_invalid

        val colorsLabel = Res.string.task_edit_colors_label
        val colorFloor = Res.string.task_edit_color_floor
        val save = Res.string.task_edit_save
        val saving = Res.string.task_edit_saving
        val hint = Res.string.task_edit_hint

        val errorTaskGone = Res.string.task_edit_error_task_gone
        val errorNameEmpty = Res.string.task_edit_error_name_empty
        val errorNameLineBreak = Res.string.task_edit_error_name_line_break
        val errorColorGone = Res.string.task_edit_error_color_gone

        /** Takes the two colours that are the same, counting from one. */
        val errorDuplicateColor = Res.string.task_edit_error_duplicate_color
        val errorColorCount = Res.string.task_edit_error_color_count
        val errorQuantity = Res.string.task_edit_error_quantity
        val errorQuantityBelowProgress = Res.string.task_edit_error_quantity_below_progress
        val errorQuantityLocked = Res.string.task_edit_error_quantity_locked
        val errorCouldNotSave = Res.string.task_edit_error_could_not_save
    }

    /** Turning a task back into the words it was made from. */
    object TaskConvert {
        val title = Res.string.task_convert_title

        /** Takes the task's name. */
        val body = Res.string.task_convert_body
        val historyWarning = Res.string.task_convert_history_warning
        val irreversible = Res.string.task_convert_irreversible
        val accept = Res.string.task_convert_accept
        val cancel = Res.string.task_convert_cancel
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
