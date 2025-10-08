package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Generic JSON response parser using Kotlinx Serialization.
 */
class JsonResponseParser<T>(
    private val pluginContext: PluginContext,
    private val deserializer: (String) -> T,
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
) : ResponseParser<T> {

    override suspend fun parse(jsonString: String): Result<T, ServiceError> {
        return try {
            Ok(deserializer(jsonString))
        } catch (e: SerializationException) {
            pluginContext.logger.error("JSON parsing failed", e)
            Err(ServiceError.InvalidResponseError("Failed to parse JSON: ${e.message}", e))
        } catch (e: Exception) {
            pluginContext.logger.error("Unexpected error during parsing", e)
            Err(ServiceError.UnknownError("Unexpected parsing error: ${e.message}", e))
        }
    }
}

/**
 * Creates a JsonResponseParser for inline reified types.
 */
inline fun <reified T> createJsonParser(
    pluginContext: PluginContext,
    json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
): JsonResponseParser<T> {
    return JsonResponseParser(
        pluginContext = pluginContext,
        deserializer = { jsonString -> json.decodeFromString<T>(jsonString) },
        json = json
    )
}

inline fun <reified T> JsonResponseParser<T>.jsonEncodeToString(value: T): String = json.encodeToString(value)