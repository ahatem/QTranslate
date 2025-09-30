package com.github.ahatem.qtranslate.presentation.listeners.window

import com.github.ahatem.qtranslate.models.Hotkeys
import java.awt.event.ActionListener
import javax.swing.KeyStroke

/*
    Ctrl+Alt+1..9 => Show dictionary with selected 1..9th translation service
    Ctrl+Tab/Ctrl+Shift+Tab/Ctrl+1..9 => Switch to the next/previous/1..9th translation service
    Ctrl+Shift+1..9 => Select language pair
    Ctrl+Space => Show suggestion/autocomplete menu
*/


sealed class WindowKeyListeners(val action: ActionListener, val hotkey: KeyStroke?) {

  object Translate : WindowKeyListeners(
    TranslateAction(),
    Hotkeys.getHotkey("translate")!!.toKeyStroke()
  )

  object ListenToInput : WindowKeyListeners(
    ListenToInputAction(),
    Hotkeys.getHotkey("listen_to_input")!!.toKeyStroke()
  )

  object ListenToTranslation : WindowKeyListeners(
    ListenToTranslationAction(),
    Hotkeys.getHotkey("listen_to_translation")!!.toKeyStroke()

  )

  object ListenToBackwardTranslation : WindowKeyListeners(
    ListenToBackwardTranslationAction(),
    null
  )

  object ClearCurrentTranslation : WindowKeyListeners(
    ClearAction(),
    Hotkeys.getHotkey("clear_current_translation")!!.toKeyStroke()
  )

  object SwapTranslationDirection : WindowKeyListeners(
    SwapTranslationDirectionAction(),
    Hotkeys.getHotkey("swap_translation_direction")!!.toKeyStroke()
  )

  object OpenDictionaryDialog : WindowKeyListeners(
    OpenDictionaryDialogAction(),
    Hotkeys.getHotkey("open_dictionary_dialog")!!.toKeyStroke()
  )

  object OpenHistoryDialog : WindowKeyListeners(
    OpenHistoryDialogAction(),
    Hotkeys.getHotkey("open_history_dialog")!!.toKeyStroke()
  )

  object ResetLanguagePairToAutoDetected : WindowKeyListeners(
    ResetLanguagePairToAutoDetectedAction(),
    Hotkeys.getHotkey("reset_language_pair_to_auto_detected")!!.toKeyStroke()
  )

  object HowToUse : WindowKeyListeners(
    HowToUseAction(),
    Hotkeys.getHotkey("how_to_use")!!.toKeyStroke()
  )

  object ToggleFullScreen : WindowKeyListeners(
    ToggleFullScreenAction(),
    Hotkeys.getHotkey("toggle_full_screen")!!.toKeyStroke()
  )

  object GoBackwardInHistory : WindowKeyListeners(
    GoBackwardInHistoryAction(),
    Hotkeys.getHotkey("go_backward_in_history")!!.toKeyStroke()
  )

  object GoForwardInHistory : WindowKeyListeners(
    GoForwardInHistoryAction(),
    Hotkeys.getHotkey("go_forward_in_history")!!.toKeyStroke()
  )

  object ToggleHistoryPane : WindowKeyListeners(
    ToggleHistoryPaneAction(),
    Hotkeys.getHotkey("toggle_history_pane")!!.toKeyStroke()
  )

  object ToggleTranslationOptionsPane : WindowKeyListeners(
    ToggleTranslationOptionsPaneAction(),
    Hotkeys.getHotkey("toggle_translation_options_pane")!!.toKeyStroke()
  )

  object ToggleServicesPane : WindowKeyListeners(
    ToggleServicesPaneAction(),
    Hotkeys.getHotkey("toggle_services_pane")!!.toKeyStroke()
  )

  object ToggleStatusPane : WindowKeyListeners(
    ToggleStatusPaneAction(),
    Hotkeys.getHotkey("toggle_status_pane")!!.toKeyStroke()
  )

  object ToggleSpellChecking : WindowKeyListeners(
    ToggleSpellCheckingAction(),
    null
  )

  object ToggleInstantTranslation : WindowKeyListeners(
    ToggleInstantTranslationAction(),
    null
  )

  object ToggleBackwardTranslationPane : WindowKeyListeners(
    ToggleBackwardTranslationPaneAction(),
    Hotkeys.getHotkey("toggle_backward_translation_pane")!!.toKeyStroke()
  )

  object OpenSettingsDialog : WindowKeyListeners(
    OpenSettingsDialogAction(),
    Hotkeys.getHotkey("open_settings_dialog")!!.toKeyStroke()
  )

  data class ChangeLayoutPreset(val presetId: String) : WindowKeyListeners(
    ChangeLayoutPresetAction(presetId),
    null
  )

  object ToggleAutoCheckForUpdates : WindowKeyListeners(
    ToggleAutoCheckForUpdatesAction(),
    null
  )

  object CheckForUpdate : WindowKeyListeners(
    CheckForUpdateAction(),
    null
  )

  object ContactUs : WindowKeyListeners(
    ContactUsAction(),
    null
  )

  object OpenAboutQTranslateDialog : WindowKeyListeners(
    OpenAboutQTranslateDialog(),
    null
  )


  object ToggleGlobalHotkeys : WindowKeyListeners(
    ToggleGlobalHotkeysAction(),
    null
  )

  companion object {
    fun getAllValues(): List<WindowKeyListeners> {
      return listOf(
        Translate,
        ListenToInput,
        ListenToTranslation,
        ClearCurrentTranslation,
        SwapTranslationDirection,
        OpenDictionaryDialog,
        OpenHistoryDialog,
        ResetLanguagePairToAutoDetected,
        HowToUse,
        ToggleFullScreen,
        GoBackwardInHistory,
        GoForwardInHistory,
        ToggleHistoryPane,
        ToggleTranslationOptionsPane,
        ToggleServicesPane,
        ToggleHistoryPane,
        ToggleSpellChecking,
        ToggleInstantTranslation,
        ToggleBackwardTranslationPane,
        OpenSettingsDialog,
        ContactUs,
        OpenAboutQTranslateDialog,
        ToggleGlobalHotkeys,
        ToggleAutoCheckForUpdates,
        CheckForUpdate,
      ).filter { it.hotkey != null }
    }
  }
}