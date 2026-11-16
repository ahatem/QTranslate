package com.github.ahatem.qtranslate.core.conversation

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stores conversation sessions on disk, one folder per session:
 *
 * ```
 * conversations/<id>/session.json      metadata and transcript
 * conversations/<id>/microphone.wav    the user's microphone
 * conversations/<id>/system.wav        what the computer played
 * ```
 *
 * Plain files rather than a database: sessions are few, each is read whole, and a user can copy,
 * back up or delete a folder by hand. Audio and transcript live in separate files on purpose, so
 * a failed transcript write never touches a recording.
 */
class SessionRepository(val root: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    init {
        root.mkdirs()
    }

    fun folderOf(sessionId: String): File = File(root, sessionId)

    fun audioFile(sessionId: String, source: AudioSource): File = File(folderOf(sessionId), source.fileName)

    /** Every readable session, newest first. A folder with an unreadable file is skipped. */
    fun list(): List<ConversationSession> =
        root.listFiles { file -> file.isDirectory }.orEmpty()
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.createdAt }

    fun load(sessionId: String): ConversationSession? {
        val file = File(folderOf(sessionId), SESSION_FILE)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(ConversationSession.serializer(), file.readText(Charsets.UTF_8)) }
            .getOrNull()
    }

    /** Writes [session] atomically: a crash mid-write leaves the previous version intact. */
    fun save(session: ConversationSession) {
        val folder = folderOf(session.id).apply { mkdirs() }
        val temp = File(folder, "$SESSION_FILE.tmp")
        temp.writeText(json.encodeToString(ConversationSession.serializer(), session), Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), File(folder, SESSION_FILE).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), File(folder, SESSION_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(sessionId: String): Boolean = folderOf(sessionId).deleteRecursively()

    /** Removes a session's recordings, keeping its transcript. */
    fun deleteAudio(sessionId: String) {
        AudioSource.entries.forEach { audioFile(sessionId, it).delete() }
    }

    fun hasAudio(sessionId: String, source: AudioSource): Boolean =
        audioFile(sessionId, source).length() > WavFiles.HEADER_SIZE

    /**
     * Sessions whose title or transcript contains [query], ignoring case. Each hit lists the
     * matching segments; a title-only match lists none.
     */
    fun search(query: String): List<SessionSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return list().map { SessionSearchHit(it, emptyList()) }
        return list().mapNotNull { session ->
            val segments = session.segments.filter { it.text.contains(needle, ignoreCase = true) }
            if (segments.isEmpty() && !session.title.contains(needle, ignoreCase = true)) null
            else SessionSearchHit(session, segments)
        }
    }

    /**
     * Marks sessions left in [SessionStatus.RECORDING] by a crash as interrupted and repairs their
     * recordings' headers so the audio up to the crash plays. Returns the sessions it recovered.
     */
    fun recoverInterrupted(): List<ConversationSession> =
        list().filter { it.status == SessionStatus.RECORDING }.map { session ->
            AudioSource.entries.forEach { WavFiles.repair(audioFile(session.id, it)) }
            val duration = AudioSource.entries.maxOf { source ->
                val stream = session.streams.firstOrNull { it.source == source }
                val file = audioFile(session.id, source)
                if (stream == null || !file.isFile) 0L else stream.offsetMillis + WavFiles.durationMillis(file)
            }
            session.copy(status = SessionStatus.INTERRUPTED, durationMillis = maxOf(duration, session.durationMillis))
                .also(::save)
        }

    /** The transcript as plain text, one line per segment: `[00:12] Me: …`. */
    fun exportText(
        session: ConversationSession,
        includeTranslations: Boolean = false,
        speakerName: (AudioSource) -> String
    ): String = buildString {
        appendLine(session.title)
        appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(session.createdAt)))
        appendLine()
        session.segments.filter { it.text.isNotBlank() }.forEach { segment ->
            appendLine("[${formatTimestamp(segment.startMillis)}] ${speakerName(segment.source)}: ${segment.text}")
            if (includeTranslations && !segment.translation.isNullOrBlank()) {
                appendLine("   ${segment.translation}")
            }
        }
    }

    /** Exports the transcript in standard SubRip (.srt) subtitle format. */
    fun exportSrt(
        session: ConversationSession,
        includeTranslations: Boolean = false,
        speakerName: (AudioSource) -> String
    ): String = buildString {
        var index = 1
        session.segments.filter { it.text.isNotBlank() }.forEach { segment ->
            appendLine(index++)
            appendLine("${formatSrtTimestamp(segment.startMillis)} --> ${formatSrtTimestamp(segment.endMillis)}")
            appendLine("${speakerName(segment.source)}: ${segment.text}")
            if (includeTranslations && !segment.translation.isNullOrBlank()) {
                appendLine(segment.translation)
            }
            appendLine()
        }
    }

    private fun formatSrtTimestamp(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        val ms = millis % 1000
        return "%02d:%02d:%02d,%03d".format(Locale.ROOT, h, m, s, ms)
    }

    private companion object {
        const val SESSION_FILE = "session.json"
    }
}

/** `m:ss`, or `h:mm:ss` past an hour. */
fun formatTimestamp(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
