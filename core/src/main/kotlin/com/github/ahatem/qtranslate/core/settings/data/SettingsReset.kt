package com.github.ahatem.qtranslate.core.settings.data

/**
 * A page of the Settings dialog, for a reset scoped to that page.
 *
 * The split follows what the dialog shows, because that is what a user means by "reset this
 * page". A control that moves between pages moves its reset with it: the lists here are the
 * single record of which settings a page owns.
 */
enum class SettingsPage {
    GENERAL,
    APPEARANCE,
    SERVICES,
    BEHAVIOUR,
    LANGUAGES,
    WINDOW,
    POPUPS,
    OCR,
    KEYBOARD,
    NETWORK,
}

/**
 * What "reset" means, for both the per-page action and the whole-dialog one.
 *
 * Two different promises, and the difference is the point:
 *
 * - [page] restores everything one page owns, including lists the user built on that page, such
 *   as pinned languages or custom bindings. It is a targeted, explicit request, so it may remove
 *   what it names.
 * - [preferences] restores ordinary preferences and deliberately preserves the data the
 *   application manages on the user's behalf — service presets, custom hotkey bindings, pinned
 *   languages, translation rules, credentials, window geometry, popup positions, nudge state —
 *   plus the version and compatibility fields that are not settings at all. Losing those is not
 *   something a casual "reset settings" implies, and there is no undo.
 *
 * Neither resets anything the application stores outside [Configuration]: plugin enable/disable
 * state, secrets, translation history, language files and installed plugins all live in their own
 * stores and are cleared by their own explicit actions.
 */
object SettingsReset {

    /**
     * Fields a preference reset leaves untouched.
     *
     * Named so the guarantee is testable and visible rather than implied by the absence of an
     * assignment. Client data (presets, bindings, pins, rules), operational state (geometry,
     * positions, pin state, nudge) and non-settings fields (version, compatibility) are here.
     */
    val PRESERVED_BY_PREFERENCE_RESET: Set<String> = setOf(
        // Not settings: schema version and a migration-only compatibility input.
        "configVersion",
        "legacySelectionIconEnabled",
        // Service configuration: presets, their selections and role scoping.
        "servicePresets",
        "activeServicePresetId",
        "disabledServices",
        // Custom bindings. "Reset this page" on Keyboard & Hotkeys restores them; a preference
        // reset must not, because there is no way to recover a hand-made set afterwards.
        "hotkeys",
        "ocrHotkeys",
        "writingHotkeys",
        // Lists the user authored.
        "pinnedLanguages",
        "translationRules",
        // Operational state written by the application as it runs.
        "mainWindowSize",
        "mainWindowPosition",
        "ocrWorkspaceSize",
        "ocrWorkspacePosition",
        "popupLastKnownSize",
        "popupLastKnownPosition",
        "quickDictionaryLastKnownSize",
        "quickDictionaryLastKnownPosition",
        "imageSearchLastKnownSize",
        "imageSearchLastKnownPosition",
        "isQuickDictionaryPinned",
        "donationNudgeShown",
        // Retained for configuration compatibility; no control writes it.
        "isDictionaryAutoPopupEnabled",
    )

    /**
     * What a reset restores: a fresh installation's settings.
     *
     * [Configuration.FRESH_INSTALL] rather than [Configuration.DEFAULT], because DEFAULT also has
     * to keep meaning "what a stored file without this field decodes to" — a reset should give the
     * user the product's current defaults, which is what a new install gets.
     */
    private val defaults: Configuration get() = Configuration.FRESH_INSTALL

    /**
     * Fields a preference reset restores to the fresh-install value.
     *
     * Every [Configuration] field is in exactly one of this set and
     * [PRESERVED_BY_PREFERENCE_RESET]; a test enforces that, so a field added later cannot be
     * silently neither reset nor preserved.
     */
    val RESET_BY_PREFERENCE_RESET: Set<String> = setOf(
        "launchOnSystemStartup",
        "autoCheckForUpdates",
        "isGlobalHotkeysEnabled",
        "selectionBehavior",
        "selectionReadSource",
        "interfaceLanguage",
        "isInstantTranslationEnabled",
        "autoCopyTranslation",
        "isSpellCheckingEnabled",
        "isRemoveLineBreaksEnabled",
        "extraOutputType",
        "extraOutputSource",
        "summaryLength",
        "rewriteStyle",
        "dictionaryAutoSource",
        "preferredSourceLanguage",
        "preferredTargetLanguage",
        "layoutPresetId",
        "toolbarVisibility",
        "showDictionaryPanel",
        "serviceSelectorStyle",
        "serviceSelectorAppearance",
        "closeButtonBehavior",
        "minimizeButtonBehavior",
        "themeId",
        "iconSetId",
        "useUnifiedTitleBar",
        "uiScale",
        "uiFontConfig",
        "editorFontConfig",
        "editorFallbackFontConfig",
        "closePopupsOnClickOutside",
        "isPopupAutoSizeEnabled",
        "isPopupAutoPositionEnabled",
        "popupTransparencyPercentage",
        "popupIdleTimeoutSeconds",
        "isQuickDictionaryAutoPositionEnabled",
        "quickDictionaryTransparencyPercentage",
        "quickDictionaryIdleTimeoutSeconds",
        "isImageSearchAutoPositionEnabled",
        "imageSearchTransparencyPercentage",
        "ocr",
        "network",
        "isHistoryEnabled",
        "clearHistoryOnExit",
    )

    /**
     * Restores the settings [page] owns and leaves every other field alone.
     *
     * The Services page keeps every preset: it resets the active preset's selections and the
     * role-scoping toggles, because deleting presets is a different action.
     */
    fun page(configuration: Configuration, page: SettingsPage): Configuration = when (page) {
        SettingsPage.GENERAL -> configuration.copy(
            launchOnSystemStartup = defaults.launchOnSystemStartup,
            autoCheckForUpdates = defaults.autoCheckForUpdates,
            selectionBehavior = defaults.selectionBehavior,
            selectionReadSource = defaults.selectionReadSource,
            isHistoryEnabled = defaults.isHistoryEnabled,
            clearHistoryOnExit = defaults.clearHistoryOnExit,
        )

        SettingsPage.APPEARANCE -> configuration.copy(
            interfaceLanguage = defaults.interfaceLanguage,
            themeId = defaults.themeId,
            iconSetId = defaults.iconSetId,
            useUnifiedTitleBar = defaults.useUnifiedTitleBar,
            uiScale = defaults.uiScale,
            uiFontConfig = defaults.uiFontConfig,
            editorFontConfig = defaults.editorFontConfig,
            editorFallbackFontConfig = defaults.editorFallbackFontConfig,
        )

        SettingsPage.SERVICES -> configuration.copy(
            disabledServices = defaults.disabledServices,
            servicePresets = resetActivePresetServices(configuration),
        )

        SettingsPage.BEHAVIOUR -> configuration.copy(
            isInstantTranslationEnabled = defaults.isInstantTranslationEnabled,
            autoCopyTranslation = defaults.autoCopyTranslation,
            isSpellCheckingEnabled = defaults.isSpellCheckingEnabled,
            isRemoveLineBreaksEnabled = defaults.isRemoveLineBreaksEnabled,
            extraOutputType = defaults.extraOutputType,
            extraOutputSource = defaults.extraOutputSource,
            summaryLength = defaults.summaryLength,
            rewriteStyle = defaults.rewriteStyle,
            dictionaryAutoSource = defaults.dictionaryAutoSource,
        )

        SettingsPage.LANGUAGES -> configuration.copy(
            preferredSourceLanguage = defaults.preferredSourceLanguage,
            preferredTargetLanguage = defaults.preferredTargetLanguage,
            pinnedLanguages = defaults.pinnedLanguages,
            translationRules = defaults.translationRules,
        )

        SettingsPage.WINDOW -> configuration.copy(
            layoutPresetId = defaults.layoutPresetId,
            toolbarVisibility = defaults.toolbarVisibility,
            showDictionaryPanel = defaults.showDictionaryPanel,
            serviceSelectorStyle = defaults.serviceSelectorStyle,
            serviceSelectorAppearance = defaults.serviceSelectorAppearance,
            closeButtonBehavior = defaults.closeButtonBehavior,
            minimizeButtonBehavior = defaults.minimizeButtonBehavior,
        )

        SettingsPage.POPUPS -> configuration.copy(
            closePopupsOnClickOutside = defaults.closePopupsOnClickOutside,
            isPopupAutoSizeEnabled = defaults.isPopupAutoSizeEnabled,
            isPopupAutoPositionEnabled = defaults.isPopupAutoPositionEnabled,
            popupTransparencyPercentage = defaults.popupTransparencyPercentage,
            popupIdleTimeoutSeconds = defaults.popupIdleTimeoutSeconds,
            isQuickDictionaryAutoPositionEnabled = defaults.isQuickDictionaryAutoPositionEnabled,
            quickDictionaryTransparencyPercentage = defaults.quickDictionaryTransparencyPercentage,
            quickDictionaryIdleTimeoutSeconds = defaults.quickDictionaryIdleTimeoutSeconds,
            isImageSearchAutoPositionEnabled = defaults.isImageSearchAutoPositionEnabled,
            imageSearchTransparencyPercentage = defaults.imageSearchTransparencyPercentage,
        )

        SettingsPage.OCR -> configuration.copy(ocr = defaults.ocr)

        SettingsPage.KEYBOARD -> configuration.copy(
            isGlobalHotkeysEnabled = defaults.isGlobalHotkeysEnabled,
            hotkeys = defaults.hotkeys,
            ocrHotkeys = defaults.ocrHotkeys,
            writingHotkeys = defaults.writingHotkeys,
        )

        SettingsPage.NETWORK -> configuration.copy(network = defaults.network)
    }

    /**
     * Restores ordinary preferences; keeps everything in [PRESERVED_BY_PREFERENCE_RESET].
     */
    fun preferences(configuration: Configuration): Configuration = configuration.copy(
        // General
        launchOnSystemStartup = defaults.launchOnSystemStartup,
        autoCheckForUpdates = defaults.autoCheckForUpdates,
        isGlobalHotkeysEnabled = defaults.isGlobalHotkeysEnabled,
        selectionBehavior = defaults.selectionBehavior,
        selectionReadSource = defaults.selectionReadSource,
        interfaceLanguage = defaults.interfaceLanguage,
        isHistoryEnabled = defaults.isHistoryEnabled,
        clearHistoryOnExit = defaults.clearHistoryOnExit,
        // Translation behaviour
        isInstantTranslationEnabled = defaults.isInstantTranslationEnabled,
        autoCopyTranslation = defaults.autoCopyTranslation,
        isSpellCheckingEnabled = defaults.isSpellCheckingEnabled,
        isRemoveLineBreaksEnabled = defaults.isRemoveLineBreaksEnabled,
        extraOutputType = defaults.extraOutputType,
        extraOutputSource = defaults.extraOutputSource,
        summaryLength = defaults.summaryLength,
        rewriteStyle = defaults.rewriteStyle,
        dictionaryAutoSource = defaults.dictionaryAutoSource,
        // Default languages (the pins and rules themselves stay)
        preferredSourceLanguage = defaults.preferredSourceLanguage,
        preferredTargetLanguage = defaults.preferredTargetLanguage,
        // Window
        layoutPresetId = defaults.layoutPresetId,
        toolbarVisibility = defaults.toolbarVisibility,
        showDictionaryPanel = defaults.showDictionaryPanel,
        serviceSelectorStyle = defaults.serviceSelectorStyle,
        serviceSelectorAppearance = defaults.serviceSelectorAppearance,
        closeButtonBehavior = defaults.closeButtonBehavior,
        minimizeButtonBehavior = defaults.minimizeButtonBehavior,
        // Appearance
        themeId = defaults.themeId,
        iconSetId = defaults.iconSetId,
        useUnifiedTitleBar = defaults.useUnifiedTitleBar,
        uiScale = defaults.uiScale,
        uiFontConfig = defaults.uiFontConfig,
        editorFontConfig = defaults.editorFontConfig,
        editorFallbackFontConfig = defaults.editorFallbackFontConfig,
        // Popups
        closePopupsOnClickOutside = defaults.closePopupsOnClickOutside,
        isPopupAutoSizeEnabled = defaults.isPopupAutoSizeEnabled,
        isPopupAutoPositionEnabled = defaults.isPopupAutoPositionEnabled,
        popupTransparencyPercentage = defaults.popupTransparencyPercentage,
        popupIdleTimeoutSeconds = defaults.popupIdleTimeoutSeconds,
        isQuickDictionaryAutoPositionEnabled = defaults.isQuickDictionaryAutoPositionEnabled,
        quickDictionaryTransparencyPercentage = defaults.quickDictionaryTransparencyPercentage,
        quickDictionaryIdleTimeoutSeconds = defaults.quickDictionaryIdleTimeoutSeconds,
        isImageSearchAutoPositionEnabled = defaults.isImageSearchAutoPositionEnabled,
        imageSearchTransparencyPercentage = defaults.imageSearchTransparencyPercentage,
        // OCR and network
        ocr = defaults.ocr,
        network = defaults.network,
    )

    /**
     * The active preset's service selections go back to the shipped defaults; the preset itself,
     * its name and every other preset are untouched.
     */
    private fun resetActivePresetServices(configuration: Configuration): List<ServicePreset> {
        val defaultSelections = defaults.getActivePreset()?.selectedServices.orEmpty()
        val defaultComparisons = defaults.getActivePreset()?.comparisonTranslatorIds.orEmpty()
        return configuration.servicePresets.map { preset ->
            if (preset.id == configuration.activeServicePresetId) {
                preset.copy(
                    selectedServices = defaultSelections,
                    comparisonTranslatorIds = defaultComparisons,
                )
            } else {
                preset
            }
        }
    }
}
