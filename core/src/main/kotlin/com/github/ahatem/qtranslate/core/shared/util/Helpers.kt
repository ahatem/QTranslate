package com.github.ahatem.qtranslate.core.shared.util

import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType

/** Maps a Service to its ServiceType based on implemented interfaces. */
fun mapServiceToType(service: Service): ServiceType? {
    return when (service) {
        is Translator -> ServiceType.TRANSLATOR
        is TextToSpeech -> ServiceType.TTS
        is OCR -> ServiceType.OCR
        is SpellChecker -> ServiceType.SPELL_CHECKER
        is Dictionary -> ServiceType.DICTIONARY
        else -> null
    }
}