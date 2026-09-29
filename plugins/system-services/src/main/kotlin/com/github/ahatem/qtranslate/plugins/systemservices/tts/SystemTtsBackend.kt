package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.tts.Gender
import com.github.michaelbull.result.Result

internal data class SystemVoice(val id: String, val name: String, val locale: String, val gender: Gender? = null)

internal interface SystemTtsBackend {
    val displayName: String
    suspend fun discoverVoices(): Result<List<SystemVoice>, ServiceError>
    suspend fun synthesize(text: String, voiceId: String, speed: Float): Result<ByteArray, ServiceError>
}
