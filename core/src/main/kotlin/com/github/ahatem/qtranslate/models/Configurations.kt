package com.github.ahatem.qtranslate.models

import com.formdev.flatlaf.intellijthemes.*
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTMaterialOceanicIJTheme
import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.common.QTranslate
import com.github.ahatem.qtranslate.utils.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.prefs.Preferences
import javax.swing.KeyStroke
import kotlin.properties.Delegates

// TODO: add Action callback (eg:- ActionListener interface) to allow something like Double Ctrl
data class Hotkey(
  val id: String,
  val description: String,
  val modifiers: Int,
  val keyCode: Int,
  val customizable: Boolean = true,
  val hotkeyText: String? = null
) {

  constructor(id: String, description: String, keyStroke: KeyStroke) : this(
    id,
    description,
    keyStroke.modifiers,
    keyStroke.keyCode
  )

  fun toKeyStroke(): KeyStroke {
    return KeyStroke.getKeyStroke(keyCode, modifiers)
  }

  fun toUserReadableFormat(): String {
    return hotkeyText ?: toKeyStroke().getReadableKeyStrokeText()
  }

}

object Hotkeys {
  private val hotkeysPrefs = Preferences.userRoot().node("QTranslate")

  private val defaultsGlobalHotkeys
    get() = mapOf(
      "main_window" to Hotkey(
        "main_window",
        Localizer.localize("hotkey_panel_table_action_show_main_window"),
        InputEvent.CTRL_DOWN_MASK,
        0,
        false,
        "Double Ctrl"
      ),
      "popup_window" to Hotkey(
        "popup_window",
        Localizer.localize("hotkey_panel_table_action_show_popup_window"),
        controlKeyWith(KeyEvent.VK_Q)
      ),
      "listen_selected_text" to Hotkey(
        "listen_selected_text",
        Localizer.localize("hotkey_panel_table_action_listen_to_selected_text"),
        controlKeyWith(KeyEvent.VK_E)
      ),
      "text_recognition" to Hotkey(
        "text_recognition",
        Localizer.localize("hotkey_panel_table_action_text_recognition"),
        controlKeyWith(KeyEvent.VK_I)
      ),
      "cycle_services" to Hotkey(
        "cycle_services",
        Localizer.localize("hotkey_panel_table_action_cycle_services"),
        controlKeyWith(KeyEvent.VK_TAB)
      ),
    )

  private val defaultsWindowHotkeys
    get() = mapOf(
      "translate" to Hotkey(
        "translate",
        Localizer.localize("hotkey_panel_table_action_translate"),
        controlKeyWith(KeyEvent.VK_ENTER)
      ),
      "listen_to_input" to Hotkey(
        "listen_to_input",
        Localizer.localize("hotkey_panel_table_action_listen_to_input"),
        controlKeyWith(KeyEvent.VK_L)
      ),
      "listen_to_translation" to Hotkey(
        "listen_to_translation",
        Localizer.localize("hotkey_panel_table_action_listen_to_translation"),
        controlKeyWith(KeyEvent.VK_O)
      ),
      "clear_current_translation" to Hotkey(
        "clear_current_translation",
        Localizer.localize("hotkey_panel_table_action_clear_current_translation"),
        controlKeyWith(KeyEvent.VK_N)
      ),
      "swap_translation_direction" to Hotkey(
        "swap_translation_direction",
        Localizer.localize("hotkey_panel_table_action_swap_translation_direction"),
        controlKeyWith(KeyEvent.VK_PERIOD)
      ),
      "open_dictionary_dialog" to Hotkey(
        "open_dictionary_dialog",
        Localizer.localize("hotkey_panel_table_action_open_dictionary_dialog"),
        controlKeyWith(KeyEvent.VK_D)
      ),
      "open_history_dialog" to Hotkey(
        "open_history_dialog",
        Localizer.localize("hotkey_panel_table_action_open_history_dialog"),
        controlKeyWith(KeyEvent.VK_H)
      ),
      "reset_language_pair_to_auto_detected" to Hotkey(
        "reset_language_pair_to_auto_detected",
        Localizer.localize("hotkey_panel_table_action_reset_language_pair_to_auto_detected"),
        shiftKeyWith(KeyEvent.VK_ESCAPE)
      ),
      "how_to_use" to Hotkey(
        "how_to_use",
        Localizer.localize("hotkey_panel_table_action_how_to_use"),
        singleKey(KeyEvent.VK_F1)
      ),
      "toggle_full_screen" to Hotkey(
        "toggle_full_screen",
        Localizer.localize("hotkey_panel_table_action_toggle_full_screen"),
        singleKey(KeyEvent.VK_F11)
      ),
      "go_backward_in_history" to Hotkey(
        "go_backward_in_history",
        Localizer.localize("hotkey_panel_table_action_go_backward_in_history"),
        altKeyWith(KeyEvent.VK_LEFT)
      ),
      "go_forward_in_history" to Hotkey(
        "go_forward_in_history",
        Localizer.localize("hotkey_panel_table_action_go_forward_in_history"),
        altKeyWith(KeyEvent.VK_RIGHT)
      ),
      "toggle_history_pane" to Hotkey(
        "toggle_history_pane",
        Localizer.localize("hotkey_panel_table_action_toggle_history_pane"),
        controlKeyWith(KeyEvent.VK_F1)
      ),
      "toggle_translation_options_pane" to Hotkey(
        "toggle_translation_options_pane",
        Localizer.localize("hotkey_panel_table_action_toggle_translation_options_pane"),
        controlKeyWith(KeyEvent.VK_F2)
      ),
      "toggle_services_pane" to Hotkey(
        "toggle_services_pane",
        Localizer.localize("hotkey_panel_table_action_toggle_services_pane"),
        controlKeyWith(KeyEvent.VK_F3)
      ),
      "toggle_status_pane" to Hotkey(
        "toggle_status_pane",
        Localizer.localize("hotkey_panel_table_action_toggle_status_pane"),
        controlKeyWith(KeyEvent.VK_F4)
      ),
      "toggle_backward_translation_pane" to Hotkey(
        "toggle_backward_translation_pane",
        Localizer.localize("hotkey_panel_table_action_toggle_backward_translation_pane"),
        controlKeyWith(KeyEvent.VK_B)
      ),
      "open_settings_dialog" to Hotkey(
        "open_settings_dialog",
        Localizer.localize("hotkey_panel_table_action_open_settings_dialog"),
        controlKeyWith(KeyEvent.VK_COMMA)
      )
    )


  private val defaultHotkeys
    get() = mapOf(
      *defaultsGlobalHotkeys.toList().toTypedArray(),
      *defaultsWindowHotkeys.toList().toTypedArray()
    )

  var hotkeys = getHotkeysWithUserDefinedOnes()

  private fun getHotkeysWithUserDefinedOnes(): MutableMap<String, Hotkey> {
    return defaultHotkeys.toMutableMap().map {
      val userDefinedHotkey = hotkeysPrefs.get("hotkey_${it.key}", "")
      if (userDefinedHotkey.isNotEmpty()) {
        val parts = userDefinedHotkey.split(";")
        val modifiers = parts[0].toInt()
        val keyCode = parts[1].toInt()
        val newHotkey = it.value.copy(modifiers = modifiers, keyCode = keyCode)
        it.key to newHotkey
      } else {
        it.toPair()
      }
    }.toMap().toMutableMap()
  }

  fun getHotkey(id: String) = hotkeys[id]

  fun getHotkeyByDescription(hotkeyDescription: String): Hotkey {
    return hotkeys.values.find { it.description == hotkeyDescription }!!
  }

  fun updateHotkey(hotkeyId: String, keyStroke: KeyStroke): Hotkey? {
    if (isKeyStrokeInUse(keyStroke)) return null

    val hotkey = getHotkey(hotkeyId)!!.copy(modifiers = keyStroke.modifiers, keyCode = keyStroke.keyCode)
    hotkeys[hotkeyId] = hotkey
    hotkeysPrefs.put("hotkey_${hotkeyId}", "${keyStroke.modifiers};${keyStroke.keyCode}")

    return hotkey
  }

  fun reset() {
    hotkeysPrefs.keys().filter { it.startsWith("hotkey_") }.forEach { hotkeysPrefs.remove(it) }
    hotkeys = getHotkeysWithUserDefinedOnes()
  }

  fun isKeyStrokeInUse(keyStroke: KeyStroke): Boolean {
    return hotkeys.values.any { it.modifiers == keyStroke.modifiers && it.keyCode == keyStroke.keyCode }
  }

}

sealed class Theme(val id: String, val readableName: String) {

  sealed class BuiltIn(
    id: String,
    readableName: String,
    val lafClassName: String
  ) : Theme(id, readableName) {
    object DarkOneDark : BuiltIn("DarkOneDark", "Dark  - One Dark", FlatOneDarkIJTheme::class.java.name)
    object DarkPurple : BuiltIn("DarkPurple", "Dark  - Purple", FlatDarkPurpleIJTheme::class.java.name)
    object DarkHiberbee : BuiltIn("DarkHiberbee", "Dark  - Hiberbee", FlatHiberbeeDarkIJTheme::class.java.name)
    object DarkMaterialOceanic :
      BuiltIn("DarkMaterialOceanic", "Dark  - Oceanic Green", FlatMTMaterialOceanicIJTheme::class.java.name)

    object DarkVuesion : BuiltIn("DarkVuesion", "Dark  - Vuesion", FlatVuesionIJTheme::class.java.name)
    object DarkSolarized : BuiltIn("DarkSolarized", "Dark  - Solarized", FlatSolarizedDarkIJTheme::class.java.name)
    object DarkMac : BuiltIn("DarkMac", "Light - Mac", FlatMacDarkLaf::class.java.name)
    object LightMac : BuiltIn("LightMac", "Light - Mac", FlatMacLightLaf::class.java.name)
    object LightGray : BuiltIn("LightGray", "Light - Gray", FlatGrayIJTheme::class.java.name)
    object LightSolarized : BuiltIn("LightSolarized", "Light - Solarized", FlatSolarizedLightIJTheme::class.java.name)
  }

  sealed class Custom(
    id: String,
    readableName: String,
    val fileName: String
  ) : Theme(id, readableName) {
    object DarkSharper : Custom("DarkSharper", "Dark  - Default", "ReSharperDark.theme.json")
    object LightSharper : Custom("LightSharper", "Light - Default", "ReSharperLight.theme.json")
    object DarkXDark : Custom("DarkXDark", "Dark  - XDark", "XcodeDark.theme.json")
    object DarkModernBlack : Custom("DarkModernBlack", "Dark  - Modern Black", "vscode_dark_modern.theme.json")
    object DarkGithub : Custom("DarkGithub", "Dark  - GitHub", "github-dark-default.theme.json")
    object DarkGentle : Custom("DarkGentle", "Dark  - Godot", "godot_theme.theme.json")
    object DarkKintsugi : Custom("DarkKintsugi", "Dark - Kintsugi", "kintsugi.theme.json")
    object LightVitesse : Custom("LightVitesse", "Light - Vitesse", "vitesse.light.soft.theme.json")
    object LightEspresso : Custom("LightEspresso", "Light - Espresso", "espresso_light.theme.json")
    object LightSalmon : Custom("LightSalmon", "Light - Salmon", "salmon.theme.json")
  }

  companion object {
    val entries: List<Theme> = listOf(
      // Default Themes
      Custom.DarkSharper,   // Primary dark theme
      Custom.LightSharper,  // Primary light theme

      // Built-in Dark Themes
      BuiltIn.DarkOneDark,        // Deep dark style
      BuiltIn.DarkMaterialOceanic, // Oceanic dark tones
      BuiltIn.DarkPurple,         // Purple-accented dark
      BuiltIn.DarkHiberbee,       // Cozy dark theme
      BuiltIn.DarkVuesion,        // Modern dark look
      BuiltIn.DarkSolarized,      // Classic dark solarized
      BuiltIn.DarkMac,            // Mac-inspired dark

      // Custom Dark Themes
      Custom.DarkModernBlack, // Sleek black design
      Custom.DarkXDark,       // Extra dark variant
      Custom.DarkGithub,      // GitHub-inspired dark
      Custom.DarkGentle,      // Soft dark tones
      Custom.DarkKintsugi,

      // Built-in Light Themes
      BuiltIn.LightMac,       // Mac-inspired light
      BuiltIn.LightGray,      // Neutral gray style
      BuiltIn.LightSolarized, // Classic light solarized

      // Custom Light Themes
      Custom.LightVitesse,    // Bright and fast look
      Custom.LightEspresso,   // Warm coffee tones
      Custom.LightSalmon      // Soft salmon hue
    )


    fun getThemeByReadableName(readableName: String) =
      entries.find { it.readableName == readableName } ?: fallbackTheme(readableName)

    fun from(name: String) =
      entries.find { it::class.simpleName == name } ?: fallbackTheme(name)

    private fun fallbackTheme(name: String): Theme =
      if (name.contains("light", ignoreCase = true)) Custom.LightSharper else Custom.DarkSharper
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

  var currentInputLanguage: String by Delegates.observable(prefs.get("current_input_language", "auto"))
  { _, _, newValue -> prefs.put("current_input_language", newValue) }

  var currentOutputLanguage: String by Delegates.observable(prefs.get("current_output_language", "eng"))
  { _, _, newValue -> prefs.put("current_output_language", newValue) }

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

  var theme by Delegates.observable(Theme.from(prefs.get("theme", Theme.Custom.DarkSharper::class.simpleName)))
  { _, _, newValue -> prefs.put("theme", newValue::class.simpleName) }

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
