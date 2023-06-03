package com.pnix.qtranslate.models

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.intellijthemes.*
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialOceanicIJTheme
import com.formdev.flatlaf.themes.FlatMacLightLaf
import com.pnix.qtranslate.common.QTranslate
import com.pnix.qtranslate.utils.fileToLaf
import com.pnix.qtranslate.utils.getDefaultFontFamily
import java.util.prefs.Preferences
import kotlin.properties.Delegates

enum class Theme(val readableName: String, val lookAndFeel: FlatLaf) {
  DARK_SHARPER("Dark  - Default", fileToLaf("ReSharperDark.theme.json")),
  LIGHT_SHARPER("Light - Default", fileToLaf("ReSharperLight.theme.json")),

  DARK_X_DARK("Dark  - XDark", fileToLaf("XcodeDark.theme.json")),
  DARK_MODERN_BLACK("Dark  - Modern Black", fileToLaf("vscode_dark_modern.theme.json")),
  DARK_BLACK("Dark  - Black", fileToLaf("github-dark-default.theme.json")),
  DARK_GENTLE("Dark  - Godot", fileToLaf("godot_theme.theme.json")),
  DARK_ONE_DARK("Dark  - One Dark", FlatOneDarkIJTheme()),
  DARK_PURPLE("Dark  - Purple", FlatDarkPurpleIJTheme()),
  DARK_HIBERBEE("Dark  - Hiberbee", FlatHiberbeeDarkIJTheme()),
  DARK_MATERIAL_OCEANIC("Dark  - Oceanic Green", FlatMaterialOceanicIJTheme()),
  DARK_VUESION("Dark  - Vuesion", FlatVuesionIJTheme()),
  DARK_SOLARIZED("Dark  - Solarized", FlatSolarizedDarkIJTheme()),

  LIGHT_MAC("Light - Mac", FlatMacLightLaf()),
  LIGHT_GRAY("Light - Gray", FlatGrayIJTheme()),
  LIGHT_VITESSE("Light - Vitesse", fileToLaf("vitesse.light.soft.theme.json")),
  LIGHT_ESPRESSO("Light - Espresso", fileToLaf("espresso_light.theme.json")),
  LIGHT_SOLARIZED("Light - Solarized", FlatSolarizedLightIJTheme());

  companion object {
    fun getThemeByReadableName(readableName: String) =
      values().find { it.readableName == readableName } ?: fallbackTheme(readableName)

    fun from(name: String) =
      values().find { it.name == name } ?: fallbackTheme(name)

    private fun fallbackTheme(name: String) =
      if (name.contains("light", true)) LIGHT_SHARPER else DARK_SHARPER
  }
}

data class Configuration(
  // * Basics
  var startWithWindows: Boolean = Configurations.startWithWindows,
  var interfaceLanguage: String = Configurations.interfaceLanguage,
  var inputsFontName: String = Configurations.inputsFontName,
  var inputsFontSize: Int = Configurations.inputsFontSize,
  var autoDetectFirstLanguage: String = Configurations.autoDetectFirstLanguage,
  var autoDetectSecondLanguage: String = Configurations.autoDetectSecondLanguage,
  var enableHistory: Boolean = Configurations.enableHistory,
  var clearHistoryOnExist: Boolean = Configurations.clearHistoryOnExist,
  var expandHistoryItems: Boolean = Configurations.expandHistoryItems,

  // * Services
  var excludedTranslators: MutableSet<String> = Configurations.excludedTranslators,

  // * Appearance
  var enableWindowStyle: Boolean = Configurations.enableWindowStyle,
  var unifyTitleBar: Boolean = Configurations.unifyTitleBar,
  var theme: Theme = Configurations.theme,
  var popupEnableAutoSize: Boolean = Configurations.popupEnableAutoSize,
  var popupEnableAutoPosition: Boolean = Configurations.popupEnableAutoPosition,
  var popupEnablePinWhenDragging: Boolean = Configurations.popupEnablePinWhenDragging,
  var popupAutoHideDelay: Int = Configurations.popupAutoHideDelay,
  var popupTransparency: Int = Configurations.popupTransparency,
)

object Configurations {
  fun use(configuration: Configuration) {
    // * Basics
    startWithWindows = configuration.startWithWindows
    interfaceLanguage = configuration.interfaceLanguage
    inputsFontName = configuration.inputsFontName
    inputsFontSize = configuration.inputsFontSize
    autoDetectFirstLanguage = configuration.autoDetectFirstLanguage
    autoDetectSecondLanguage = configuration.autoDetectSecondLanguage
    enableHistory = configuration.enableHistory
    clearHistoryOnExist = configuration.clearHistoryOnExist
    expandHistoryItems = configuration.expandHistoryItems

    // * Services
    excludedTranslators = configuration.excludedTranslators

    // * Appearance
    enableWindowStyle = configuration.enableWindowStyle
    unifyTitleBar = configuration.unifyTitleBar
    theme = configuration.theme
    popupEnableAutoSize = configuration.popupEnableAutoSize
    popupEnableAutoPosition = configuration.popupEnableAutoPosition
    popupEnablePinWhenDragging = configuration.popupEnablePinWhenDragging
    popupAutoHideDelay = configuration.popupAutoHideDelay
    popupTransparency = configuration.popupTransparency
  }

  private val prefs = Preferences.userRoot().node("QTranslate")

  var spellChecking by Delegates.observable(prefs.getBoolean("spell_checking", false))
  { _, _, newValue -> prefs.putBoolean("spell_checking", newValue) }

  var instantTranslation by Delegates.observable(prefs.getBoolean("instant_translation", false))
  { _, _, newValue -> prefs.putBoolean("instant_translation", newValue) }

  var showBackwardTranslationPanel by Delegates.observable(prefs.getBoolean("sow_backward_translation_panel", false))
  { _, _, newValue -> prefs.putBoolean("sow_backward_translation_panel", newValue) }

  var showHistoryPanel by Delegates.observable(prefs.getBoolean("show_history_panel", true))
  { _, _, newValue -> prefs.putBoolean("show_history_panel", newValue) }

  var showTranslationOptionsPanel by Delegates.observable(prefs.getBoolean("show_translation_options_panel", true))
  { _, _, newValue -> prefs.putBoolean("show_translation_options_panel", newValue) }

  var showServicesPanel by Delegates.observable(prefs.getBoolean("show_services_panel", true))
  { _, _, newValue -> prefs.putBoolean("show_services_panel", newValue) }

  var showStatusPanel by Delegates.observable(prefs.getBoolean("show_status_panel", true))
  { _, _, newValue -> prefs.putBoolean("show_status_panel", newValue) }

  var lastOptionOpened: String by Delegates.observable(prefs.get("last_option_opened", "basics"))
  { _, _, newValue -> prefs.put("last_option_opened", newValue) }

  var layoutPreset: String by Delegates.observable(prefs.get("layout_preset", "preset_2"))
  { _, _, newValue -> prefs.put("layout_preset", newValue) }

  var enableGlobalHotkeys by Delegates.observable(prefs.getBoolean("enable_global_hotkeys", true))
  { _, _, newValue -> prefs.putBoolean("enable_global_hotkeys", newValue) }

  var latestVersionNumber by Delegates.observable(prefs.getInt("latest_version_number", QTranslate.VERSION_NUMBER))
  { _, _, newValue -> prefs.putInt("latest_version_number", newValue) }

  var skippedVersionNumber by Delegates.observable(prefs.getInt("skipped_version_number", -1))
  { _, _, newValue -> prefs.putInt("skipped_version_number", newValue) }

  var autoCheckForUpdates by Delegates.observable(prefs.getBoolean("auto_check_for_updates", true))
  { _, _, newValue -> prefs.putBoolean("auto_check_for_updates", newValue) }


  // * Basics
  var startWithWindows by Delegates.observable(prefs.getBoolean("start_with_windows", false))
  { _, _, newValue -> prefs.putBoolean("start_with_windows", newValue) }

  var interfaceLanguage: String by Delegates.observable(prefs.get("interface_language", "en"))
  { _, _, newValue -> prefs.put("interface_language", newValue) }

  var inputsFontName: String by Delegates.observable(prefs.get("inputs_font_name", getDefaultFontFamily()))
  { _, _, newValue -> prefs.put("inputs_font_name", newValue) }

  var inputsFontSize by Delegates.observable(prefs.getInt("inputs_font_size", 14))
  { _, _, newValue -> prefs.putInt("inputs_font_size", newValue) }

  var autoDetectFirstLanguage: String by Delegates.observable(prefs.get("auto_detect_first_language", "eng"))
  { _, _, newValue -> prefs.put("auto_detect_first_language", newValue) }

  var autoDetectSecondLanguage: String by Delegates.observable(prefs.get("auto_detect_second_language", "ara"))
  { _, _, newValue -> prefs.put("auto_detect_second_language", newValue) }

  var enableHistory by Delegates.observable(prefs.getBoolean("enable_history", true))
  { _, _, newValue -> prefs.putBoolean("enable_history", newValue) }

  var clearHistoryOnExist by Delegates.observable(prefs.getBoolean("clear_history_on_exit", true))
  { _, _, newValue -> prefs.putBoolean("clear_history_on_exit", newValue) }

  var expandHistoryItems by Delegates.observable(prefs.getBoolean("expand_history_items", false))
  { _, _, newValue -> prefs.putBoolean("expand_history_items", newValue) }

  // * Services
  var excludedTranslators by Delegates.observable(prefs.get("excluded_translators", "").split(",").toMutableSet())
  { _, _, newValue -> prefs.put("excluded_translators", newValue.joinToString(",")) }


  // * Appearance
  var enableWindowStyle by Delegates.observable(prefs.getBoolean("enable_window_style", true))
  { _, _, newValue -> prefs.putBoolean("enable_window_style", newValue) }

  var unifyTitleBar by Delegates.observable(prefs.getBoolean("unify_title_bar", false))
  { _, _, newValue -> prefs.putBoolean("unify_title_bar", newValue) }

  var theme by Delegates.observable(Theme.from(prefs.get("theme", Theme.DARK_HIBERBEE.name)))
  { _, _, newValue -> prefs.put("theme", newValue.name) }

  var popupLastSize: String by Delegates.observable(prefs.get("popup_last_size", "250,120"))
  { _, _, newValue -> prefs.put("popup_last_size", newValue) }

  var popupLastPosition: String by Delegates.observable(prefs.get("popup_last_position", "0,0"))
  { _, _, newValue -> prefs.put("popup_last_position", newValue) }

  var popupEnableAutoSize by Delegates.observable(prefs.getBoolean("popup_enable_auto_size", true))
  { _, _, newValue -> prefs.putBoolean("popup_enable_auto_size", newValue) }

  var popupEnableAutoPosition by Delegates.observable(prefs.getBoolean("popup_enable_auto_position", true))
  { _, _, newValue -> prefs.putBoolean("popup_enable_auto_position", newValue) }

  var popupEnablePinWhenDragging by Delegates.observable(prefs.getBoolean("popup_enable_pin_when_dragging", true))
  { _, _, newValue -> prefs.putBoolean("popup_enable_pin_when_dragging", newValue) }

  var popupAutoHideDelay by Delegates.observable(prefs.getInt("popup_auto_hide_delay", 5))
  { _, _, newValue -> prefs.putInt("popup_auto_hide_delay", newValue) }

  var popupTransparency by Delegates.observable(prefs.getInt("popup_transparency", 0))
  { _, _, newValue -> prefs.putInt("popup_transparency", newValue) }

}
