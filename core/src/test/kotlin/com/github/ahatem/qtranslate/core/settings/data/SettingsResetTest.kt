package com.github.ahatem.qtranslate.core.settings.data

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reset scope, as a promise rather than an implementation detail.
 *
 * The two resets say different things: "reset this page" restores everything that page shows,
 * while "reset all settings" restores ordinary preferences and keeps the data the application
 * manages for the user — presets, bindings, pins, rules, credentials, geometry, nudge state.
 * Losing those is not something a preference reset implies, and there is no undo.
 */
class SettingsResetTest {

    private val fresh = Configuration.FRESH_INSTALL

    private fun preset(name: String) = Configuration.DEFAULT.getActivePreset()!!.copy(name = name)

    /** A configuration with every reset-able preference changed away from its default. */
    private val customizedPreferences = Configuration.FRESH_INSTALL.copy(
        launchOnSystemStartup = true,
        autoCheckForUpdates = false,
        isGlobalHotkeysEnabled = false,
        selectionBehavior = SelectionBehavior.TRANSLATE,
        selectionReadSource = SelectionReadSource.SOURCE,
        interfaceLanguage = "ar",
        isInstantTranslationEnabled = true,
        autoCopyTranslation = AutoCopyTranslation.ALL,
        isSpellCheckingEnabled = false,
        isRemoveLineBreaksEnabled = true,
        extraOutputType = ExtraOutputType.Summarize,
        extraOutputSource = ExtraOutputSource.Input,
        summaryLength = "LONG",
        rewriteStyle = "CASUAL",
        dictionaryAutoSource = DictionaryAutoSource.OFF,
        preferredSourceLanguage = "fr",
        preferredTargetLanguage = "de",
        layoutPresetId = "side_by_side",
        toolbarVisibility = ToolbarVisibility(
            isHistoryBarVisible = false,
            isLanguageBarVisible = false,
            isServicesPanelVisible = false,
            isStatusBarVisible = false,
        ),
        showDictionaryPanel = true,
        serviceSelectorStyle = ServiceSelectorStyle.CLASSIC,
        serviceSelectorAppearance = ServiceSelectorAppearance.TEXT_ONLY,
        windowLayout = WindowLayout(
            panes = PaneLayout.SIDE_BY_SIDE,
            history = RegionSlot.BOTTOM,
            languages = RegionSlot.BOTTOM,
            services = RegionSlot.TOP,
            serviceWidget = ServiceWidget.STRIP,
            stripAppearance = ServiceSelectorAppearance.TEXT_ONLY,
            shareRows = true,
            showStatusBar = false,
            density = Density.COMPACT,
        ),
        closeButtonBehavior = CloseButtonBehavior.EXIT,
        minimizeButtonBehavior = MinimizeButtonBehavior.HIDE_TO_TRAY,
        themeId = "dark",
        iconSetId = "material",
        useUnifiedTitleBar = false,
        uiScale = 150,
        uiFontConfig = FontConfig(name = "Rubik", size = 16),
        editorFontConfig = FontConfig(name = "Rubik", size = 20),
        editorFallbackFontConfig = FontConfig(name = "Noto Naskh Arabic", size = 20),
        closePopupsOnClickOutside = false,
        isPopupAutoSizeEnabled = false,
        isPopupAutoPositionEnabled = false,
        popupTransparencyPercentage = 40,
        popupIdleTimeoutSeconds = 30,
        isQuickDictionaryAutoPositionEnabled = false,
        quickDictionaryTransparencyPercentage = 40,
        quickDictionaryIdleTimeoutSeconds = 30,
        isImageSearchAutoPositionEnabled = false,
        imageSearchTransparencyPercentage = 40,
        ocr = OcrSettings(
            postSelectionAction = OcrPostSelectionAction.COPY_TEXT,
            translationDestination = OcrTranslationDestination.QUICK_TRANSLATE,
            recognitionLanguage = "de",
            removeLineBreaks = true,
            showWorkspaceAnnotations = false,
        ),
        network = NetworkConfig(
            requestTimeoutSeconds = 90,
            connectTimeoutSeconds = 45,
            socketTimeoutSeconds = 45,
        ),
        isHistoryEnabled = false,
        clearHistoryOnExit = true,
    )

    /** A configuration carrying the data a preference reset must keep. */
    private val customizedData = Configuration.FRESH_INSTALL.copy(
        servicePresets = listOf(preset("Work")),
        activeServicePresetId = Configuration.FRESH_INSTALL.activeServicePresetId,
        disabledServices = setOf("ocr:service"),
        hotkeys = listOf(HotkeyBinding(action = HotkeyAction.TRANSLATE, keyCode = 84, modifiers = 2)),
        pinnedLanguages = listOf("fr", "de"),
        translationRules = listOf(TranslationRule(sourceLanguage = "fr", targetLanguage = "de")),
        layoutProfiles = listOf(LayoutProfile("Reading", WindowLayout(panes = PaneLayout.SIDE_BY_SIDE))),
        mainWindowSize = Size(width = 1200, height = 800),
        mainWindowPosition = Position(x = 10, y = 20),
        ocrWorkspaceSize = Size(width = 900, height = 700),
        ocrWorkspacePosition = Position(x = 30, y = 40),
        popupLastKnownSize = Size(width = 500, height = 300),
        popupLastKnownPosition = Position(x = 50, y = 60),
        quickDictionaryLastKnownSize = Size(width = 460, height = 440),
        quickDictionaryLastKnownPosition = Position(x = 70, y = 80),
        imageSearchLastKnownSize = Size(width = 600, height = 500),
        imageSearchLastKnownPosition = Position(x = 90, y = 100),
        isQuickDictionaryPinned = true,
        donationNudgeShown = true,
    )

    /** Field names the compiled class actually has, so coverage cannot be assumed. */
    private val allFields: Set<String> = Configuration::class.java.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        .map { it.name }
        .toSet()

    @Test
    fun `every configuration field is classified as reset or preserved`() {
        val reset = SettingsReset.RESET_BY_PREFERENCE_RESET
        val preserved = SettingsReset.PRESERVED_BY_PREFERENCE_RESET

        assertTrue(reset.intersect(preserved).isEmpty(), "a field is reset or preserved, never both")
        assertEquals(
            allFields,
            reset + preserved,
            "a new setting must be classified; otherwise a reset would either drop it or keep it by accident",
        )
    }

    @Test
    fun `a preference reset restores every ordinary preference`() {
        assertEquals(fresh, SettingsReset.preferences(customizedPreferences))
    }

    @Test
    fun `a preference reset keeps the data the application manages for the user`() {
        // customizedData differs from a fresh install only in that data (presets, bindings, pins,
        // rules, saved layouts, window geometry, nudge state), so the reset must change nothing.
        assertEquals(customizedData, SettingsReset.preferences(customizedData))
    }

    @Test
    fun `resetting one page restores that page and leaves the rest alone`() {
        val config = customizedPreferences.copy(
            servicePresets = customizedData.servicePresets,
            hotkeys = customizedData.hotkeys,
            pinnedLanguages = customizedData.pinnedLanguages,
            layoutProfiles = customizedData.layoutProfiles,
            mainWindowSize = customizedData.mainWindowSize,
        )

        val general = SettingsReset.page(config, SettingsPage.GENERAL)
        assertEquals(fresh.launchOnSystemStartup, general.launchOnSystemStartup)
        assertEquals(fresh.selectionBehavior, general.selectionBehavior)
        assertEquals(fresh.isHistoryEnabled, general.isHistoryEnabled)
        assertEquals(config.themeId, general.themeId, "Appearance is a different page")
        assertEquals(config.hotkeys, general.hotkeys, "bindings are not this page's")

        val window = SettingsReset.page(config, SettingsPage.WINDOW)
        assertEquals(fresh.layoutPresetId, window.layoutPresetId)
        assertEquals(fresh.windowLayout, window.windowLayout, "a custom region layout is reset too")
        assertEquals(config.layoutProfiles, window.layoutProfiles, "saved layouts are not reset with the page")
        assertEquals(fresh.closeButtonBehavior, window.closeButtonBehavior)
        assertEquals(fresh.minimizeButtonBehavior, window.minimizeButtonBehavior, "a reset gives the product default")
        assertEquals(config.themeId, window.themeId)

        val languages = SettingsReset.page(config, SettingsPage.LANGUAGES)
        assertEquals(fresh.pinnedLanguages, languages.pinnedLanguages, "this page owns its list")
        assertEquals(fresh.translationRules, languages.translationRules)
        assertEquals(config.servicePresets, languages.servicePresets)
    }

    @Test
    fun `resetting the keyboard page restores bindings without touching the rest`() {
        val config = customizedPreferences.copy(
            hotkeys = listOf(HotkeyBinding(action = HotkeyAction.TRANSLATE, keyCode = 84, modifiers = 2)),
            themeId = "dark",
        )

        val keyboard = SettingsReset.page(config, SettingsPage.KEYBOARD)

        assertEquals(Configuration.FRESH_INSTALL.hotkeys, keyboard.hotkeys)
        assertEquals(Configuration.FRESH_INSTALL.ocrHotkeys, keyboard.ocrHotkeys)
        assertEquals(Configuration.FRESH_INSTALL.writingHotkeys, keyboard.writingHotkeys)
        assertEquals(Configuration.FRESH_INSTALL.isGlobalHotkeysEnabled, keyboard.isGlobalHotkeysEnabled)
        assertEquals("dark", keyboard.themeId, "another page is untouched")
    }

    @Test
    fun `resetting the services page keeps every preset and restores the active selections`() {
        val custom = preset("Work")
        val other = preset("Play")
        val config = Configuration.FRESH_INSTALL.copy(
            servicePresets = listOf(custom, other),
            activeServicePresetId = other.id,
        )

        val services = SettingsReset.page(config, SettingsPage.SERVICES)

        assertEquals(listOf("Work", "Play"), services.servicePresets.map { it.name }, "no preset is deleted")
        assertEquals(
            Configuration.FRESH_INSTALL.getActivePreset()!!.selectedServices,
            services.getActivePreset()!!.selectedServices,
            "the active preset's selections go back to the defaults",
        )
        assertEquals(
            custom.selectedServices,
            services.servicePresets.first().selectedServices,
            "a preset that is not active keeps its own selections",
        )
    }

    @Test
    fun `a page reset that changes nothing is the identity`() {
        for (page in SettingsPage.entries) {
            val reset = SettingsReset.page(Configuration.FRESH_INSTALL, page)
            assertEquals(Configuration.FRESH_INSTALL, reset, "$page has nothing to restore on defaults")
        }
    }
}
