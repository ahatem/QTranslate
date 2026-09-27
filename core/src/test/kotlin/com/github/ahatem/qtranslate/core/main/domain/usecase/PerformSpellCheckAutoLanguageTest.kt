package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckResponse
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerformSpellCheckAutoLanguageTest {
    private class FakeChecker(override val supportedLanguages: SupportedLanguages) : SpellChecker {
        override val key = "fake-spell"
        override val name = "Fake spell checker"
        override val version = "1"
        val requests = mutableListOf<SpellCheckRequest>()
        var fetches = 0
        var dynamicLanguages = emptySet<LanguageCode>()

        override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
            fetches++
            return Ok(dynamicLanguages)
        }

        override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> {
            requests += request
            return Ok(SpellCheckResponse(request.text, emptyList()))
        }
    }

    private class Harness(checker: FakeChecker) {
        val statuses = mutableListOf<StatusCode>()
        val useCase = PerformSpellCheckUseCase(
            ActiveServiceManager(
                MutableStateFlow(mapOf<String, Service>("fake:default:fake-spell" to checker)),
                MutableStateFlow(Configuration.DEFAULT),
            ),
            object : LoggerFactory {
                override fun getLogger(name: String) = object : Logger {
                    override fun debug(message: String) = Unit
                    override fun info(message: String) = Unit
                    override fun warn(message: String) = Unit
                    override fun error(message: String, error: Throwable?) = Unit
                }
            },
        )

        suspend fun check(source: LanguageCode, detected: LanguageCode? = null) = useCase(
            MainState(sourceLanguage = source, detectedSourceLanguage = detected),
            "Ths text",
        ) { code: StatusCode, _: NotificationType, _: Boolean -> statuses += code }
    }

    @Test fun `explicit source languages are passed through`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH)))
        val harness = Harness(checker)
        harness.check(LanguageCode.ENGLISH)
        harness.check(LanguageCode.ARABIC)
        assertEquals(listOf(LanguageCode.ENGLISH, LanguageCode.ARABIC), checker.requests.map { it.language })
    }

    @Test fun `native AUTO support wins before and after translation detection`() = runTest {
        for (support in listOf(SupportedLanguages.All, SupportedLanguages.Specific(setOf(LanguageCode.AUTO, LanguageCode.ENGLISH)))) {
            val checker = FakeChecker(support)
            val harness = Harness(checker)
            harness.check(LanguageCode.AUTO)
            harness.check(LanguageCode.AUTO, LanguageCode.ARABIC)
            assertEquals(listOf(LanguageCode.AUTO, LanguageCode.AUTO), checker.requests.map { it.language })
        }
    }

    @Test fun `non AUTO checker uses detected English and Arabic`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH, LanguageCode.ARABIC)))
        val harness = Harness(checker)
        harness.check(LanguageCode.AUTO, LanguageCode.ENGLISH)
        harness.check(LanguageCode.AUTO, LanguageCode.ARABIC)
        assertEquals(listOf(LanguageCode.ENGLISH, LanguageCode.ARABIC), checker.requests.map { it.language })
    }

    @Test fun `missing or unsupported detection skips without a warning`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH)))
        val harness = Harness(checker)
        assertTrue(harness.check(LanguageCode.AUTO).isEmpty())
        assertTrue(harness.check(LanguageCode.AUTO, LanguageCode.JAPANESE).isEmpty())
        assertTrue(checker.requests.isEmpty())
        assertTrue(harness.statuses.isEmpty())
    }

    @Test fun `dynamic capability preserves true AUTO and is fetched once`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Dynamic).apply {
            dynamicLanguages = setOf(LanguageCode.AUTO, LanguageCode.ENGLISH)
        }
        val harness = Harness(checker)
        harness.check(LanguageCode.AUTO)
        harness.check(LanguageCode.AUTO, LanguageCode.ENGLISH)
        assertEquals(1, checker.fetches)
        assertEquals(listOf(LanguageCode.AUTO, LanguageCode.AUTO), checker.requests.map { it.language })
    }
}
