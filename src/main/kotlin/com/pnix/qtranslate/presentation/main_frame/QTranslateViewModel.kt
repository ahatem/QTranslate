package com.pnix.qtranslate.presentation.main_frame

import com.pnix.qtranslate.data.translators.bing.BingTranslator
import com.pnix.qtranslate.data.translators.google.GoogleTranslator
import com.pnix.qtranslate.data.translators.reverso.ReversoTranslator
import com.pnix.qtranslate.data.translators.yandex.YandexTranslator
import com.pnix.qtranslate.domain.models.Language
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object QTranslateViewModel {

  val translators = listOf(GoogleTranslator(), BingTranslator(), YandexTranslator(), ReversoTranslator())
  val supportedLanguages = Language.listAllLanguages().toTypedArray()

  private val _input = MutableStateFlow("")
  val input = _input.asStateFlow()

  private val _translation = MutableStateFlow("")
  val translation = _translation.asStateFlow()

  private val _inputLanguage = MutableStateFlow(Language("eng"))
  val inputLanguage = _inputLanguage.asStateFlow()

  private val _outputLanguage = MutableStateFlow(Language("ara"))
  val outputLanguage = _outputLanguage.asStateFlow()

  private val _selectedTranslatorIndex = MutableStateFlow(0)
  val selectedTranslatorIndex = _selectedTranslatorIndex.asStateFlow()

  private val _isTranslating = MutableStateFlow(false)
  val isTranslating = _isTranslating.asStateFlow()

  fun translate() {
    val inputText = _input.value
    if (inputText.isBlank()) return
    _isTranslating.value = true
    val inputLang = _inputLanguage.value.alpha3
    val outputLang = _outputLanguage.value.alpha3
    val translator = translators[_selectedTranslatorIndex.value]
    GlobalScope.launch {
      val translatedText = translator.translate(inputText, inputLang, outputLang)
      _translation.value = translatedText.translatedText
    }
    _isTranslating.value = false
  }


  fun setInputText(text: String) {
    _input.value = text
  }

  fun setOutputLanguage(language: Language) {
    _outputLanguage.value = language
  }

  fun setInputLanguage(language: Language) {
    _inputLanguage.value = language
  }

  fun setSelectedTranslatorIndex(index: Int) {
    _selectedTranslatorIndex.value = index
  }

}

/*sealed class QTranslateIntent {
  data class UpdateInputText(val text: String) : QTranslateIntent()
  data class UpdateOutputText(val text: String) : QTranslateIntent()
  data class UpdateSourceLanguage(val language: Language) : QTranslateIntent()
  data class UpdateTargetLanguage(val language: Language) : QTranslateIntent()
  data class UpdateSelectedTranslator(val index: Int) : QTranslateIntent()
  object Translate : QTranslateIntent()
  object ListenToTranslation : QTranslateIntent()
  object ListenToInput : QTranslateIntent()
  data class CheckSpelling(val text: String) : QTranslateIntent()
  object ApplicationClosing : QTranslateIntent()
}

sealed class QTranslateState {
  data class Idle(val uiState: UiState) : QTranslateState()
  data class IsTranslating(val uiState: UiState) : QTranslateState()
  data class TranslationError(val uiState: UiState, val error: Throwable) : QTranslateState()
  data class TranslationSuccess(val uiState: UiState, val translation: TranslationResult) : QTranslateState()
  data class TextToSpeech(val uiState: UiState, val audioData: ByteArray) : QTranslateState()
}

object QTranslateViewModel {
  private val viewModelScope = CoroutineScope(Dispatchers.Default)

  private val intentChannel = Channel<QTranslateIntent>(Channel.UNLIMITED)
  private val stateFlow = MutableStateFlow<QTranslateState>(QTranslateState.Idle(UiState()))

  init {
    viewModelScope.launch {
      var uiState = UiState()

      for (intent in intentChannel) {
        when (intent) {
          is QTranslateIntent.UpdateInputText -> {
            uiState = uiState.copy(inputText = intent.text)
            emitState(uiState)
          }
          is QTranslateIntent.UpdateOutputText -> {
            uiState = uiState.copy(outputText = intent.text)
            emitState(uiState)
          }
          is QTranslateIntent.UpdateSourceLanguage -> {
            uiState = uiState.copy(sourceLanguage = intent.language)
            emitState(uiState)
          }
          is QTranslateIntent.UpdateTargetLanguage -> {
            uiState = uiState.copy(targetLanguage = intent.language)
            emitState(uiState)
          }
          is QTranslateIntent.UpdateSelectedTranslator -> {
            uiState = uiState.copy(selectedTranslatorIndex = intent.index)
            emitState(uiState)
          }
          QTranslateIntent.Translate -> {
            if (uiState.inputText.isEmpty()) {
              emitState(uiState.copy(outputText = ""))
              continue
            }

            emitState(QTranslateState.IsTranslating(uiState))

            val translator = uiState.translators[uiState.selectedTranslatorIndex]
            val sourceText = uiState.inputText
            val sourceLanguage = uiState.sourceLanguage
            val targetLanguage = uiState.targetLanguage

            runCatching {
              val translation = translator.translate(sourceText, targetLanguage.id, sourceLanguage.id)
              emitState(QTranslateState.TranslationSuccess(uiState.copy(
                outputText = translation.translatedText,
                sourceLanguage = translation.sourceLanguage,
                isTranslating = false
              ), translation))
            }.onFailure {
              emitState(QTranslateState.TranslationError(uiState.copy(
                outputText = "No Data Returned",
                isTranslating = false
              ), it))
              it.printStackTrace()
            }
          }
          QTranslateIntent.ListenToTranslation -> {
            val translator = uiState.translators[uiState.selectedTranslatorIndex]
            val targetLanguage = uiState.targetLanguage

            val audioData = ByteArrayOutputStream().use { outputStream ->
              outputStream.writeBytes(translator.textToSpeech(uiState.outputText, targetLanguage.alpha3).content)
              outputStream.toByteArray()
            }

            emitState(QTranslateState.TextToSpeech(uiState, audioData))
          }
          QTranslateIntent.ListenToInput -> {
            val translator = uiState.translators[uiState.selectedTranslatorIndex]
            val sourceLanguage = uiState.sourceLanguage

            val audioData = ByteArrayOutputStream().use { outputStream ->
              outputStream.writeBytes(translator.textToSpeech(uiState.outputText, sourceLanguage.alpha3).content)
              outputStream.toByteArray()
            }

            emitState(QTranslateState.TextToSpeech(uiState, audioData))
          }
          is QTranslateIntent.CheckSpelling -> {
            *//*val translator = uiState.translators[uiState.selectedTranslatorIndex]
            val sourceLanguage = _uiState.value.sourceLanguage
            return translator.spellCheck(text, sourceLanguage.alpha3)*//*
            emitState(QTranslateState.Idle(uiState))
          }
          QTranslateIntent.ApplicationClosing -> {
            viewModelScope.cancel()
          }
        }
      }
    }
  }

  fun processIntent(intent: QTranslateIntent) = viewModelScope.launch {
    intentChannel.send(intent)
  }

  fun getStateFlow(): StateFlow<QTranslateState> {
    return stateFlow
  }

  private suspend fun emitState(uiState: UiState) {
    stateFlow.emit(QTranslateState.Idle(uiState))
  }

  private suspend fun emitState(state: QTranslateState) {
    stateFlow.emit(state)
  }

  fun onApplicationClosing() {
    viewModelScope.cancel()
  }
}

data class UiState(
  val inputText: String = "",
  val outputText: String = "",
  val sourceLanguage: Language = Language("en"),
  val targetLanguage: Language = Language("ar"),
  val translators: List<TranslatorService> = listOf(
    GoogleTranslator(),
    BingTranslator(),
    YandexTranslator(),
    ReversoTranslator(),
  ),
  val selectedTranslatorIndex: Int = 0,
  val isTranslating: Boolean = false
)*/


