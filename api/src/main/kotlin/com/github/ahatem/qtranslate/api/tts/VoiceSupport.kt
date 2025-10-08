package com.github.ahatem.qtranslate.api.tts

import com.github.ahatem.qtranslate.api.language.LanguageCode

/**
 * Optional interface for TTS services that support voice selection.
 */
interface VoiceSupport {
    val voices: List<Voice>
}

data class Voice(
    val id: String,
    val name: String,
    val language: LanguageCode,
    val gender: Gender? = null
)

enum class Gender {
    MALE, FEMALE, NEUTRAL
}