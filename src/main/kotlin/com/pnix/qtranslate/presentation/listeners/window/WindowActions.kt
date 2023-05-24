package com.pnix.qtranslate.presentation.listeners.window

import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.settings_dialog.SettingsDialog
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
    GlobalScope.launch { QTranslateViewModel.listenToBackwardTranslationAction() }
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
    val translation = QTranslateViewModel.translation.value

    val sourceLanguage = QTranslateViewModel.inputLanguage.value
    val targetLanguage = QTranslateViewModel.outputLanguage.value

    QTranslateViewModel.setInputText(translation)
    QTranslateViewModel.setInputLanguage(targetLanguage)
    QTranslateViewModel.setOutputLanguage(sourceLanguage)

    GlobalScope.launch { QTranslateViewModel.translate() }
  }
}

class OpenDictionaryDialogAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    println("ShowDictionaryAction")
  }
}

class OpenHistoryDialogAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    println("ShowHistoryAction")
  }
}

class ResetLanguagePairToAutoDetectedAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    println("ResetLanguagePairToAutoDetectedAction")
  }
}

class HowToUseAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    println("ShowHelpAction")
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


class ToggleBackwardTranslationPaneAction : ActionListener {
  override fun actionPerformed(e: ActionEvent?) {
    Configurations.showBackwardTranslationPanel = !Configurations.showBackwardTranslationPanel
    QTranslateViewModel.triggerConfigurationChanged()
  }
}
