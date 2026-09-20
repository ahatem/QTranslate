package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.language.SystemOcrLanguages
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.map

/**
 * Linux OCR through the system `tesseract` executable.
 *
 * Running the installed binary rather than binding libtesseract keeps the plugin free of native
 * libraries and model downloads; language data is whatever the user has installed.
 *
 * Tesseract must be told which language to read, so `LanguageCode.AUTO` is rejected rather than
 * answered with a default language.
 */
internal class LinuxTesseractBackend(
    private val runner: ProcessRunner,
    private val tesseract: String?,
) : SystemOcrBackend {

    override val displayName: String = "Tesseract"

    override suspend fun validate(): Result<Unit, ServiceError> = tooling().map { }

    override suspend fun supportedLanguages(): Result<BackendLanguages, ServiceError> = coroutineBinding {
        val executable = tooling().bind()
        BackendLanguages(
            languages = installedLanguageCodes(executable).bind()
                .mapNotNull { SystemOcrLanguages.fromTesseract(it) }
                .toSet(),
            detectsLanguage = false,
        )
    }

    override suspend fun recognize(
        image: ImageData,
        language: LanguageCode,
    ): Result<String, ServiceError> = coroutineBinding {
        val executable = tooling().bind()

        if (language == LanguageCode.AUTO) {
            Err(ServiceError.UnsupportedLanguageError(LanguageCode.AUTO, AUTO_MESSAGE)).bind()
        }

        val code = SystemOcrLanguages.toTesseract(language)
            ?: Err(
                ServiceError.UnsupportedLanguageError(
                    language,
                    "Tesseract has no language data for '${language.tag}'.",
                )
            ).bind()

        // Tesseract has no maximum image dimension.
        val prepared = OcrImage.prepare(image, maxDimension = null).bind()

        val imageFile = TempFiles.write(IMAGE_PREFIX, ".${prepared.format}", prepared.bytes)
        try {
            // "stdout" as the output base writes the text to standard output instead of a file.
            val outcome = runner.run(
                listOf(executable, imageFile.absolutePath, "stdout", "-l", code),
                RECOGNIZE_TIMEOUT_MS,
            ).bind()
            if (outcome.timedOut) {
                Err(ServiceError.TimeoutError("Tesseract did not finish in time.")).bind()
            }
            if (outcome.exitCode != 0) {
                Err(failureFor(outcome, language)).bind()
            }
            outcome.stdout.trimEnd('\n', '\r')
        } finally {
            TempFiles.deleteQuietly(imageFile)
        }
    }

    private fun tooling(): Result<String, ServiceError> =
        tesseract?.takeIf { it.isNotBlank() }?.let { Ok(it) }
            ?: Err(
                ServiceError.ConfigurationError(
                    "Tesseract is not installed or not on the PATH. Install the tesseract-ocr package " +
                        "for your distribution and try again."
                )
            )

    /** The installed traineddata codes naming a language, dropping models such as `osd` and `equ`. */
    private suspend fun installedLanguageCodes(executable: String): Result<List<String>, ServiceError> =
        coroutineBinding {
            val outcome = runner.run(listOf(executable, "--list-langs"), CAPABILITIES_TIMEOUT_MS).bind()
            if (outcome.timedOut) {
                Err(ServiceError.TimeoutError("Tesseract did not return its language list in time.")).bind()
            }
            if (outcome.exitCode != 0) {
                val detail = firstNonBlankLine(outcome.stderr) ?: "Tesseract could not list its languages."
                Err(ServiceError.UnknownError(detail)).bind()
            }

            // Newer Tesseract prints the list on stdout after a header line; older builds used stderr.
            val listing = outcome.stdout.ifBlank { outcome.stderr }
            listing.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(LANGUAGE_LIST_HEADER) }
                .filter { SystemOcrLanguages.fromTesseract(it) != null }
                .toList()
        }

    private fun failureFor(outcome: ProcessOutcome, language: LanguageCode): ServiceError {
        val detail = firstNonBlankLine(outcome.stderr) ?: "Tesseract failed with exit code ${outcome.exitCode}."
        return when {
            detail.contains("Failed loading language", ignoreCase = true) ||
                detail.contains("Error opening data file", ignoreCase = true) ||
                detail.contains("Could not initialize tesseract", ignoreCase = true) ->
                ServiceError.UnsupportedLanguageError(language, detail)

            detail.contains("image", ignoreCase = true) || detail.contains("format", ignoreCase = true) ->
                ServiceError.InvalidInputError(detail)

            else -> ServiceError.UnknownError(detail)
        }
    }

    private fun firstNonBlankLine(text: String): String? =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()

    private companion object {
        const val LANGUAGE_LIST_HEADER = "List of available languages"
        const val IMAGE_PREFIX = "qt-system-ocr-"
        const val RECOGNIZE_TIMEOUT_MS = 25_000L
        const val CAPABILITIES_TIMEOUT_MS = 10_000L
        const val AUTO_MESSAGE =
            "Tesseract cannot detect the language of an image. Choose a specific language for text recognition."
    }
}
