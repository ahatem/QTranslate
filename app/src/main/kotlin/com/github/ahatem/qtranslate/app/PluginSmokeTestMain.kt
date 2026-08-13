package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.michaelbull.result.fold
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File

private data class SmokeResult(
    val pluginId: String,
    val check: String,
    val passed: Boolean,
    val detail: String
)

/** Entry point used by Gradle's smokeTestAllPlugins verification task. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 2) { "Expected app-data and report paths." }
    val appData = File(args[0]).also(File::mkdirs)
    val reportFile = File(args[1])
    val loggerFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.INFO)
    val settingsRepository = SettingsRepository(
        appData,
        Json { ignoreUnknownKeys = true; isLenient = true },
        loggerFactory.getLogger("SmokeSettings")
    )
    val configuration = Configuration.DEFAULT.copy(autoCheckForUpdates = false)
    settingsRepository.updateConfiguration(configuration)
    val dependencies = buildDependencies(appData, loggerFactory, settingsRepository, configuration)
    val results = mutableListOf<SmokeResult>()

    try {
        dependencies.pluginManager.loadAndProcessPlugins()
        val initialPlugins = dependencies.pluginManager.plugins.value
        val expectedJarCount = File(appData, "plugins").listFiles { file -> file.extension == "jar" }?.size ?: 0
        results += SmokeResult(
            "runtime",
            "discovery",
            initialPlugins.size == expectedJarCount && initialPlugins.isNotEmpty(),
            "loaded ${initialPlugins.size} of $expectedJarCount plugin JARs"
        )

        initialPlugins.forEach { plugin ->
            results += SmokeResult(
                plugin.id,
                "initialize",
                plugin.status == PluginStatus.ENABLED,
                plugin.lastError?.message ?: plugin.status.name
            )

            val settingsCheck = runCatching {
                val instance = dependencies.pluginManager.getPluginSettingsInstance(plugin.id)
                val model = dependencies.pluginManager.getPluginSettingsModel(plugin.id)
                when {
                    instance == null && model == null -> "no configurable settings"
                    instance != null && model != null -> "${model.schema.size} fields in ${model.groups.size} groups"
                    else -> error("settings instance/schema mismatch")
                }
            }
            results += SmokeResult(
                plugin.id,
                "settings",
                settingsCheck.isSuccess,
                settingsCheck.fold({ it }, { it.message ?: it::class.java.simpleName })
            )

            val lifecycleCheck = runCatching {
                val serviceIds = plugin.services.map { it.id }.toSet()
                dependencies.pluginManager.disablePlugin(plugin.id)
                check(dependencies.pluginManager.plugins.value.single { it.id == plugin.id }.status == PluginStatus.DISABLED)
                check(serviceIds.none { it in dependencies.pluginManager.activeServices.value })
                dependencies.pluginManager.enablePlugin(plugin.id)
                check(dependencies.pluginManager.plugins.value.single { it.id == plugin.id }.status == PluginStatus.ENABLED)
                check(serviceIds.all { it in dependencies.pluginManager.activeServices.value })
                "disable/enable restored ${serviceIds.size} services"
            }
            results += SmokeResult(
                plugin.id,
                "lifecycle",
                lifecycleCheck.isSuccess,
                lifecycleCheck.fold({ it }, { it.message ?: it::class.java.simpleName })
            )
        }

        dependencies.pluginManager.activeServices.value.values
            .filterIsInstance<Translator>()
            .sortedBy { it.id }
            .forEach { translator ->
                val translationCheck = runCatching {
                    withTimeoutOrNull(35_000) {
                        translator.translate(
                            TranslationRequest(
                                text = "Hello",
                                sourceLanguage = LanguageCode("en"),
                                targetLanguage = LanguageCode("fr")
                            )
                        ).fold(
                            success = { response ->
                                check(response.translatedText.isNotBlank()) { "empty successful response" }
                                "translated successfully"
                            },
                            failure = { error ->
                                check(error.message.isNotBlank()) { "structured error has no message" }
                                "graceful ${error::class.simpleName}: ${error.message.lineSequence().first()}"
                            }
                        )
                    } ?: "graceful TimeoutError: smoke request exceeded 35 seconds"
                }
                results += SmokeResult(
                    translator.id.substringBeforeLast('-'),
                    "translate:${translator.id}",
                    translationCheck.isSuccess,
                    translationCheck.fold({ it }, { "threw ${it::class.java.simpleName}: ${it.message}" })
                )
            }
    } catch (error: Throwable) {
        results += SmokeResult("runtime", "uncaught", false, "${error::class.java.simpleName}: ${error.message}")
    } finally {
        runCatching { dependencies.pluginManager.shutdown() }
        runCatching { dependencies.mainStore.onShutdown() }
        dependencies.appScope.cancel()
    }

    val report = buildString {
        appendLine("QTranslate all-plugin smoke report")
        appendLine("==================================")
        results.forEach { result ->
            appendLine("${if (result.passed) "PASS" else "FAIL"}  ${result.pluginId}  ${result.check}  ${result.detail}")
        }
        appendLine("----------------------------------")
        appendLine("${results.count { it.passed }} passed, ${results.count { !it.passed }} failed")
    }
    reportFile.parentFile.mkdirs()
    reportFile.writeText(report)
    print(report)
    check(results.isNotEmpty() && results.all { it.passed }) {
        "Plugin smoke test failed. Report: ${reportFile.absolutePath}"
    }
}
