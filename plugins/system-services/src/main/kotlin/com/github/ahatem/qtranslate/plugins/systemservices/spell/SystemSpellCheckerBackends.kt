package com.github.ahatem.qtranslate.plugins.systemservices.spell

import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.PathExecutableLocator
import com.github.ahatem.qtranslate.plugins.systemservices.backend.RealProcessRunner
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ResourceExtractor
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.io.File

internal object SystemSpellCheckerBackends {
    const val MAC_RESOURCE = "native/macos/system_spell"

    suspend fun create(context: PluginContext): Result<SystemSpellCheckerBackend, ServiceError> =
        when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> Ok(WindowsSpellCheckerBackend())
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> {
                val helper = ResourceExtractor.extract(
                    MAC_RESOURCE, File(context.getPluginDataDirectory(), MAC_RESOURCE), executable = true
                )
                Ok(MacSpellCheckerBackend(RealProcessRunner(), helper))
            }
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true) ->
                LinuxSpellCheckerBackend.discover(PathExecutableLocator, RealProcessRunner())
            else -> Err(ServiceError.ServiceUnavailableError("System spell checking is unsupported on this platform."))
        }
}
