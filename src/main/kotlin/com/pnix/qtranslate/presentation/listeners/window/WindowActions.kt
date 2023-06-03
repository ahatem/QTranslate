package com.pnix.qtranslate.presentation.listeners.window

import com.pnix.qtranslate.common.Updater
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.about_dialog.AboutQTranslateDialog
import com.pnix.qtranslate.presentation.history_dialog.HistoryDialog
import com.pnix.qtranslate.presentation.listeners.global.QTranslateHotkeyListener
import com.pnix.qtranslate.presentation.settings_dialog.SettingsDialog
import com.pnix.qtranslate.presentation.update_dialog.NewUpdateDialog
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.awt.event.ActionEvent
import java.awt.event.ActionListener

class TranslateAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    GlobalScope.launch { QTranslateViewModel.translate() }
  }
}

class ListenToInputAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    GlobalScope.launch { QTranslateViewModel.listenToInput() }
  }
}

class ListenToTranslationAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    GlobalScope.launch { QTranslateViewModel.listenToTranslation() }
  }
}

class ListenToBackwardTranslationAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    GlobalScope.launch { QTranslateViewModel.listenToBackwardTranslation() }
  }
}

class ClearAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    QTranslateViewModel.setTranslationText("")
    QTranslateViewModel.setInputText("")
  }
}

class SwapTranslationDirectionAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    val sourceLanguage = QTranslateViewModel.inputLanguage.value
    val targetLanguage = QTranslateViewModel.outputLanguage.value

    QTranslateViewModel.setInputLanguage(targetLanguage)
    QTranslateViewModel.setOutputLanguage(sourceLanguage)
  }
}

class OpenDictionaryDialogAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    println("ShowDictionaryAction")
  }
}

class OpenHistoryDialogAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    HistoryDialog(QTranslateViewModel.mainFrame)
  }
}

class ResetLanguagePairToAutoDetectedAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    println("ResetLanguagePairToAutoDetectedAction")
  }
}

class HowToUseAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    QTranslateViewModel.setInputText(
      """
      QTranslate Version  1.0.0

      Global hotkeys (default values):
        Double Ctrl => Show main window (with translation of selected text, if any)
        Ctrl+Q => Translate selected text and show it in popup window
        Ctrl+E => Listen to selected text

      Main window hotkeys:
        Ctrl+Enter => Translate text
        Ctrl+N => Clear current translation
        Ctrl+D => Show dictionary
        Ctrl+H => Show translation history
        Ctrl+K => Show virtual keyboard
        Shift+Esc => Reset language pair to auto-detected
        Ctrl+I => Swap translation direction
        F1 => How to use?
        F11 => Turn on/off full-screen mode
        Alt+Left Arrow => Go to the previous translation
        Alt+Right Arrow => Go to the next translation
    """.trimIndent()
    )
  }
}

class ToggleFullScreenAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    println("ToggleFullScreenAction")
  }
}

class GoBackwardInHistoryAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    QTranslateViewModel.undoTranslation()
  }
}

class GoForwardInHistoryAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    QTranslateViewModel.redoTranslation()
  }
}

class ToggleHistoryPaneAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.showHistoryPanel = !Configurations.showHistoryPanel
    QTranslateViewModel.triggerConfigurationChanged()
  }
}

class ToggleTranslationOptionsPaneAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.showTranslationOptionsPanel = !Configurations.showTranslationOptionsPanel
    QTranslateViewModel.triggerConfigurationChanged()
  }
}

class ToggleServicesPaneAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.showServicesPanel = !Configurations.showServicesPanel
    QTranslateViewModel.triggerConfigurationChanged()
  }
}

class ToggleStatusPaneAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.showStatusPanel = !Configurations.showStatusPanel
    QTranslateViewModel.triggerConfigurationChanged()
  }
}

class OpenSettingsDialogAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    SettingsDialog(QTranslateViewModel.mainFrame)
  }
}

class ToggleSpellCheckingAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.spellChecking = !Configurations.spellChecking
  }
}

class ToggleInstantTranslationAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.instantTranslation = !Configurations.instantTranslation
  }
}

class ChangeLayoutPresetAction(private val presetId: String) : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.layoutPreset = presetId
    QTranslateViewModel.triggerConfigurationChanged()
  }
}

class OpenAboutQTranslateDialog : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    AboutQTranslateDialog(QTranslateViewModel.mainFrame)
  }
}

class ContactUsAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.lastOptionOpened = "contact_us"
    SettingsDialog(QTranslateViewModel.mainFrame)
  }
}

class ToggleGlobalHotkeysAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.enableGlobalHotkeys = !Configurations.enableGlobalHotkeys
    if (Configurations.enableGlobalHotkeys) QTranslateHotkeyListener.registerGlobalListener()
    else QTranslateHotkeyListener.unRegisterGlobalListener()
  }
}

class ToggleBackwardTranslationPaneAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.showBackwardTranslationPanel = !Configurations.showBackwardTranslationPanel
    QTranslateViewModel.triggerConfigurationChanged()
    if (Configurations.showBackwardTranslationPanel) WindowKeyListeners.Translate.action.actionPerformed(e)
  }
}

class ToggleAutoCheckForUpdatesAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.autoCheckForUpdates = !Configurations.autoCheckForUpdates
  }
}

class CheckForUpdateAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    val latestVersionInfo = Updater.getLatestVersionInfo()
    if(latestVersionInfo != null && latestVersionInfo.isNewerVersion()) {
      Configurations.latestVersionNumber = latestVersionInfo.versionCode
      if (Configurations.latestVersionNumber != Configurations.skippedVersionNumber) {
        NewUpdateDialog(QTranslateViewModel.mainFrame, latestVersionInfo)
      }
    }
  }
}
