package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The command list's redesign added sixty-three strings. Arabic ships a translation for every one
 * of them, so an Arabic interface reads as Arabic rather than switching to English midway through
 * the page.
 *
 * Done as its own test because it is a different promise from the fallback one: that test says a
 * locale without the text must still *say something*, this one says Arabic must not need to.
 *
 * The keys are listed rather than derived from `embedded_en.toml`. Deriving them would pass the
 * moment English added a key Arabic had not caught up with, which is the failure this is for.
 */
class KeyboardPanelArabicCoverageTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /**
     * Every string the command list added, by its key inside `[settings_hotkeys]` and in the menu.
     *
     * Grouped as the page uses them so a missing category reads differently from a missing
     * description. `conflict_entry` is `%s — %s`, punctuation only, so it is the one entry with no
     * Arabic of its own.
     */
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

    private fun shippedLanguages(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "languages")
            if (File(candidate, "en-GB.toml").isFile) return candidate
            directory = directory.parentFile
        }
        error("the languages folder was not found above ${System.getProperty("user.dir")}")
    }

    private fun localizerFor(tag: String): LocalizationManager {
        val appData = Files.createTempDirectory("qtranslate-kbd-ar").toFile()
        val install = Files.createTempDirectory("qtranslate-kbd-ar-install").toFile()
        File(install, "languages").mkdirs()
        shippedLanguages().listFiles { file -> file.extension == "toml" }
            .orEmpty()
            .forEach { it.copyTo(File(install, "languages/${it.name}"), overwrite = true) }
        val manager = LocalizationManager(appData, LanguageTomlParser(), logger, install)
        runBlocking { manager.loadLanguage(LanguageCode(tag)) }
        return manager
    }

    @Test
    fun `arabic carries every string the command list added`() {
        val arabic = localizerFor("ar-SA")
        val english = localizerFor("en")

        val untranslated = allKeys
            // A punctuation-only template (`%s — %s`): identical by design, so equality here
            // would report a missing translation that cannot exist.
            .filter { it != "settings_hotkeys.conflict_entry" }
            .filter { key ->
                // Equal to English means it fell back rather than being translated.
                arabic.getString(key) == english.getString(key)
            }

        assertEquals(
            emptyList(),
            untranslated,
            "ar-SA falls back to English for these command list strings: $untranslated",
        )
    }

    @Test
    fun `every arabic string is real text rather than a key or a blank`() {
        val arabic = localizerFor("ar-SA")
        for (key in allKeys) {
            val text = arabic.getString(key)
            assertNotEquals(key, text, "ar-SA leaks the key for $key")
            assertTrue(text.isNotBlank(), "ar-SA renders an empty label for $key")
            assertTrue(
                !text.startsWith("settings_hotkeys.") && !text.startsWith("main_window_main_menu."),
                "ar-SA leaks the key path for $key: $text",
            )
        }
    }

    @Test
    fun `the arabic strings keep the placeholders the code fills in`() {
        val arabic = localizerFor("ar-SA")
        val english = localizerFor("en")

        for (key in allKeys) {
            val englishText = english.getString(key)
            if (!englishText.contains("%s")) continue
            assertTrue(
                arabic.getString(key).contains("%s"),
                "ar-SA dropped the %s placeholder from $key; the value would render unformatted",
            )
        }
    }

    @Test
    fun `the arabic strings are written in arabic rather than transliterated English`() {
        // `conflict_entry` is a punctuation-only template and has no letters to check.
        val arabicScript = Regex("[\\u0600-\\u06FF]")
        val arabic = localizerFor("ar-SA")
        val withoutArabic = allKeys
            .filter { it != "settings_hotkeys.conflict_entry" }
            .filterNot { arabicScript.containsMatchIn(arabic.getString(it)) }

        assertEquals(
            emptyList(),
            withoutArabic,
            "ar-SA has no Arabic script in these strings: $withoutArabic",
        )
    }
}
