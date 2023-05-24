package com.pnix.qtranslate.presentation.viewmodels

import com.pnix.qtranslate.models.*
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import com.pnix.qtranslate.text_extractor.ApiNinjasTextExtractor
import com.pnix.qtranslate.text_extractor.GoogleTextExtractor
import com.pnix.qtranslate.text_extractor.OcrSpaceTextExtractor
import com.pnix.qtranslate.translators.abstraction.UnsupportedLanguageException
import com.pnix.qtranslate.translators.bing.BingTranslator
import com.pnix.qtranslate.translators.google.GoogleTranslator
import com.pnix.qtranslate.translators.reverso.ReversoTranslator
import com.pnix.qtranslate.translators.yandex.YandexTranslator
import javazoom.jl.player.advanced.AdvancedPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

object QTranslateViewModel {

  lateinit var mainFrame: QTranslateFrame
    private set

  private val _configurationChanged = MutableStateFlow(false)
  val configurationChanged = _configurationChanged.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading = _isLoading.asStateFlow()

  val translators = listOf(GoogleTranslator(), BingTranslator(), YandexTranslator(), ReversoTranslator())
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

  private var player: AdvancedPlayer? = null
  private var job: Job? = null

  suspend fun translate(text: String? = null) {
    val inputText = text ?: _input.value
    if (inputText.isBlank()) return
    _isTranslating.value = true
    val inputLang = _inputLanguage.value.alpha3
    val outputLang = _outputLanguage.value.alpha3
    val translator = translators[_selectedTranslatorIndex.value]

    runCatching {
      val translatedText = translator.translate(inputText, outputLang, inputLang)
      _translation.value = translatedText.translatedText
      if (Configurations.showBackwardTranslationPanel) {
        val backwardTranslatedText = translator.translate(translatedText.translatedText, inputLang, outputLang)
        _backwardTranslation.value = backwardTranslatedText.translatedText
      }

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

    }.onFailure {
      _translation.value = when (it) {
        is UnsupportedLanguageException -> it.message!!.replace(outputLang, "'${_outputLanguage.value.name}'")
        else -> "Unknown error occurred"
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
      _translation.value = it.message ?: "Unknown error occurred"
    }
  }

  suspend fun listenToTranslation() {
    val outputText = _translation.value
    if (outputText.isBlank()) return

    val outputLang = _outputLanguage.value.alpha3
    runCatching {
      val textToSpeech = currentTranslator.textToSpeech(outputText, outputLang)
      playAudio(textToSpeech.content)
    }.onFailure {
      _translation.value = it.message ?: "Unknown error occurred"
    }
  }

  suspend fun listenToBackwardTranslationAction() {
    val backwardTranslationText = _backwardTranslation.value
    if (backwardTranslationText.isBlank()) return

    val outputLang = _inputLanguage.value.alpha3
    runCatching {
      val textToSpeech = currentTranslator.textToSpeech(backwardTranslationText, outputLang)
      playAudio(textToSpeech.content)
    }.onFailure {
      _translation.value = it.message ?: "Unknown error occurred"
    }
  }

  fun extractText(image: BufferedImage): String {
    for (imageTextExtractor in imageTextExtractors) {
      runCatching {
        val text = imageTextExtractor.extractText(image)
        if (text.isNotEmpty()) return text
      }.onFailure { println(it.message) }
    }
    throw Exception("All methods failed to recognize text in the image.")
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

  private fun updateState(translationHistorySnapshot: TranslationHistorySnapshot?) {
    translationHistorySnapshot?.let {
      _selectedTranslatorIndex.value = it.selectedTranslatorIndex
      _inputLanguage.value = Language(it.inputLanguage)
      _outputLanguage.value = Language(it.outputLanguage)

      _input.value = it.originalText
      _translation.value = it.translatedText
      _backwardTranslation.value = it.backwardTranslationText
    }
  }

  private fun playAudio(content: ByteArray) {
    _isListening.value = true
    job?.cancel()
    player?.close()
    job = CoroutineScope(Dispatchers.IO).launch {
      ByteArrayInputStream(content).use { stream ->
        player = AdvancedPlayer(stream)
        player?.play()
        player?.close()
        _isListening.value = false
        player = null
        job = null
      }
    }
  }


  /* Setters */
  fun setInputText(text: String) {
    _input.value = text
  }

  fun setTranslationText(text: String) {
    _translation.value = text
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

  fun setMainFrame(frame: QTranslateFrame) {
    this.mainFrame = frame
  }


}

