package com.github.ahatem.qtranslate.api.plugin

import com.github.michaelbull.result.Result

/**
 * An [HttpClient] that can also upload files: as `multipart/form-data`, or as a raw request body.
 *
 * Optional behaviour rather than a change to [HttpClient] itself, for the same reason as
 * [SingleAttemptHttpClient]: a plugin that brings its own client, or a test double, keeps
 * compiling. The host's client implements it, and a plugin asks with an ordinary cast:
 *
 * ```kotlin
 * val uploads = context.http as? MultipartHttpClient
 *     ?: return Err(ServiceError.ConfigurationError("This host cannot upload files."))
 * ```
 *
 * Speech and document APIs take their input this way and accept nothing else: OpenAI-style
 * endpoints want a multipart form, others (Deepgram, for one) the audio itself as the body.
 */
public interface MultipartHttpClient : HttpClient {

    /**
     * Sends [parts] as one `multipart/form-data` POST and returns the response body as text.
     *
     * The boundary and `Content-Type` header are set by the client; [headers] should not
     * include them.
     */
    public suspend fun postMultipart(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError>

    /**
     * Sends [body] as-is in one POST with the given [contentType], and returns the response body
     * as text. [headers] should not include `Content-Type`.
     */
    public suspend fun postBytes(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError>
}

/** One field of a `multipart/form-data` body. */
public sealed interface MultipartPart {

    /** The form field name. */
    public val name: String

    /** A plain text field. */
    public data class Text(override val name: String, val value: String) : MultipartPart

    /** A file upload. */
    public class File(
        override val name: String,
        public val fileName: String,
        public val contentType: String,
        public val bytes: ByteArray
    ) : MultipartPart
}
