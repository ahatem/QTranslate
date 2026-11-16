package com.github.ahatem.qtranslate.core.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the Conversation Sessions window remembers between runs. Kept beside the sessions rather
 * than in the main configuration: it is the window's own state, and nothing else reads it.
 */
@Serializable
data class ConversationPreferences(
    val microphoneId: String? = null,
    val systemOutputId: String? = null,
    val systemAudioMode: String = "device",
    val preferredProcessName: String? = null,
    val spokenLanguage: String = "auto",
    val callLanguage: String = "auto",
    val targetLanguage: String? = null,
    val keepAudio: Boolean = true,
    /** Aligns "Me" and "Them" cards on opposite sides of the transcript view. */
    val chatLayout: Boolean = true,
    /** Automatically translate each settled line into target language. */
    val autoTranslate: Boolean = false,
    /** Whether the one-time notice about online translation has been dismissed. */
    val autoTranslateNoticeDismissed: Boolean = false,
    /** The privacy notice has been read and dismissed. */
    val privacyNoticeDismissed: Boolean = false,
    /** Number of latest cards to keep in the visible window (10..100). */
    val visibleCardWindow: Int = 10
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(file: File): ConversationPreferences =
            runCatching { json.decodeFromString(serializer(), file.readText(Charsets.UTF_8)) }
                .getOrDefault(ConversationPreferences())

        fun save(file: File, preferences: ConversationPreferences) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(serializer(), preferences), Charsets.UTF_8)
            }
        }
    }
}
