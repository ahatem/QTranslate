package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.language.SystemOcrLanguages
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Windows OCR backed by `Windows.Media.Ocr`.
 *
 * WinRT is reached through a PowerShell script shipped in this JAR rather than a JNI bridge: the
 * script already speaks WinRT, where an in-process binding would mean COM activation and an async
 * bridge for a single call. It is invoked with an argument list, never a shell string.
 *
 * The engine recognizes with one chosen recognizer and does not detect the language of an image, so
 * `LanguageCode.AUTO` is rejected. It lists a line's words in visual order, which [OcrLineOrder]
 * converts back to logical order.
 */
internal class WindowsOcrBackend(
    private val runner: ProcessRunner,
    private val scriptFile: File?,
    private val powershell: String?,
) : SystemOcrBackend {

    override val displayName: String = "Windows.Media.Ocr"

    private val capabilitiesMutex = Mutex()
    private var cachedCapabilities: Capabilities? = null

    override suspend fun validate(): Result<Unit, ServiceError> = tooling().map { }

    override suspend fun supportedLanguages(): Result<BackendLanguages, ServiceError> =
        capabilities().map { BackendLanguages(it.languages, detectsLanguage = false) }

    override suspend fun recognize(
        image: ImageData,
        language: LanguageCode,
    ): Result<String, ServiceError> = coroutineBinding {
        val tooling = tooling().bind()

        if (language == LanguageCode.AUTO) {
            Err(ServiceError.UnsupportedLanguageError(LanguageCode.AUTO, AUTO_MESSAGE)).bind()
        }

        // Reject a corrupt image before starting a process.
        val decoded = OcrImage.decode(image).bind()

        // The engine's own limit, from OcrEngine.MaxImageDimension.
        val maxDimension = capabilities().bind().maxImageDimension
        val prepared = OcrImage.prepare(image, decoded, maxDimension).bind()

        val imageFile = TempFiles.write(IMAGE_PREFIX, ".${prepared.format}", prepared.bytes)
        val resultFile = TempFiles.create(RESULT_PREFIX, JSON_SUFFIX)
        try {
            val outcome = runner.run(
                tooling.powerShellCommand(
                    "-Command", COMMAND_RECOGNIZE,
                    "-ImagePath", imageFile.absolutePath,
                    "-Language", language.tag,
                    "-OutputPath", resultFile.absolutePath,
                ),
                RECOGNIZE_TIMEOUT_MS,
            ).bind()
            val result = HelperProtocol.read(resultFile, outcome).bind()
            if (!result.ok) Err(HelperProtocol.toError(result, language)).bind()

            // Words arrive in visual order; rebuild each line and keep the line order the engine gave.
            if (result.lines.isEmpty()) {
                result.text.orEmpty()
            } else {
                result.lines.joinToString("\n") { line -> OcrLineOrder.lineText(line, language) }
            }
        } finally {
            TempFiles.deleteQuietly(imageFile, resultFile)
        }
    }

    /** The engine's languages and maximum image dimension, read once. */
    private suspend fun capabilities(): Result<Capabilities, ServiceError> {
        cachedCapabilities?.let { return Ok(it) }
        return capabilitiesMutex.withLock {
            cachedCapabilities?.let { return@withLock Ok(it) }

            val loaded = coroutineBinding {
                val tooling = tooling().bind()
                val resultFile = TempFiles.create(RESULT_PREFIX, JSON_SUFFIX)
                try {
                    val outcome = runner.run(
                        tooling.powerShellCommand(
                            "-Command", COMMAND_CAPABILITIES,
                            "-OutputPath", resultFile.absolutePath,
                        ),
                        CAPABILITIES_TIMEOUT_MS,
                    ).bind()
                    val result = HelperProtocol.read(resultFile, outcome).bind()
                    if (!result.ok) Err(HelperProtocol.toError(result, null)).bind()
                    val maxDimension = result.maxImageDimension
                        ?: Err(
                            ServiceError.InvalidResponseError(
                                "Windows OCR did not report its maximum image dimension."
                            )
                        ).bind()
                    Capabilities(
                        languages = result.languageTags.mapNotNull { SystemOcrLanguages.fromBcp47(it) }.toSet(),
                        maxImageDimension = maxDimension,
                    )
                } finally {
                    TempFiles.deleteQuietly(resultFile)
                }
            }

            loaded.fold(
                success = { capabilities ->
                    cachedCapabilities = capabilities
                    Ok(capabilities)
                },
                failure = { Err(it) },
            )
        }
    }

    private fun tooling(): Result<Tooling, ServiceError> {
        val executable = powershell?.takeIf { it.isNotBlank() }
            ?: return Err(
                ServiceError.ConfigurationError(
                    "Windows PowerShell could not be found, so the System OCR engine cannot be started."
                )
            )
        val script = scriptFile?.takeIf { it.isFile }
            ?: return Err(
                ServiceError.ConfigurationError(
                    "The Windows OCR helper script could not be staged from the plugin; reinstall the plugin."
                )
            )
        return Ok(Tooling(executable, script))
    }

    private data class Capabilities(val languages: Set<LanguageCode>, val maxImageDimension: Int)

    private data class Tooling(val executable: String, val script: File) {
        fun powerShellCommand(vararg scriptArguments: String): List<String> = buildList {
            add(executable)
            add("-NoProfile")
            add("-NonInteractive")
            add("-ExecutionPolicy")
            add("Bypass")
            add("-File")
            add(script.absolutePath)
            addAll(scriptArguments)
        }
    }

    private companion object {
        const val COMMAND_RECOGNIZE = "recognize"
        const val COMMAND_CAPABILITIES = "capabilities"
        const val IMAGE_PREFIX = "qt-system-ocr-"
        const val RESULT_PREFIX = "qt-system-ocr-result-"
        const val JSON_SUFFIX = ".json"
        const val RECOGNIZE_TIMEOUT_MS = 25_000L
        const val CAPABILITIES_TIMEOUT_MS = 10_000L
        const val AUTO_MESSAGE =
            "Windows OCR does not detect the language of an image. Choose a specific language for text recognition."
    }
}
