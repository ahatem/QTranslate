package com.github.ahatem.qtranslate.core.localization

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A shipped file is only a translation once the manager can discover it by filename and read the
 * metadata the interface-language picker uses. The picker has no separate registry to update.
 */
class LanguageDiscoveryTest {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "languages").isDirectory && File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    @Test
    fun `every shipped language file is discovered with selector metadata`() = runTest {
        val appData = Files.createTempDirectory("qtranslate-language-discovery").toFile()
        val manager = LocalizationManager(
            appDataDirectory = appData,
            parser = LanguageTomlParser(),
            logger = logger,
            installationRoot = repoRoot,
        )

        val expected = File(repoRoot, "languages")
            .listFiles { file -> file.extension == "toml" }!!
            .map { file -> file.nameWithoutExtension }
            .sorted()

        assertEquals(expected, manager.availableLanguages)

        expected.forEach { code ->
            val meta = assertNotNull(
                manager.readLanguageMeta(LanguageCode(code)),
                "$code.toml has no readable [meta] section",
            )
            assertEquals(code, meta.locale, "$code.toml declares a different locale")
            assertTrue(meta.name.isNotBlank(), "$code.toml has no English language name")
            assertTrue(meta.nativeName.isNotBlank(), "$code.toml has no native language name")
        }

        val czech = assertNotNull(manager.readLanguageMeta(LanguageCode("cs-CZ")))
        assertEquals("Czech", czech.name)
        assertEquals("Čeština", czech.nativeName)
        assertEquals("cs-CZ", czech.locale)
        assertEquals(listOf("KacerV1"), czech.translators)
        assertFalse(czech.isRtl)
    }
}
