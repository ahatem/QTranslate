package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ExecutableLocator
import com.github.ahatem.qtranslate.plugins.systemservices.backend.PathExecutableLocator
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessRunner
import com.github.ahatem.qtranslate.plugins.systemservices.backend.RealProcessRunner
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ResourceExtractor
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.io.File

internal object SystemTtsBackends {
    fun create(context: PluginContext): Result<SystemTtsBackend, ServiceError> = create(
        System.getProperty("os.name").orEmpty(), context.getPluginDataDirectory(),
        RealProcessRunner(), PathExecutableLocator,
    )

    fun create(
        osName: String,
        dataDirectory: File,
        runner: ProcessRunner,
        locator: ExecutableLocator,
    ): Result<SystemTtsBackend, ServiceError> = when {
        osName.startsWith("Windows", true) -> {
            val exe = locator.locate("powershell.exe")
                ?: System.getenv("SystemRoot")?.let {
                    File(it, "System32/WindowsPowerShell/v1.0/powershell.exe").takeIf(File::isFile)?.absolutePath
                }
            val script = ResourceExtractor.extract(
                "scripts/windows-tts.ps1", File(dataDirectory, "native/windows/windows-tts.ps1")
            )
            if (exe == null || script == null)
                Err(ServiceError.ConfigurationError("Windows PowerShell speech synthesis is unavailable."))
            else Ok(WindowsTtsBackend(runner, exe, script))
        }
        osName.startsWith("Mac", true) || osName.startsWith("Darwin", true) -> {
            val say = File("/usr/bin/say").takeIf(File::isFile)?.absolutePath ?: locator.locate("say")
            val convert = File("/usr/bin/afconvert").takeIf(File::isFile)?.absolutePath
                ?: locator.locate("afconvert")
            if (say == null || convert == null)
                Err(ServiceError.ConfigurationError("macOS speech tools are unavailable."))
            else Ok(MacTtsBackend(runner, say, convert))
        }
        osName.startsWith("Linux", true) -> {
            val exe = locator.locate("espeak-ng") ?: locator.locate("espeak")
            if (exe == null)
                Err(ServiceError.ConfigurationError("No installed eSpeak speech engine was found."))
            else Ok(LinuxTtsBackend(runner, exe))
        }
        else -> Err(ServiceError.ConfigurationError("System TTS is not supported on this operating system."))
    }
}
