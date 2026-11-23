package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The store decides what is copied; the UI owns the clipboard. These assert the emitted events. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainStoreAutoCopyTest {

    @Test
    fun `an eligible main translation emits one copy event carrying the result`() = runTest {
        val fixture = fixture(AutoCopyTranslation.ALL)
        try {
            val copied = fixture.copiedAfter {
                fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
                fixture.store.dispatch(MainIntent.Translate())
            }

            assertEquals("t:hello", copied)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a quick translation emits one copy event`() = runTest {
        val fixture = fixture(AutoCopyTranslation.QUICK_TRANSLATE_ONLY)
        try {
            val copied = fixture.copiedAfter {
                fixture.store.dispatch(MainIntent.ShowQuickTranslate("hello"))
            }

            assertEquals("t:hello", copied)
        } finally {
            fixture.cleanUp()
        }
    }

    private class NoCopyCase(
        val label: String,
        val autoCopy: AutoCopyTranslation = AutoCopyTranslation.ALL,
        val failing: Boolean = false,
        val blank: Boolean = false,
        val instant: Boolean = false,
        val act: suspend TestScope.(MainStoreFixture) -> Unit,
    )

    @Test
    fun `nothing is copied for work that is not a delivered result`() = runTest {
        val cases = listOf(
            NoCopyCase("an internal refresh has no translated result to hand over") { fixture ->
                fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
                fixture.store.dispatch(MainIntent.RefreshExtraOutput(null))
            },
            NoCopyCase("a failed translation", failing = true) { fixture ->
                fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
                fixture.store.dispatch(MainIntent.Translate())
            },
            NoCopyCase("a blank result", blank = true) { fixture ->
                fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
                fixture.store.dispatch(MainIntent.Translate())
            },
            NoCopyCase("instant translation, even under ALL", instant = true) { fixture ->
                fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            },
            NoCopyCase("the setting off", autoCopy = AutoCopyTranslation.OFF) { fixture ->
                fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
                fixture.store.dispatch(MainIntent.Translate())
            },
            NoCopyCase("replacing the selection pastes instead of copying") { fixture ->
                fixture.pastedAfter { fixture.store.dispatch(MainIntent.ReplaceWithTranslation("hello")) }
            },
        )

        for (case in cases) {
            val fixture = fixture(case.autoCopy, case.failing, case.blank, case.instant)
            try {
                case.act(this, fixture)
                advanceUntilIdle()

                assertEquals(emptyList(), fixture.recordedCopies(), case.label)
            } finally {
                fixture.cleanUp()
            }
        }
    }

    private fun TestScope.fixture(
        autoCopy: AutoCopyTranslation,
        failing: Boolean = false,
        blank: Boolean = false,
        instant: Boolean = false,
    ): MainStoreFixture {
        val translator = EchoTranslator(failing, blank)
        return MainStoreFixture(
            services = mapOf(translator.key to translator),
            configuration = MainStoreFixture.configuration(mapOf(ServiceRole.TRANSLATOR to translator.key)) {
                copy(autoCopyTranslation = autoCopy, isInstantTranslationEnabled = instant)
            },
            testScope = this,
        )
    }

    private class EchoTranslator(
        private val failing: Boolean,
        private val blank: Boolean,
    ) : Translator {
        override val key = "t"
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            when {
                failing -> Err(ServiceError.NetworkError("down"))
                blank -> Ok(TranslationResponse("   "))
                else -> Ok(TranslationResponse("$key:${request.text}"))
            }
    }
}