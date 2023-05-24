package com.pnix.qtranslate.presentation.listeners.window

import com.pnix.qtranslate.utils.altKeyWith
import com.pnix.qtranslate.utils.controlKeyWith
import com.pnix.qtranslate.utils.shiftKeyWith
import com.pnix.qtranslate.utils.singleKey
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
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
    controlKeyWith(KeyEvent.VK_ENTER)
  )

  object ListenToInput : WindowKeyListeners(
    ListenToInputAction(),
    controlKeyWith(KeyEvent.VK_L)
  )

  object ListenToTranslation : WindowKeyListeners(
    ListenToTranslationAction(),
    controlKeyWith(KeyEvent.VK_O)
  )

  object ListenToBackwardTranslation : WindowKeyListeners(
    ListenToBackwardTranslationAction(),
    null
  )

  object ClearCurrentTranslation : WindowKeyListeners(
    ClearAction(),
    controlKeyWith(KeyEvent.VK_N)
  )

  object SwapTranslationDirection : WindowKeyListeners(
    SwapTranslationDirectionAction(),
    controlKeyWith(KeyEvent.VK_I)
  )

  object OpenDictionaryDialog : WindowKeyListeners(
    OpenDictionaryDialogAction(),
    controlKeyWith(KeyEvent.VK_D)
  )

  object OpenHistoryDialog : WindowKeyListeners(
    OpenHistoryDialogAction(),
    controlKeyWith(KeyEvent.VK_H)
  )

  object ResetLanguagePairToAutoDetected : WindowKeyListeners(
    ResetLanguagePairToAutoDetectedAction(),
    shiftKeyWith(KeyEvent.VK_ESCAPE)
  )

  object HowToUse : WindowKeyListeners(
    HowToUseAction(),
    singleKey(KeyEvent.VK_F1)
  )

  object ToggleFullScreen : WindowKeyListeners(
    ToggleFullScreenAction(),
    singleKey(KeyEvent.VK_F11)
  )

  object GoBackwardInHistory : WindowKeyListeners(
    GoBackwardInHistoryAction(),
    altKeyWith(KeyEvent.VK_LEFT)
  )

  object GoForwardInHistory : WindowKeyListeners(
    GoForwardInHistoryAction(),
    altKeyWith(KeyEvent.VK_RIGHT)
  )

  object ToogleHistoryPane : WindowKeyListeners(
    ToggleHistoryPaneAction(),
    controlKeyWith(KeyEvent.VK_F1)
  )

  object ToggleTranslationOptionsPane : WindowKeyListeners(
    ToggleTranslationOptionsPaneAction(),
    controlKeyWith(KeyEvent.VK_F2)
  )

  object ToggleServicesPane : WindowKeyListeners(
    ToggleServicesPaneAction(),
    controlKeyWith(KeyEvent.VK_F3)
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
    controlKeyWith(KeyEvent.VK_B)
  )

  object OpenSettingsDialog : WindowKeyListeners(
    OpenSettingsDialogAction(),
    controlKeyWith(KeyEvent.VK_COMMA)
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
        ToogleHistoryPane,
        ToggleTranslationOptionsPane,
        ToggleServicesPane,
        OpenSettingsDialog
      )
    }
  }
}