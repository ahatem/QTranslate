package com.github.ahatem.qtranslate.core.conversation

/**
 * Heuristics for selecting audio recording devices and warning when a suspicious
 * mix device or general output is chosen for a Discord call.
 */
object DeviceCheckHeuristics {
    private val SUSPICIOUS_MIC_KEYWORDS = listOf("stream", "mix", "monitor", "chat", "loopback")

    fun isSuspiciousMicrophone(deviceName: String?): Boolean {
        if (deviceName.isNullOrBlank()) return false
        val lower = deviceName.lowercase()
        return SUSPICIOUS_MIC_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    fun findSuggestedChatOutput(selectedOutputName: String?, availableOutputs: List<String>): String? {
        if (selectedOutputName.isNullOrBlank() || availableOutputs.isEmpty()) return null
        val selectedLower = selectedOutputName.lowercase()
        if (selectedLower.contains("chat")) return null
        return availableOutputs.firstOrNull { it.lowercase().contains("chat") }
    }

    fun pickDefaultMicrophone(devices: List<AudioDevice>): AudioDevice? {
        if (devices.isEmpty()) return null
        // 1. Devices containing "mic" or "microphone" that are NOT suspicious
        val realMics = devices.filter { dev ->
            val name = dev.name.lowercase()
            (name.contains("mic") || name.contains("microphone")) && !isSuspiciousMicrophone(dev.name)
        }
        if (realMics.isNotEmpty()) return realMics.first()

        // 2. Any device that is NOT suspicious and does not look like system audio
        val nonSuspicious = devices.filter { !isSuspiciousMicrophone(it.name) && !it.looksLikeSystemAudio }
        if (nonSuspicious.isNotEmpty()) return nonSuspicious.first()

        return devices.first()
    }

    fun pickDefaultCallOutput(outputs: List<AudioDevice>): AudioDevice? {
        if (outputs.isEmpty()) return null
        // Prefer "chat" outputs (e.g. SteelSeries Sonar - Chat)
        val chatOutput = outputs.firstOrNull { it.name.contains("chat", ignoreCase = true) }
        if (chatOutput != null) return chatOutput

        return outputs.first()
    }
}

object AudioLevels {
    fun rmsLevel(pcm: ByteArray): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val s = sample.toShort().toDouble()
            sum += s * s
            i += 2
        }
        val samples = pcm.size / 2
        if (samples == 0) return 0f
        val rms = kotlin.math.sqrt(sum / samples)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}
