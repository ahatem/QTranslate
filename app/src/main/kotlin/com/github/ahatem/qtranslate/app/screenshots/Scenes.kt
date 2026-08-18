package com.github.ahatem.qtranslate.app.screenshots

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType

/**
 * The gallery of scenes, in the order they are captured.
 *
 * Kept apart from the driving code so the set can be extended without touching the harness: the
 * website shows all of these, the README picks a handful.
 *
 * ### Window size
 * Given in density-independent pixels and scaled to the display. Every main-window scene shares
 * the same [WINDOW] size so the gallery reads as one set: side by side on a page, a per-scene
 * jumble of sizes is the difference between a collection of screenshots and a tour of the app.
 * The size is wide enough for a docked dictionary, and the scenes' prose is long enough to wrap
 * and fill the panes that would otherwise look empty.
 */
internal object Scenes {

    const val DARK = "custom:qtranslate_dark"
    const val LIGHT = "custom:qtranslate_light"

    /**
     * The native zoom every scene is captured at, applied through the app itself — fonts, icons,
     * insets and window all scale from [Configuration.uiScale], using FlatLaf's own derivation
     * rather than a forced JVM-wide scale. 2 at 200% doubles the output like a 200% display does,
     * while the application render itself stays untampered with.
     */
    const val OUTPUT_SCALE = 2

    /** [OUTPUT_SCALE] as the [Configuration.uiScale] percentage the app zooms to. */
    const val SCALE_PERCENT = 100 * OUTPUT_SCALE

    /**
     * The share of the width every docked dictionary gets, whatever the window or theme. Fixed
     * here so the column is identical in every shot the dictionary appears in, and stable across
     * re-renders.
     */
    const val DICTIONARY_SPLIT = 0.65

    /**
     * Shared by every scene. Update checks and the selection icon would reach the network and the
     * desktop for no benefit, and system-wide hotkeys would steal Ctrl+Q from whatever else the
     * machine is doing while a capture runs — clearing the list is not enough, since the config
     * migration fills missing bindings back in, so the feature is switched off instead.
     */
    val BASE: Configuration = Configuration.DEFAULT.copy(
        themeId = DARK,
        uiScale = SCALE_PERCENT,
        autoCheckForUpdates = false,
        isInstantTranslationEnabled = false,
        isSelectionIconEnabled = false,
        isGlobalHotkeysEnabled = false
    )

// ── size ─────────────────────────────────────────────────────────────────

    /**
     * The size every main-window scene, and the settings dialog drawn beside its owner, is
     * authored at. Density-independent at 100%; [windowSize] maps it to the device pixels the
     * configured [SCALE_PERCENT] zoom needs.
     */
    val WINDOW = 920 to 520

    // ── passages ─────────────────────────────────────────────────────────────
    //
    // Real prose rather than filler, and long enough to wrap a few lines: a pane holding one short
    // sentence reads as a mock-up no matter how real it is.

    const val PERISTALSIS =
        "Peristalsis moves food through the digestive tract by rhythmic contraction of smooth " +
            "muscle. The wave begins in the oesophagus and continues through the stomach and " +
            "intestines, so swallowing works even when you are lying down."

    const val PITCH =
        "QTranslate puts a translator one shortcut away. Select text anywhere, press the " +
            "shortcut, and read the translation without leaving what you were doing."

    const val LIBRARY =
        "The library will be closed on Monday for the public holiday and will reopen on Tuesday " +
            "morning at nine. Books due over the weekend may be returned using the drop box " +
            "beside the main entrance."

    const val VACCINE =
        "Store the vaccine between two and eight degrees Celsius and protect it from light. Do " +
            "not freeze. Once the vial is opened, use the remaining doses within six hours."

    const val ARABIC_PERISTALSIS =
        "تعمل الحركة الدودية على دفع الطعام عبر الجهاز الهضمي عن طريق انقباض منتظم للعضلات " +
            "الملساء، وتبدأ الموجة من المريء وتستمر حتى الأمعاء."

    /** Short enough that the popup keeps the compact shape it has in use. */
    const val SELECTION =
        "Select text in any application and press the shortcut to read it in your language."

    // ── settings pages ────────────────────────────────────────────────────────

    /**
     * Sidebar rows, in the order the settings dialog builds them. Every page is captured: they
     * are the clearest evidence of how much the app actually does. Rows 2 and 6 are the
     * Translation and Interface group headings, which have no page of their own.
     */
    val SETTINGS_PAGES = listOf(
        0 to "general",
        1 to "appearance",
        3 to "services",
        4 to "behavior",
        5 to "languages",
        7 to "layout",
        8 to "popups",
        9 to "hotkeys",
        10 to "plugins",
        11 to "network",
    )

    // ── history ───────────────────────────────────────────────────────────────

    /** Run before the history shot so the table has a plausible spread of work in it. */
    val HISTORY_WARMUP = listOf(
        LanguageCode("fr") to LIBRARY,
        LanguageCode("de") to VACCINE,
        LanguageCode("es") to PITCH,
        LanguageCode("ar") to PERISTALSIS,
    )

    // ── configurations ────────────────────────────────────────────────────────

    fun classic(theme: String, size: Pair<Int, Int> = WINDOW): Configuration =
        BASE.copy(themeId = theme, layoutPresetId = "classic", mainWindowSize = windowSize(size))

    fun sideBySide(theme: String, size: Pair<Int, Int> = WINDOW): Configuration =
        BASE.copy(themeId = theme, layoutPresetId = "side_by_side", mainWindowSize = windowSize(size))

    fun compact(theme: String, size: Pair<Int, Int> = WINDOW): Configuration =
        BASE.copy(themeId = theme, layoutPresetId = "compact", mainWindowSize = windowSize(size))

    fun hero(theme: String, size: Pair<Int, Int> = WINDOW): Configuration = BASE.copy(
        themeId = theme,
        layoutPresetId = "classic",
        extraOutputType = ExtraOutputType.BackwardTranslate,
        mainWindowSize = windowSize(size)
    )

    /**
     * The bundled file is `ar-SA.toml`; a bare "ar" finds nothing and silently falls back to the
     * embedded English strings, leaving the window left-to-right.
     */
    fun arabic(layout: String, size: Pair<Int, Int> = WINDOW): Configuration = BASE.copy(
        layoutPresetId = layout,
        interfaceLanguage = "ar-SA",
        mainWindowSize = windowSize(size)
    )
}