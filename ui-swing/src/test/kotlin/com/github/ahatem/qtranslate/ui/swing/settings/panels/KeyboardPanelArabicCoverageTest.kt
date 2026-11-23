package com.github.ahatem.qtranslate.ui.swing.settings.panels

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Arabic ships a translation for every string the command list added, so the page does not switch
 * to English midway. The keys are listed rather than derived from `embedded_en.toml`: deriving
 * them would pass the moment English added a key Arabic had not caught up with.
 */
class KeyboardPanelArabicCoverageTest {

    /** Every string the command list added; `conflict_entry` is punctuation only, so it has no Arabic. */
    private val hotkeyKeys = listOf(
        // category headings
        "category_translation", "category_writing", "category_dictionary", "category_ocr",
        "category_capture", "category_navigation", "category_editing", "category_document",
        "category_window",
        // what each command does
        "describe_show_main_window", "describe_show_quick_translate", "describe_listen_to_text",
        "describe_open_ocr", "describe_ocr_show_actions", "describe_ocr_translate",
        "describe_ocr_translate_quick", "describe_ocr_translate_main", "describe_ocr_open_workspace",
        "describe_ocr_copy_text", "describe_ocr_copy_image", "describe_replace_with_translation",
        "describe_replace_with_rewrite", "describe_replace_with_summary",
        "describe_cycle_target_language", "describe_show_dictionary", "describe_show_images",
        "describe_translate", "describe_focus_input", "describe_focus_output",
        "describe_focus_extra_output", "describe_copy_translation", "describe_clear_input",
        "describe_swap_languages", "describe_open_settings", "describe_show_history",
        "describe_translate_document", "describe_ocr_action_translate",
        "describe_ocr_action_copy_text", "describe_ocr_action_copy_image",
        "describe_ocr_action_save_image", "describe_ocr_action_recrop", "describe_ocr_action_cancel",
        // what a command needs
        "requirement_selection", "requirement_writing",
        // the list's own controls
        "filter_label", "filter_all", "filter_unassigned", "filter_conflicts",
        "filter_empty_unassigned", "filter_empty_conflicts", "details_placeholder",
        "conflict_entry", "conflict_row_tooltip", "recorder_conflict",
        "preset_current", "preset_custom_hint",
        // the actions at the end of each row
        "row_edit_tooltip", "row_clear_tooltip", "column_actions",
        "command_list_accessible_name", "row_actions_accessible_name",
        "row_actions_accessible_description",
        // the reworded hint, which changed from naming a row to naming the pencil
        "edit_hint",
    )

    /** Strings the command list introduced outside `[settings_hotkeys]`. */
    private val menuKeys = listOf("main_window_main_menu.menu")

    private val allKeys = hotkeyKeys.map { "settings_hotkeys.$it" } + menuKeys

    @Test
    fun `the arabic strings are real arabic text that keeps its placeholders`() {
        val arabic = shippedLocalizerFor("ar-SA")
        val english = shippedLocalizerFor("en")
        val arabicScript = Regex("[\\u0600-\\u06FF]")

        for (key in allKeys) {
            val text = arabic.getString(key)
            assertNotEquals(key, text, "ar-SA leaks the key for $key")
            assertTrue(text.isNotBlank(), "ar-SA renders an empty label for $key")
            if (key != "settings_hotkeys.conflict_entry") {
                assertTrue(arabicScript.containsMatchIn(text), "ar-SA has no Arabic script for $key: $text")
            }
            if (english.getString(key).contains("%s")) {
                assertTrue(text.contains("%s"), "ar-SA dropped the %s placeholder from $key")
            }
        }
    }
}
