package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.Correction
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
import java.util.Locale
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
        var corrections = emptyList<Correction>()

        override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
            fetches++
            return Ok(dynamicLanguages)
        }

        override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> {
            requests += request
            return Ok(SpellCheckResponse(request.text, corrections))
        }
    }

    private class Harness(checker: FakeChecker, locale: Locale = Locale.forLanguageTag("fr-FR")) {
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
            userLocale = { locale },
        )

        suspend fun check(source: LanguageCode, detected: LanguageCode? = null) = check(
            MainState(sourceLanguage = source, detectedSourceLanguage = detected),
        )

        suspend fun check(state: MainState) = useCase(
            state,
            "Ths text",
        ) { code: StatusCode, _: NotificationType, _: Boolean -> statuses += code }
    }

    @Test fun `explicit source languages are passed through`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH)))
        val harness = Harness(checker, Locale.forLanguageTag("ja-JP"))
        harness.check(LanguageCode.ENGLISH)
        harness.check(LanguageCode.ARABIC)
        assertEquals(listOf(LanguageCode.ENGLISH, LanguageCode.ARABIC), checker.requests.map { it.language })
    }

    @Test fun `native AUTO support wins before and after translation detection`() = runTest {
        for (support in listOf(SupportedLanguages.All, SupportedLanguages.Specific(setOf(LanguageCode.AUTO, LanguageCode.ENGLISH)))) {
            val checker = FakeChecker(support)
            val harness = Harness(checker, Locale.forLanguageTag("ja-JP"))
            harness.check(LanguageCode.AUTO)
            harness.check(LanguageCode.AUTO, LanguageCode.ARABIC)
            assertEquals(listOf(LanguageCode.AUTO, LanguageCode.AUTO), checker.requests.map { it.language })
        }
    }

    @Test fun `non AUTO checker uses detected English and Arabic`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH, LanguageCode.ARABIC)))
        val harness = Harness(checker, Locale.forLanguageTag("ja-JP"))
        harness.check(LanguageCode.AUTO, LanguageCode.ENGLISH)
        harness.check(LanguageCode.AUTO, LanguageCode.ARABIC)
        assertEquals(listOf(LanguageCode.ENGLISH, LanguageCode.ARABIC), checker.requests.map { it.language })
    }

    @Test fun `unsupported locale or detected language skips without a warning`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH)))
        val harness = Harness(checker)
        assertTrue(harness.check(LanguageCode.AUTO).isEmpty())
        assertTrue(harness.check(LanguageCode.AUTO, LanguageCode.JAPANESE).isEmpty())
        assertTrue(checker.requests.isEmpty())
        assertTrue(harness.statuses.isEmpty())
    }

    @Test fun `exact system locale outranks its supported base language`() = runTest {
        val finding = Correction("Ths", 0, 3, listOf("This"))
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH, LanguageCode("en-US"))))
            .apply { corrections = listOf(finding) }
        val state = MainState(sourceLanguage = LanguageCode.AUTO)
        assertEquals(listOf(finding), Harness(checker, Locale.forLanguageTag("en-US")).check(state))
        assertEquals(listOf(LanguageCode("en-US")), checker.requests.map { it.language })
        assertEquals(null, state.detectedSourceLanguage)
    }

    @Test fun `regional system locale uses advertised base language`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode.ARABIC)))
        Harness(checker, Locale.forLanguageTag("ar-EG")).check(LanguageCode.AUTO)
        assertEquals(listOf(LanguageCode.ARABIC), checker.requests.map { it.language })
    }

    @Test fun `system locale never selects a different region without a supported base`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode("en-GB"))))
        val harness = Harness(checker, Locale.forLanguageTag("en-US"))
        assertTrue(harness.check(LanguageCode.AUTO).isEmpty())
        assertTrue(checker.requests.isEmpty())
        assertTrue(harness.statuses.isEmpty())
    }

    @Test fun `real detection replaces system locale fallback without becoming sticky`() = runTest {
        val checker = FakeChecker(SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH, LanguageCode.ARABIC)))
        val harness = Harness(checker, Locale.forLanguageTag("en-US"))
        harness.check(LanguageCode.AUTO)
        harness.check(LanguageCode.AUTO, LanguageCode.ARABIC)
        assertEquals(listOf(LanguageCode.ENGLISH, LanguageCode.ARABIC), checker.requests.map { it.language })
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
