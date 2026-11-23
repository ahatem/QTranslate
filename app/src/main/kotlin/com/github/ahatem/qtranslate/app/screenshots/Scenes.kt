package com.github.ahatem.qtranslate.app.screenshots

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.Density
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.data.LayoutProfile
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowPreset
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout

/**
 * The gallery of scenes, in the order they are captured.
 *
 * Kept apart from the driving code so the set can be extended without touching the harness: the
 * website shows all of these, the README picks a handful.
 *
 * ### Window size
 * Given in density-independent pixels and scaled to the display. Each size is chosen for the
 * layout it shows: a scene's window is authored at whatever width and height that arrangement
 * genuinely reads best at, not stretched to make the gallery uniform — uniformity is the
 * composite canvas's job (see [ScreenshotCompositor]). The prose is long enough to wrap and
 * fill the panes that would otherwise look empty.
 */
internal object Scenes {

    const val DARK = "custom:qtranslate_dark"
    const val LIGHT = "custom:qtranslate_light"

    /**
     * The theme and filename suffix of every paired capture. A scene that appears on the website
     * is always captured once per entry here, with the same content, so the two files differ by
     * the theme alone and the site can pick the matching one.
     */
    val THEMES = listOf(DARK to "dark", LIGHT to "light")

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
        selectionBehavior = SelectionBehavior.OFF,
        isGlobalHotkeysEnabled = false,
        // Configuration.DEFAULT now leaves this blank so a fresh install detects the OS language
        // (see Configuration.DEFAULT's own comment) -- exactly the behavior a capture machine must
        // NOT inherit. The public gallery is English regardless of whatever locale happens to be
        // configured on whichever machine runs the harness; every non-English scene overrides this
        // explicitly (see arabic() below).
        interfaceLanguage = "en-GB",
        // Deliberate presentation choice for the public screenshot gallery only -- production's
        // own default (Inter) is untouched. The fallback stays whatever the production default
        // is, so scripts Rubik cannot render still fall back the same way a real install would.
        uiFontConfig = FontConfig(name = "Rubik", size = Configuration.DEFAULT.uiFontConfig.size),
        editorFontConfig = FontConfig(name = "Rubik", size = Configuration.DEFAULT.editorFontConfig.size)
    )

// ── size ─────────────────────────────────────────────────────────────────

    /**
     * The size main-window scenes, and the settings dialog drawn beside their owner, are
     * authored at. Tuned for the classic and side-by-side layouts: wide enough for a docked
     * dictionary, narrow enough that inputs keep a natural measure instead of stretching.
     * Density-independent at 100%; [windowSize] maps it to the device pixels the configured
     * [SCALE_PERCENT] zoom needs.
     */
    val WINDOW = 780 to 540

    /** A taller frame gives the Comparison board room for several readable results. */
    val COMPARISON_WINDOW = 760 to 700

    /** Narrow enough that Side By Side stacks its Input and Output. */
    val NARROW_WINDOW = 520 to 520

    // ── passages ─────────────────────────────────────────────────────────────
    //
    // Real prose rather than filler, and long enough to wrap a few lines: a pane holding one short
    // sentence reads as a mock-up no matter how real it is.

    const val PERISTALSIS =
        "Peristalsis moves food through the digestive tract by rhythmic contraction of smooth " +
            "muscle. The wave begins in the oesophagus and continues through the stomach and " +
            "intestines, so swallowing works even when you are lying down."

    const val PERISTALSIS_HERO =
        "Peristalsis moves food through the digestive tract by rhythmic contraction of smooth " +
            "muscle. The wave continues through the stomach and intestines."

    const val PITCH =
        "QTranslate puts a translator one shortcut away. Select text anywhere, press the " +
            "shortcut, and read the translation without leaving what you were doing."

    const val LIBRARY =
        "The library will be closed on Monday for the public holiday and will reopen on Tuesday " +
            "morning at nine. Books due over the weekend may be returned using the drop box " +
            "beside the main entrance."

    const val COMPARISON =
        "The library will close for the public holiday on Monday and reopen at nine on Tuesday morning."

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
        9 to "ocr",
        10 to "hotkeys",
        11 to "plugins",
        12 to "network",
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
    //
    // Each scene states the window its subject needs as a [WindowLayout], written with
    // [withWindowLayout]. The window reads that description, so a scene and the window it
    // produces agree by construction rather than through a layout id the window ignores.

    /** The default window: stacked panes, history on top, languages above results, picker below. */
    val CLASSIC: WindowLayout = WindowLayout.DEFAULT

    /** The Modern classic card: the v1.5.0 window, which is also the default. */
    val MODERN_CLASSIC_CARD: WindowLayout = WindowPreset.MODERN_CLASSIC.applyTo(WindowLayout.DEFAULT)

    /** The Classic card (QTranslate 6): a bottom strip of service tabs and dense spacing. */
    val CLASSIC_CARD: WindowLayout = WindowPreset.CLASSIC.applyTo(WindowLayout.DEFAULT)

    /** The Focus card: one shared row, no service row, no status bar. */
    val FOCUS_CARD: WindowLayout = WindowPreset.FOCUS.applyTo(WindowLayout.DEFAULT)

    /** History and languages sharing one row at the top: the Compact card. */
    val COMPACT_ROW: WindowLayout = WindowPreset.COMPACT.applyTo(WindowLayout.DEFAULT)

    // Density scenes: each card in the other density, to compare spacing.
    val MODERN_CLASSIC_COMPACT: WindowLayout = MODERN_CLASSIC_CARD.copy(density = Density.COMPACT)
    val CLASSIC_COMFORTABLE: WindowLayout = CLASSIC_CARD.copy(density = Density.COMFORTABLE)

    /** Input and output beside each other, with history and languages above both columns. */
    val SIDE_BY_SIDE = WindowLayout(
        panes = PaneLayout.SIDE_BY_SIDE,
        history = RegionSlot.TOP,
        languages = RegionSlot.TOP,
        services = RegionSlot.BOTTOM,
    )

    /** One input translated by several services, with the languages above the board. */
    val COMPARISON_BOARD = WindowLayout(panes = PaneLayout.COMPARISON)

    /**
     * Classic panes with the strip of adjacent service tabs instead of the picker.
     *
     * The one arrangement the selector scenes ask for that no preset card offers: the Layout page
     * reaches it one value at a time, so it is spelled out here.
     */
    val CLASSIC_SELECTOR = WindowLayout(serviceWidget = ServiceWidget.STRIP)

    fun classic(theme: String, size: Pair<Int, Int> = WINDOW): Configuration =
        BASE.copy(themeId = theme, mainWindowSize = windowSize(size))
            .withWindowLayout(CLASSIC)

    /** Classic panes with one shared row instead of two: the Compact card. */
    fun classicCompact(theme: String, size: Pair<Int, Int> = WINDOW): Configuration =
        BASE.copy(themeId = theme, mainWindowSize = windowSize(size))
            .withWindowLayout(COMPACT_ROW)

    /**
     * Any one window layout, in English or in Arabic, for the curated slot list.
     *
     * The bundled file is `ar-SA.toml`; a bare "ar" finds nothing and silently falls back to the
     * embedded English strings, leaving the window left-to-right.
     */
    fun slots(layout: WindowLayout, theme: String, rtl: Boolean = false, size: Pair<Int, Int> = WINDOW): Configuration =
        BASE.copy(
            themeId = theme,
            interfaceLanguage = if (rtl) "ar-SA" else "en-GB",
            mainWindowSize = windowSize(size),
        ).withWindowLayout(layout)

    /** A tall frame, so the Layout page's outline, cards and saved layouts are all in the shot. */
    val SETTINGS_OUTLINE_WINDOW = 860 to 980

    /** The layouts saved for the Settings outline scenes: one equal to the default and one that is not. */
    val OUTLINE_PROFILES = listOf(
        LayoutProfile("Everyday", WindowLayout.DEFAULT),
        LayoutProfile("Reading", FOCUS_CARD),
    )

    /**
     * The window the Settings outline scenes start from: [layout] drawn on the Layout page, with a
     * second result chosen when [secondResult] is true and the saved layouts of [OUTLINE_PROFILES]
     * when [withProfiles] is true.
     */
    fun settingsOutline(
        layout: WindowLayout,
        theme: String,
        rtl: Boolean = false,
        withProfiles: Boolean = false,
        secondResult: Boolean = false,
    ): Configuration = slots(layout, theme, rtl, SETTINGS_OUTLINE_WINDOW).copy(
        layoutProfiles = if (withProfiles) OUTLINE_PROFILES else emptyList(),
        extraOutputType = if (secondResult) ExtraOutputType.BackwardTranslate else ExtraOutputType.None,
    )

    fun classicSelector(theme: String, size: Pair<Int, Int> = WINDOW): Configuration =
        classic(theme, size).withWindowLayout(CLASSIC_SELECTOR)

    fun sideBySide(theme: String, size: Pair<Int, Int> = WINDOW): Configuration =
        BASE.copy(themeId = theme, mainWindowSize = windowSize(size))
            .withWindowLayout(SIDE_BY_SIDE)

    fun comparison(theme: String, size: Pair<Int, Int> = COMPARISON_WINDOW): Configuration =
        BASE.copy(themeId = theme, mainWindowSize = windowSize(size))
            .withWindowLayout(COMPARISON_BOARD)

    fun hero(theme: String, size: Pair<Int, Int> = WINDOW): Configuration = BASE.copy(
        themeId = theme,
        extraOutputType = ExtraOutputType.BackwardTranslate,
        mainWindowSize = windowSize(size)
    ).withWindowLayout(CLASSIC)

    /**
     * The bundled file is `ar-SA.toml`; a bare "ar" finds nothing and silently falls back to the
     * embedded English strings, leaving the window left-to-right.
     */
    fun arabic(layout: WindowLayout, theme: String = DARK, size: Pair<Int, Int> = WINDOW): Configuration = BASE.copy(
        themeId = theme,
        interfaceLanguage = "ar-SA",
        mainWindowSize = windowSize(size)
    ).withWindowLayout(layout)
}
