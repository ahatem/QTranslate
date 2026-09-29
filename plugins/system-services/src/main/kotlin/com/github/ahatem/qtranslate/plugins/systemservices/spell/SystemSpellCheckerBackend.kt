package com.github.ahatem.qtranslate.plugins.systemservices.spell

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/** Source offsets and lengths are UTF-16 code units, as in a Kotlin String. */
internal data class SpellingFinding(
    val start: Int,
    val length: Int,
    val suggestions: List<String>,
    val original: String? = null,
)

internal interface SystemSpellCheckerBackend {
    val displayName: String
    suspend fun languages(): Result<Set<String>, ServiceError>
    suspend fun check(text: String, languageTag: String): Result<List<SpellingFinding>, ServiceError>
    suspend fun close() {}
}
