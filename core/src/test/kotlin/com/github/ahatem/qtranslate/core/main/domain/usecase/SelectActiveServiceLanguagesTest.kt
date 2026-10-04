package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectActiveServiceLanguagesTest {

    private class AllLanguagesTranslator : Translator {
        override val key = "all-languages"
        override val name = "All Languages"
        override val version = "1.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            error("unused")
    }

    private object TestLoggerFactory : LoggerFactory {
        private val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
        override fun getLogger(name: String): Logger = logger
    }

    @Test
    fun `SupportedLanguages All exposes regional Portuguese rather than generic duplicate`() = runTest {
        val translator = AllLanguagesTranslator()
        val services = MutableStateFlow<Map<String, Service>>(mapOf("svc" to translator))
        val config = MutableStateFlow(Configuration.DEFAULT.copy(
            servicePresets = listOf(
                com.github.ahatem.qtranslate.core.settings.data.ServicePreset(
                    id = "p1",
                    name = "p1",
                    selectedServices = mapOf(com.github.ahatem.qtranslate.api.plugin.ServiceRole.TRANSLATOR to "svc")
                )
            ),
            activeServicePresetId = "p1"
        ))
        val useCase = SelectActiveServiceUseCase(
            activeServices = services,
            settingsState = config,
            activeServiceManager = ActiveServiceManager(services, config),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            loggerFactory = TestLoggerFactory
        )

        val languages = useCase.observe().first().availableLanguages

        assertTrue(LanguageCode.PORTUGUESE_BRAZIL in languages)
        assertTrue(LanguageCode.PORTUGUESE_PORTUGAL in languages)
        assertFalse(LanguageCode.PORTUGUESE in languages)
    }
}
