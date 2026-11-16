package com.github.ahatem.qtranslate.core.conversation

/**
 * A recording device the user can pick.
 *
 * @param id   Stable identifier the backend uses to reopen the device.
 * @param name What the operating system calls it, shown as-is.
 */
data class AudioDevice(val id: String, val name: String) {
    /**
     * Whether the name suggests the device carries what the computer is playing rather than a
     * microphone: Windows' "Stereo Mix", or a virtual cable such as VB-Cable or VoiceMeeter.
     * Only a hint for preselecting the "their voices" picker; the user can always choose another.
     */
    val looksLikeSystemAudio: Boolean
        get() = SYSTEM_AUDIO_HINTS.any { name.contains(it, ignoreCase = true) }

    override fun toString(): String = name

    private companion object {
        val SYSTEM_AUDIO_HINTS = listOf(
            "stereo mix", "what u hear", "wave out", "loopback",
            "cable output", "vb-audio", "voicemeeter", "monitor of"
        )
    }
}

/**
 * An application producing audio that can be selected for recording.
 *
 * @param pid The operating system process ID.
 * @param processName The executable process name (e.g. "Discord", "chrome").
 * @param windowTitle The main window title if available (e.g. "Discord | #general").
 * @param device The target audio output device this application is routed to.
 */
data class AudioApplication(
    val pid: Int,
    val processName: String,
    val windowTitle: String,
    val device: AudioDevice
) {
    val displayName: String
        get() {
            val titlePart = if (windowTitle.isNotBlank()) " - $windowTitle" else ""
            return "$processName$titlePart (${device.name})"
        }

    override fun toString(): String = displayName
}

/**
 * Where recorded audio comes from.
 *
 * Kept behind an interface so the conversation logic never touches `javax.sound` directly and
 * can be exercised with recorded samples in tests.
 */
interface AudioBackend {
    /** Every device that can be recorded from right now. */
    fun inputDevices(): List<AudioDevice>

    /**
     * Opens [device] for recording.
     *
     * @throws AudioDeviceUnavailableException when the device is gone or busy.
     */
    fun open(device: AudioDevice): PcmStream
}

/**
 * A live recording, always delivered as 16-bit little-endian PCM, mono, at [SAMPLE_RATE] —
 * whatever the device natively records, the backend converts.
 */
interface PcmStream : AutoCloseable {
    /**
     * Blocks until the next chunk is available and returns it, or `null` once the stream has
     * been closed. Closing from another thread unblocks a pending read.
     */
    fun read(): ByteArray?

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

class AudioDeviceUnavailableException(val device: AudioDevice, cause: Throwable? = null) :
    Exception("Audio device '${device.name}' is unavailable.", cause)
