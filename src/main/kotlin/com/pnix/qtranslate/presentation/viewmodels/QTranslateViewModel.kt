package com.pnix.qtranslate.presentation.viewmodels

import com.pnix.qtranslate.common.Localizer
import com.pnix.qtranslate.models.*
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import com.pnix.qtranslate.services.text_extractor.ApiNinjasTextExtractor
import com.pnix.qtranslate.services.text_extractor.GoogleTextExtractor
import com.pnix.qtranslate.services.text_extractor.OcrSpaceTextExtractor
import com.pnix.qtranslate.services.translators.abstraction.TextToSpeechNotSupportedException
import com.pnix.qtranslate.services.translators.abstraction.UnsupportedLanguageException
import com.pnix.qtranslate.utils.supportedTranslators
import javazoom.jl.player.advanced.AdvancedPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

class UnknownErrorOccurredException(cause: Throwable) : Exception(
  Localizer.localize("status_panel_error_text_unknown_error_occurred"), cause
)

object QTranslateViewModel {

  lateinit var mainFrame: QTranslateFrame
    private set

  private val _configurationChanged = MutableStateFlow(false)
  val configurationChanged = _configurationChanged.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading = _isLoading.asStateFlow()

  val translators get() = supportedTranslators.filter { !Configurations.excludedTranslators.contains(it.serviceName) }

  val supportedLanguages = Language.listAllLanguages().toTypedArray()
  val currentTranslator get() = translators[_selectedTranslatorIndex.value]

  private val imageTextExtractors = listOf(GoogleTextExtractor(), OcrSpaceTextExtractor(), ApiNinjasTextExtractor())

  private val _input = MutableStateFlow("")
  val input = _input.asStateFlow()

  private val _translation = MutableStateFlow("")
  val translation = _translation.asStateFlow()

  private val _backwardTranslation = MutableStateFlow("")
  val backwardTranslation = _backwardTranslation.asStateFlow()

  private val _inputLanguage = MutableStateFlow(Language("eng"))
  val inputLanguage = _inputLanguage.asStateFlow()

  private val _outputLanguage = MutableStateFlow(Language("ara"))
  val outputLanguage = _outputLanguage.asStateFlow()

  private val _selectedTranslatorIndex = MutableStateFlow(0)
  val selectedTranslatorIndex = _selectedTranslatorIndex.asStateFlow()

  private val _isTranslating = MutableStateFlow(false)
  val isTranslating = _isTranslating.asStateFlow()

  private val _isListening = MutableStateFlow(false)
  val isListening = _isListening.asStateFlow()

  private val _spells = MutableStateFlow(SpellCheckResult("", mutableListOf()))
  val spells = _spells.asStateFlow()

  private val _error = MutableStateFlow(Exception(""))
  val error = _error.asStateFlow()

  private var player: AdvancedPlayer? = null
  private var job: Job? = null

  suspend fun translate(text: String? = null) {
    val inputText = text ?: _input.value
    if (inputText.isBlank()) return
    _isTranslating.value = true
    val inputLang = _inputLanguage.value.alpha3
    val outputLang = _outputLanguage.value.alpha3
    val translator = currentTranslator

    runCatching {
      val translationResult = translator.translate(inputText, outputLang, inputLang)
      _translation.value = "${translationResult.translatedText}\n\n${translationResult.additionalInfo}".trim()
      if (Configurations.showBackwardTranslationPanel) {
        val backwardTranslationResult = translator.translate(translationResult.translatedText, inputLang, outputLang)
        _backwardTranslation.value =
          "${backwardTranslationResult.translatedText}\n\n${backwardTranslationResult.additionalInfo}".trim()
      }

      if (Configurations.enableHistory) {
        TranslationHistory.saveSnapshot(
          TranslationHistorySnapshot(
            _selectedTranslatorIndex.value,
            inputLang,
            outputLang,
            inputText,
            _translation.value,
            _backwardTranslation.value
          )
        )
      }

    }.onFailure {
      _error.value = when (it) {
        is UnsupportedLanguageException -> it
        else -> UnknownErrorOccurredException(it)
      }
    }

    _isTranslating.value = false
  }

  suspend fun spellCheck() {
    val inputText = _input.value
    if (inputText.isBlank()) return
    val inputLang = _inputLanguage.value.alpha3
    val spellCheck = currentTranslator.spellCheck(inputText, inputLang)
    _spells.value = spellCheck
  }

  suspend fun listenToInput(text: String? = null) {
    val inputText = text ?: _input.value
    if (inputText.isBlank()) return

    val inputLang = _inputLanguage.value.alpha3
    runCatching {
      val textToSpeech = currentTranslator.textToSpeech(inputText, inputLang)
      playAudio(textToSpeech.content)
    }.onFailure {
      _error.value = when (it) {
        is UnsupportedLanguageException -> it
        is TextToSpeechNotSupportedException -> it
        else -> UnknownErrorOccurredException(it)
      }
    }
  }

  suspend fun listenToTranslation(text: String? = null) {
    val outputText = text ?: _translation.value
    if (outputText.isBlank()) return

    val outputLang = _outputLanguage.value.alpha3
    runCatching {
      val textToSpeech = currentTranslator.textToSpeech(outputText, outputLang)
      playAudio(textToSpeech.content)
    }.onFailure {
      _error.value = when (it) {
        is UnsupportedLanguageException -> it
        is TextToSpeechNotSupportedException -> it
        else -> UnknownErrorOccurredException(it)
      }
    }
  }

  suspend fun listenToBackwardTranslation(text: String? = null) {
    val backwardTranslationText = text ?: _backwardTranslation.value
    if (backwardTranslationText.isBlank()) return

    val outputLang = _inputLanguage.value.alpha3
    runCatching {
      val textToSpeech = currentTranslator.textToSpeech(backwardTranslationText, outputLang)
      playAudio(textToSpeech.content)
    }.onFailure {
      _error.value = when (it) {
        is UnsupportedLanguageException -> it
        is TextToSpeechNotSupportedException -> it
        else -> UnknownErrorOccurredException(it)
      }
    }
  }

  fun extractText(image: BufferedImage): String {
    for (imageTextExtractor in imageTextExtractors) {
      runCatching {
        val text = imageTextExtractor.extractText(image)
        if (text.isNotEmpty()) return text
      }.onFailure {
        _error.value = Exception(it)

      }
    }
    _error.value = Exception("failed to recognize text in the image.")
    return "No Data returned"
  }

  fun triggerConfigurationChanged() {
    _configurationChanged.value = !configurationChanged.value
  }

  fun undoTranslation() {
    updateState(TranslationHistory.undo())
  }

  fun redoTranslation() {
    updateState(TranslationHistory.redo())
  }

  fun updateState(translationHistorySnapshot: TranslationHistorySnapshot?) {
    translationHistorySnapshot?.let {
      _selectedTranslatorIndex.value = it.selectedTranslatorIndex
      _inputLanguage.value = Language(it.inputLanguage)
      _outputLanguage.value = Language(it.outputLanguage)

      _input.value = it.originalText.trimIndent()
      _translation.value = it.translatedText.trimIndent()
      _backwardTranslation.value = it.backwardTranslationText.trimIndent()
    }
  }


  private fun playAudio(content: ByteArray) {
    stopPlayback()
    startPlayback(content)
  }

  private fun startPlayback(content: ByteArray) {
    _isListening.value = true
    job = CoroutineScope(Dispatchers.IO).launch {
      ByteArrayInputStream(content).use { stream ->
        player = AdvancedPlayer(stream)
        player?.play()
        stopPlayback()
        finalizePlayback()
      }
    }
  }

  private fun stopPlayback() {
    job?.cancel()
    player?.close()
    _isListening.value = false
  }

  private fun finalizePlayback() {
    player = null
    job = null
  }

  /* Setters */
  fun setInputText(text: String) {
    _input.value = text.trimIndent()
  }

  fun setTranslationText(text: String) {
    _translation.value = text.trimIndent()
  }

  fun setInputLanguage(language: Language) {
    _inputLanguage.value = language
  }

  fun setOutputLanguage(language: Language) {
    _outputLanguage.value = language
  }

  fun setSelectedTranslatorIndex(index: Int) {
    _selectedTranslatorIndex.value = index
  }

  fun setLoading(loading: Boolean) {
    _isLoading.value = loading
  }

  fun setError(exception: Exception) {
    this._error.value = exception
  }

  fun setMainFrame(frame: QTranslateFrame) {
    this.mainFrame = frame
  }
}

