package com.pnix.qtranslate.presentation.main_frame

import com.pnix.qtranslate.data.translators.bing.BingTranslator
import com.pnix.qtranslate.data.translators.google.GoogleTranslator
import com.pnix.qtranslate.data.translators.reverso.ReversoTranslator
import com.pnix.qtranslate.data.translators.yandex.YandexTranslator
import com.pnix.qtranslate.domain.models.Language
import javazoom.jl.player.advanced.AdvancedPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

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

  private val _isTranslating = MutableStateFlow(false)
  val isTranslating = _isTranslating.asStateFlow()

  private val _isListening = MutableStateFlow(false)
  val isListening = _isListening.asStateFlow()


  private var player: AdvancedPlayer? = null
  private var job: Job? = null

  suspend fun translate(text: String? = null) {
    val inputText = text ?: _input.value
    if (inputText.isBlank()) return
    _isTranslating.value = true
    val inputLang = _inputLanguage.value.alpha3
    val outputLang = _outputLanguage.value.alpha3
    val translator = translators[_selectedTranslatorIndex.value]
    val translatedText = translator.translate(inputText, outputLang, inputLang)
    _translation.value = translatedText.translatedText
    _isTranslating.value = false
  }

  suspend fun listenToInput(text: String? = null) {
    val inputText = text ?: _input.value
    if (inputText.isBlank()) return

    val inputLang = _inputLanguage.value.alpha3
    val translator = translators[_selectedTranslatorIndex.value]
    val textToSpeech = translator.textToSpeech(inputText, inputLang)

    playAudio(textToSpeech.content)
  }

  suspend fun listenToTranslation() {
    val outputText = _translation.value
    if (outputText.isBlank()) return

    val outputLang = _outputLanguage.value.alpha3
    val translator = translators[_selectedTranslatorIndex.value]
    val textToSpeech = translator.textToSpeech(outputText, outputLang)

    playAudio(textToSpeech.content)
  }

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

}

