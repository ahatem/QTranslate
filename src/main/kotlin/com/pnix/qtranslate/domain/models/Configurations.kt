package com.pnix.qtranslate.domain.models

import com.formdev.flatlaf.FlatDarculaLaf
import com.formdev.flatlaf.FlatIntelliJLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.intellijthemes.*
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubDarkIJTheme
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialOceanicIJTheme
import java.util.prefs.Preferences
import javax.swing.JLabel
import kotlin.properties.Delegates


enum class Theme(val readableName: String, val lookAndFeel: FlatLaf) {
  DEFAULT_LIGHT("Default (Light)", FlatIntelliJLaf()),
  DEFAULT_DARK("Default (Dark)", FlatDarculaLaf()),

  DARK_PURPLE("Purple (Dark)", FlatDarkPurpleIJTheme()),
  DARK_HIBERBEE("Hiberbee (Dark)", FlatHiberbeeDarkIJTheme()),
  DARK_MATERIAL_OCEANIC("Oceanic (Dark)", FlatMaterialOceanicIJTheme()),
  DARK_GITHUB("Pale-Red (Dark)", FlatGitHubDarkIJTheme()),
  DARK_VUESION("Vuesion (Dark)", FlatVuesionIJTheme()),
  DARK_SOLARIZED("Solarized (Dark)", FlatSolarizedDarkIJTheme()),

  LIGHT_CYAN("Cyan (Light)", FlatCyanLightIJTheme()),
  LIGHT_SOLARIZED("Solarized (Light)", FlatSolarizedLightIJTheme());

  companion object {
    fun getThemeByReadableName(readableName: String): Theme? {
      return Theme.values().find { it.readableName == readableName }
    }
  }
}

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


  // * Basics
  var startWithWindows by Delegates.observable(prefs.getBoolean("start_with_windows", false))
  { _, _, newValue -> prefs.putBoolean("start_with_windows", newValue) }

  var interfaceLanguage: String by Delegates.observable(prefs.get("interface_language", "English"))
  { _, _, newValue -> prefs.put("interface_language", newValue) }

  var inputsFontName: String by Delegates.observable(prefs.get("inputs_font_name", JLabel().font.family))
  { _, _, newValue -> prefs.put("inputs_font_name", newValue) }

  var inputsFontSize by Delegates.observable(prefs.getInt("inputs_font_size", 16))
  { _, _, newValue -> prefs.putInt("inputs_font_size", newValue) }

  var autoDetectFirstLanguage: String by Delegates.observable(prefs.get("auto_detect_first_language", "eng"))
  { _, _, newValue -> prefs.put("auto_detect_first_language", newValue) }

  var autoDetectSecondLanguage: String by Delegates.observable(prefs.get("auto_detect_second_language", "ara"))
  { _, _, newValue -> prefs.put("auto_detect_second_language", newValue) }

  var enableHistory by Delegates.observable(prefs.getBoolean("enable_history", true))
  { _, _, newValue -> prefs.putBoolean("enable_history", newValue) }

  var clearHistoryOnExist by Delegates.observable(prefs.getBoolean("clear_history_on_exit", false))
  { _, _, newValue -> prefs.putBoolean("clear_history_on_exit", newValue) }


  // * Appearance
  var enableWindowStyle by Delegates.observable(prefs.getBoolean("enable_window_style", true))
  { _, _, newValue -> prefs.putBoolean("enable_window_style", newValue) }

  var unifyTitleBar by Delegates.observable(prefs.getBoolean("unify_title_bar", true))
  { _, _, newValue -> prefs.putBoolean("unify_title_bar", newValue) }

  var theme by Delegates.observable(Theme.valueOf(prefs.get("theme", Theme.DARK_HIBERBEE.name)))
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

  var popupTransparency by Delegates.observable(prefs.getInt("popup_transparency", 15))
  { _, _, newValue -> prefs.putInt("popup_transparency", newValue) }

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

  // *Appearance
  var enableWindowStyle: Boolean = Configurations.enableWindowStyle,
  var unifyTitleBar: Boolean = Configurations.unifyTitleBar,
  var theme: Theme = Configurations.theme,
  var popupEnableAutoSize: Boolean = Configurations.popupEnableAutoSize,
  var popupEnableAutoPosition: Boolean = Configurations.popupEnableAutoPosition,
  var popupEnablePinWhenDragging: Boolean = Configurations.popupEnablePinWhenDragging,
  var popupAutoHideDelay: Int = Configurations.popupAutoHideDelay,
  var popupTransparency: Int = Configurations.popupTransparency,
)