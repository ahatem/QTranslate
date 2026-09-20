package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.io.File

/**
 * Where the compiled macOS Vision helper sits in the JAR. It carries no architecture because the
 * pipeline builds one universal arm64 + x86_64 binary, so one path serves both. The Gradle build
 * stages it here and PackagedPluginJarTest checks it is present.
 */
internal const val MAC_VISION_HELPER_RESOURCE = "native/macos/vision_ocr"

/** Chooses the platform backend and stages any helper it needs out of the plugin JAR. */
internal object SystemOcrBackends {

    fun create(context: PluginContext): Result<SystemOcrBackend, ServiceError> = create(
        osName = System.getProperty("os.name").orEmpty(),
        dataDirectory = context.getPluginDataDirectory(),
        runner = RealProcessRunner(),
        locator = PathExecutableLocator,
    )

    internal fun create(
        osName: String,
        dataDirectory: File,
        runner: ProcessRunner,
        locator: ExecutableLocator,
    ): Result<SystemOcrBackend, ServiceError> = when {
        osName.startsWith("Windows", ignoreCase = true) -> {
            val script = ResourceExtractor.extract(WINDOWS_SCRIPT, File(dataDirectory, WINDOWS_SCRIPT_TARGET))
            val powershell = locator.locate("powershell.exe") ?: defaultPowerShellPath()
            Ok(WindowsOcrBackend(runner, script, powershell))
        }

        osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> {
            val helper = ResourceExtractor.extract(
                MAC_VISION_HELPER_RESOURCE,
                File(dataDirectory, MAC_VISION_HELPER_RESOURCE),
                executable = true,
            )
            Ok(MacVisionOcrBackend(runner, helper))
        }

        osName.startsWith("Linux", ignoreCase = true) ->
            Ok(LinuxTesseractBackend(runner, locator.locate("tesseract")))

        else -> Err(
            ServiceError.ConfigurationError(
                "System OCR is not supported on '$osName'. It is available on Windows, macOS and Linux."
            )
        )
    }

    /** `System32\WindowsPowerShell\v1.0\powershell.exe`, for machines where it is off the PATH. */
    private fun defaultPowerShellPath(): String? {
        val systemRoot = System.getenv("SystemRoot") ?: return null
        val candidate = File(systemRoot, "System32/WindowsPowerShell/v1.0/powershell.exe")
        return candidate.takeIf { it.isFile }?.absolutePath
    }

    private const val WINDOWS_SCRIPT = "scripts/windows-ocr.ps1"
    private const val WINDOWS_SCRIPT_TARGET = "native/windows/windows-ocr.ps1"
}
