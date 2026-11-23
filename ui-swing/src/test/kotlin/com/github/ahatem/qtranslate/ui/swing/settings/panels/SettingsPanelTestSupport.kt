package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.testing.SilentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.awt.Component
import java.awt.Container
import java.io.File
import java.nio.file.Files

/** A store, a localizer and a scope backed by temp directories; call [close] from `@AfterTest`. */
internal class SettingsPanelFixture {
    private val scopes = mutableListOf<CoroutineScope>()

    val localizer: LocalizationManager by lazy {
        LocalizationManager(
            File(Files.createTempDirectory("qtranslate-settings-languages").toFile(), "languages"),
            LanguageTomlParser(),
            SilentLogger,
        )
    }

    fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    /** A store seeded with [configuration] and persisted through a real repository in a temp directory. */
    fun store(configuration: Configuration = Configuration.DEFAULT): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-settings-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, SilentLogger)
        repository.updateConfiguration(configuration)
        SettingsStore(repository, SilentLogger, newScope(), configuration)
    }

    fun close() = scopes.forEach { it.cancel() }
}

internal fun descendants(root: Container): List<Component> =
    root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

/** The repository's shipped `languages` folder, found by walking up from the test's working directory. */
internal fun shippedLanguagesDirectory(): File {
    var directory: File? = File(System.getProperty("user.dir")).absoluteFile
    while (directory != null) {
        val candidate = File(directory, "languages")
        if (File(candidate, "en-GB.toml").isFile) return candidate
        directory = directory.parentFile
    }
    error("the languages folder was not found above ${System.getProperty("user.dir")}")
}

internal fun shippedLanguageTags(): List<String> =
    shippedLanguagesDirectory().listFiles { file -> file.extension == "toml" }
        .orEmpty()
        .map { it.nameWithoutExtension }
        .sorted()

/** A localizer with [tag] loaded from the shipped files, placed the way a distribution installs them. */
internal fun shippedLocalizerFor(tag: String): LocalizationManager {
    val appData = Files.createTempDirectory("qtranslate-shipped-l10n").toFile()
    val install = Files.createTempDirectory("qtranslate-shipped-l10n-install").toFile()
    File(install, "languages").mkdirs()
    shippedLanguagesDirectory().listFiles { file -> file.extension == "toml" }
        .orEmpty()
        .forEach { it.copyTo(File(install, "languages/${it.name}"), overwrite = true) }
    val manager = LocalizationManager(appData, LanguageTomlParser(), SilentLogger, install)
    runBlocking { manager.loadLanguage(LanguageCode(tag)) }
    return manager
}
