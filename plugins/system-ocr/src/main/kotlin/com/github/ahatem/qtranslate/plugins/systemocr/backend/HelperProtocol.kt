package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The JSON object the Windows and macOS helpers write: a result, or a failure with a category the
 * plugin maps onto an existing [ServiceError]. Windows also reports [lines], because it lists a
 * line's words in visual order and [text] is therefore not a reading order for right-to-left text.
 */
@Serializable
internal data class HelperResult(
    val ok: Boolean = false,
    val category: String? = null,
    val error: String? = null,
    val text: String? = null,
    val language: String? = null,
    val languages: JsonElement? = null,
    val maxImageDimension: Int? = null,
    val autoDetect: Boolean? = null,
    val lines: List<HelperLine> = emptyList(),
) {
    /**
     * [languages] as tags. `ConvertTo-Json` renders a one-element list as a scalar, so both forms
     * are accepted.
     */
    val languageTags: List<String>
        get() = when (val node = languages) {
            null -> emptyList()
            is JsonArray -> node.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(node.contentOrNull)
            else -> emptyList()
        }
}

/** One recognized line, with the words the engine reported in it. */
@Serializable
internal data class HelperLine(
    /** The engine's line text, in its own (visual) order. */
    val text: String = "",
    val words: List<HelperWord> = emptyList(),
)

/** One recognized word and where it sits on the page. `t` keeps the payload small. */
@Serializable
internal data class HelperWord(
    val t: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
)

/** Reads and interprets the helper's result file. */
internal object HelperProtocol {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(file: File, outcome: ProcessOutcome): Result<HelperResult, ServiceError> {
        if (outcome.timedOut) {
            return Err(ServiceError.TimeoutError("The system OCR engine did not finish in time."))
        }

        val raw = runCatching {
            if (file.isFile && file.length() > 0L) file.readText() else null
        }.getOrNull() ?: return Err(
            ServiceError.InvalidResponseError("The system OCR engine produced no result${diagnostic(outcome)}.")
        )

        return runCatching { json.decodeFromString<HelperResult>(raw) }.fold(
            onSuccess = { Ok(it) },
            onFailure = {
                Err(ServiceError.InvalidResponseError("The system OCR engine returned an unreadable result.", it))
            },
        )
    }

    /** Maps a failed helper result onto the closest existing [ServiceError]. */
    fun toError(result: HelperResult, requestedLanguage: LanguageCode?): ServiceError {
        val message = result.error?.takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE
        return when (result.category) {
            CATEGORY_UNSUPPORTED_LANGUAGE ->
                requestedLanguage?.let { ServiceError.UnsupportedLanguageError(it, message) }
                    ?: ServiceError.InvalidInputError(message)

            CATEGORY_INVALID_INPUT -> ServiceError.InvalidInputError(message)
            CATEGORY_MISSING_COMPONENT, CATEGORY_NO_ENGINE -> ServiceError.ConfigurationError(message)
            else -> ServiceError.UnknownError(message)
        }
    }

    private fun diagnostic(outcome: ProcessOutcome): String =
        outcome.stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let { " ($it)" }
            ?: if (outcome.exitCode != 0) " (exit code ${outcome.exitCode})" else ""

    const val CATEGORY_UNSUPPORTED_LANGUAGE = "unsupported_language"
    const val CATEGORY_INVALID_INPUT = "invalid_input"
    const val CATEGORY_MISSING_COMPONENT = "missing_component"
    const val CATEGORY_NO_ENGINE = "no_engine"
    private const val DEFAULT_MESSAGE = "The system OCR engine failed."
}
