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
import java.io.File

/**
 * macOS OCR through Apple's Vision framework, run out of process as a Swift helper that is built
 * with the plugin and staged into its data directory. Without a Swift toolchain the helper is absent
 * and this backend reports a configuration error.
 *
 * Vision can detect the language of an image (macOS 13+), so it is the only backend offering
 * `LanguageCode.AUTO`, and only when the helper reports that the running macOS supports it.
 */
internal class MacVisionOcrBackend(
    private val runner: ProcessRunner,
    private val helper: File?,
) : SystemOcrBackend {

    override val displayName: String = "Apple Vision"

    override suspend fun validate(): Result<Unit, ServiceError> = tooling().map { }

    override suspend fun supportedLanguages(): Result<BackendLanguages, ServiceError> = coroutineBinding {
        val executable = tooling().bind()
        val resultFile = TempFiles.create(RESULT_PREFIX, JSON_SUFFIX)
        try {
            val outcome = runner.run(
                listOf(executable.absolutePath, "--command", COMMAND_CAPABILITIES, "--output", resultFile.absolutePath),
                CAPABILITIES_TIMEOUT_MS,
            ).bind()
            val result = HelperProtocol.read(resultFile, outcome).bind()
            if (!result.ok) Err(HelperProtocol.toError(result, null)).bind()
            BackendLanguages(
                languages = result.languageTags.mapNotNull { SystemOcrLanguages.fromBcp47(it) }.toSet(),
                detectsLanguage = result.autoDetect == true,
            )
        } finally {
            TempFiles.deleteQuietly(resultFile)
        }
    }

    override suspend fun recognize(
        image: ImageData,
        language: LanguageCode,
    ): Result<String, ServiceError> = coroutineBinding {
        val executable = tooling().bind()
        // Vision has no maximum image dimension.
        val prepared = OcrImage.prepare(image, maxDimension = null).bind()

        val imageFile = TempFiles.write(IMAGE_PREFIX, ".${prepared.format}", prepared.bytes)
        val resultFile = TempFiles.create(RESULT_PREFIX, JSON_SUFFIX)
        try {
            // An empty language asks the helper for Vision's own language detection.
            val outcome = runner.run(
                listOf(
                    executable.absolutePath,
                    "--command", COMMAND_RECOGNIZE,
                    "--image", imageFile.absolutePath,
                    "--language", if (language == LanguageCode.AUTO) "" else language.tag,
                    "--output", resultFile.absolutePath,
                ),
                RECOGNIZE_TIMEOUT_MS,
            ).bind()
            val result = HelperProtocol.read(resultFile, outcome).bind()
            if (!result.ok) Err(HelperProtocol.toError(result, language)).bind()
            result.text.orEmpty()
        } finally {
            TempFiles.deleteQuietly(imageFile, resultFile)
        }
    }

    private fun tooling(): Result<File, ServiceError> {
        val file = helper?.takeIf { it.isFile && it.canExecute() }
            ?: return Err(
                ServiceError.ConfigurationError(
                    "The Apple Vision OCR helper is not available. It is built only on macOS with a " +
                        "Swift toolchain; rebuild the plugin on macOS."
                )
            )
        return Ok(file)
    }

    private companion object {
        const val COMMAND_RECOGNIZE = "recognize"
        const val COMMAND_CAPABILITIES = "capabilities"
        const val IMAGE_PREFIX = "qt-system-ocr-"
        const val RESULT_PREFIX = "qt-system-ocr-result-"
        const val JSON_SUFFIX = ".json"
        const val RECOGNIZE_TIMEOUT_MS = 25_000L
        const val CAPABILITIES_TIMEOUT_MS = 10_000L
    }
}
