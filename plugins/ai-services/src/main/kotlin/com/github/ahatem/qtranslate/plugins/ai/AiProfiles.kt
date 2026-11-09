package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * AI Plugin 2.0 — per-capability connection profiles.
 *
 * The plugin targets any **OpenAI-compatible** chat completions endpoint, which covers
 * OpenRouter (default), OpenAI, Mistral, Gemini's OpenAI-compatible endpoint, local
 * Ollama, Azure OpenAI, LM Studio, and any other compatible server.
 *
 * A [AiProfile] is a fully resolved set of connection parameters for one
 * [AiCapability] — endpoint, key, model, headers, temperature, and token budget — ready
 * to be handed to the HTTP client. [AISettings.profileFor] resolves the profile for a
 * capability, inheriting from the Default profile field-wise wherever the capability has
 * no override of its own.
 *
 * Raw API keys are never logged or embedded in derived values: [AiProfile.identityKey]
 * carries only a one-way fingerprint of the key.
 */

/** The AI operations that can each have their own connection profile. */
enum class AiCapability {
    TRANSLATION,
    REWRITE,
    SUMMARIZATION,
    SPELL_CHECK,
    DICTIONARY,
    VISION
}

/**
 * Provider presets: knows which endpoint a preset maps to by default.
 *
 * A preset is a named, well-known endpoint. Picking a preset in the settings UI fills
 * the base URL with that provider's OpenAI-compatible address; picking "custom" leaves
 * the URL to be typed in manually.
 */
object AiProviderPresets {

    data class Preset(val id: String, val label: String, val defaultBaseUrl: String)

    val PRESETS: List<Preset> = listOf(
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        Preset("openai", "OpenAI", "https://api.openai.com/v1"),
        Preset("local", "Local (Ollama / LM Studio)", "http://localhost:11434/v1"),
        Preset("custom", "Custom", "")
    )

    /**
     * The default base URL for [presetId], or null when [presetId] is null or unknown.
     *
     * The "custom" preset has no default address and returns the empty string, so
     * callers can distinguish "known to have no default" from "unknown preset".
     */
    fun defaultBaseUrl(presetId: String?): String? =
        PRESETS.firstOrNull { it.id == presetId }?.defaultBaseUrl
}

/**
 * Endpoint URL helpers.
 *
 * Providers differ on whether the configured base URL already ends in
 * `/chat/completions`; blindly appending the path can produce a doubled
 * `.../chat/completions/chat/completions`. These helpers canonicalize the base URL so
 * the full chat-completions URL is always exactly `<base>/chat/completions`.
 */
object AiEndpoints {

    private const val CHAT_COMPLETIONS_SUFFIX = "/chat/completions"

    /**
     * Canonical form of a configured base URL: surrounding whitespace removed, trailing
     * slashes removed, and a trailing `/chat/completions` path segment (case-insensitive)
     * stripped so [chatCompletionsUrl] never doubles it.
     *
     * A blank input stays blank. Trailing-slash stripping and suffix stripping are
     * applied in that order and then repeated until stable, so inputs like
     * `"https://host/v1/chat/completions/"` or `" https://host/v1// "` canonicalize
     * identically. The scheme, host, port, and path prefixes are otherwise untouched.
     */
    fun normalizedBaseUrl(raw: String): String {
        var candidate = raw.trim()
        if (candidate.isEmpty()) return ""
        var changed = true
        while (changed) {
            changed = false
            val withoutTrailingSlash = candidate.trimEnd('/')
            if (withoutTrailingSlash != candidate) {
                candidate = withoutTrailingSlash
                changed = true
            }
            if (candidate.lowercase().endsWith(CHAT_COMPLETIONS_SUFFIX.lowercase())) {
                candidate = candidate.dropLast(CHAT_COMPLETIONS_SUFFIX.length)
                changed = true
            }
        }
        return candidate.trimEnd('/')
    }

    /**
     * The full chat-completions URL for [baseUrl]:
     * `normalizedBaseUrl(baseUrl) + "/chat/completions"`.
     *
     * Idempotent: feeding the result back through [normalizedBaseUrl] returns the base,
     * so `chatCompletionsUrl(chatCompletionsUrl(x)) == chatCompletionsUrl(x)`.
     */
    fun chatCompletionsUrl(baseUrl: String): String =
        normalizedBaseUrl(baseUrl) + CHAT_COMPLETIONS_SUFFIX
}

/**
 * A fully resolved connection profile for one [AiCapability].
 *
 * Instances are produced by [AISettings.profileFor]; the fields are already resolved, so
 * consumers never need to handle inheritance sentinels themselves.
 *
 * @param preset provider preset id (see [AiProviderPresets]), carried for informational
 *   purposes and for the local-endpoint key rule in [AISettings.profileFor].
 * @param baseUrl OpenAI-compatible base URL as stored (normalization happens in
 *   [chatCompletionsUrl] and [identityKey], not here).
 * @param apiKey the resolved key. Never logged; see [identityKey] for a safe fingerprint.
 * @param model exact model slug for the provider.
 * @param customHeaders extra HTTP headers as a JSON object string, or blank for none.
 * @param temperature output randomness, 0.0 (deterministic) to 2.0 (very creative).
 * @param maxTokens maximum number of tokens the model may generate.
 */
data class AiProfile(
    val preset: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val customHeaders: String,
    val temperature: Double,
    val maxTokens: Int
) {
    /**
     * Whether [baseUrl] points at something running on this machine or network.
     *
     * A local server needs no API key, so requiring one would make Ollama and LM Studio
     * unusable. Matched on the host rather than on a list of known products, since anyone
     * can run an OpenAI-compatible server on any port.
     *
     * Deliberately generous: a private-network address counts, because a model served
     * from another machine on the same LAN is the same privacy story as one served from
     * this one.
     */
    val isLocalEndpoint: Boolean
        get() = isLocalBaseUrl(baseUrl)

    /**
     * The error to report when no key is set, or null when none is needed.
     *
     * A hosted endpoint without a key is "finish setting this up", which is a different
     * problem from a key the provider rejected, and the two deserve different messages.
     * A local endpoint without a key is not a problem at all.
     *
     * The message names the endpoint but never the key.
     */
    fun missingKeyError(): ServiceError? =
        if (apiKey.isBlank() && !isLocalEndpoint) {
            ServiceError.ConfigurationError(
                "No API key set for $baseUrl. Add one in Settings → Plugins → AI Plugin, " +
                    "or point the endpoint at a local server such as Ollama, which needs no key."
            )
        } else {
            null
        }

    /**
     * Deterministic identity for deduplication: two profiles that behave identically
     * produce the same key.
     *
     * Composed as `normalizedBaseUrl|model|temperature|maxTokens|normalizedHeaders|
     * keyFingerprint`, where the fingerprint is the SHA-256 hex digest of the API key.
     * The raw key never appears in this string, so it is safe to log or persist.
     */
    val identityKey: String
        get() = listOf(
            AiEndpoints.normalizedBaseUrl(baseUrl),
            model,
            temperature.toString(),
            maxTokens.toString(),
            normalizedHeaders(customHeaders),
            keyFingerprint(apiKey)
        ).joinToString("|")

    /** The full chat-completions URL for this profile's [baseUrl]. */
    fun chatCompletionsUrl(): String = AiEndpoints.chatCompletionsUrl(baseUrl)
}

/**
 * Whether [baseUrl] points at something running on this machine or network.
 *
 * Hosts that count as local: `localhost`, loopback, private IPv4 ranges (10/8,
 * 172.16–31/12, 192.168/16), and `*.local` mDNS names. A malformed URL counts as hosted,
 * since guessing wrong in the other direction would silently wave a keyless hosted
 * endpoint through.
 */
internal fun isLocalBaseUrl(baseUrl: String): Boolean = runCatching {
    val host = java.net.URI(baseUrl.trim()).host?.lowercase() ?: return@runCatching false
    host == "localhost" ||
        host == "::1" ||
        host.endsWith(".local") ||
        host.startsWith("127.") ||
        host.startsWith("10.") ||
        host.startsWith("192.168.") ||
        Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
}.getOrDefault(false)

/**
 * SHA-256 hex digest of [apiKey] for use as a key fingerprint.
 *
 * Kept in this file (not as a loggable value): the digest is one-way, so it identifies
 * "same key" relationships without revealing the key.
 */
internal fun keyFingerprint(apiKey: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(apiKey.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Whether [headers] is a valid custom-headers value: either blank (meaning "no extra
 * headers") or a JSON object whose values are all strings.
 *
 * Header values are never logged; on failure this returns false without inspecting or
 * printing the contents.
 */
fun isCustomHeadersValid(headers: String): Boolean {
    if (headers.isBlank()) return true
    val parsed = parseHeaderObject(headers) ?: return false
    return parsed.values.all { it is JsonPrimitive && it.isString }
}

/**
 * Deterministic canonical form of a custom-headers value, for [AiProfile.identityKey].
 *
 * A JSON object's key order is irrelevant to the request it produces, so the canonical
 * form sorts entries by key and joins them as `key=value` pairs. Blank input yields "".
 * Malformed input falls back to the trimmed raw string — still deterministic, and still
 * distinguishable by [isCustomHeadersValid].
 */
fun normalizedHeaders(headers: String): String {
    if (headers.isBlank()) return ""
    val parsed = parseHeaderObject(headers) ?: return headers.trim()
    return parsed.entries
        .sortedBy { it.key }
        .joinToString(";") { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            "$key=$text"
        }
}

/**
 * Parses [headers] as a JSON object, or returns null when it is not a JSON object.
 *
 * Arrays and scalars are not header objects, even though they are valid JSON.
 */
private fun parseHeaderObject(headers: String): JsonObject? =
    runCatching { Json.parseToJsonElement(headers) }.getOrNull() as? JsonObject
