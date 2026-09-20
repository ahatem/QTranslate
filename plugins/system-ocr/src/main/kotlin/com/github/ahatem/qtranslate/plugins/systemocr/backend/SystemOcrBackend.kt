package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * What a backend can do, discovered from the platform.
 *
 * @param languages the languages the engine can recognize right now.
 * @param detectsLanguage whether the engine detects the language of an image. `LanguageCode.AUTO`
 *   means detection, so a backend that falls back to a default language reports false.
 */
internal data class BackendLanguages(
    val languages: Set<LanguageCode>,
    val detectsLanguage: Boolean,
)

/**
 * A platform's on-device OCR engine, internal to the plugin. The object QTranslate sees is
 * [com.github.ahatem.qtranslate.plugins.systemocr.SystemOcrService].
 */
internal interface SystemOcrBackend {

    /** The engine's name, for logs and diagnostics. */
    val displayName: String

    /**
     * Recognizes [image], using [language] as a hint. A backend that cannot detect a language must
     * reject `AUTO` with [ServiceError.UnsupportedLanguageError] rather than substituting a default;
     * the service resolves `AUTO` to a concrete language before calling such a backend.
     */
    suspend fun recognize(image: ImageData, language: LanguageCode): Result<String, ServiceError>

    /** The languages the engine can recognize, and whether it detects language at all. */
    suspend fun supportedLanguages(): Result<BackendLanguages, ServiceError>

    /** Whether the engine and any component it needs are present on this machine. */
    suspend fun validate(): Result<Unit, ServiceError>
}
