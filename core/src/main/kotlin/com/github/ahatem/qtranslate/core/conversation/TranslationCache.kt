package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Remembers translations of words and phrases from the transcript, and never asks for the same
 * one twice at once.
 *
 * Hovering sweeps across text: the same word is hovered again and again, and a request is often
 * abandoned halfway when the pointer moves on. Requests therefore run in [scope] rather than the
 * caller's coroutine — leaving a word cancels only the wait, the translation still lands in the
 * cache for the next hover. Failures are not cached, so the next hover tries again.
 */
class TranslationCache(
    private val scope: CoroutineScope,
    private val request: suspend (text: String, target: LanguageCode) -> Result<String, ServiceError>,
    private val maxEntries: Int = 2_000
) {
    private data class Key(val text: String, val target: LanguageCode)

    private val done = object : LinkedHashMap<Key, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>): Boolean = size > maxEntries
    }
    private val inFlight = HashMap<Key, Deferred<Result<String, ServiceError>>>()

    /** The cached translation, without asking for one. */
    fun peek(text: String, target: LanguageCode): String? =
        synchronized(this) { done[Key(normalize(text), target)] }

    suspend fun translate(text: String, target: LanguageCode): Result<String, ServiceError> {
        val key = Key(normalize(text), target)
        val pending = synchronized(this) {
            done[key]?.let { return Ok(it) }
            inFlight.getOrPut(key) { scope.async(start = CoroutineStart.LAZY) { fetch(key) } }
        }
        return pending.await()
    }

    private suspend fun fetch(key: Key): Result<String, ServiceError> {
        val result = try {
            request(key.text, key.target)
        } finally {
            synchronized(this) { inFlight.remove(key) }
        }
        result.onSuccess { translated -> synchronized(this) { done[key] = translated } }
        return result
    }

    // Case is kept: "Bill" and "bill" can translate differently. Surrounding punctuation is not.
    private fun normalize(text: String): String = text.trim().trim { !it.isLetterOrDigit() }.ifEmpty { text.trim() }
}
