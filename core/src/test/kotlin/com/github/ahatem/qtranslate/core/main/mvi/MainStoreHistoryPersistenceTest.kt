package com.github.ahatem.qtranslate.core.main.mvi

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainStoreHistoryPersistenceTest {

    @Test
    fun `failed history clear keeps persisted and visible entries`() = runTest {
        val entry = HistorySnapshot("input", "output", "en", "fr", "translator", 1L)
        val data = FailingHistoryStore(listOf(entry))
        val fixture = fixture(data)
        try {
            advanceUntilIdle()
            assertEquals(listOf(entry), fixture.store.state.value.history)

            fixture.store.dispatch(MainIntent.ClearHistory)
            advanceUntilIdle()

            assertEquals(listOf(entry), fixture.store.state.value.history)
            assertEquals(
                listOf(entry),
                Json.decodeFromString(data.preferences[stringPreferencesKey("translation_history_json")]!!),
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `history write failure does not fail a successful translation`() = runTest {
        val fixture = fixture(FailingHistoryStore(emptyList()))
        try {
            advanceUntilIdle()
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            fixture.store.dispatch(MainIntent.Translate())
            advanceUntilIdle()

            assertEquals("t:hello", fixture.store.state.value.translatedText)
            assertFalse(fixture.store.state.value.translationFailed)
            assertTrue(fixture.store.state.value.history.isNotEmpty())
        } finally {
            fixture.cleanUp()
        }
    }

    private fun TestScope.fixture(historyData: DataStore<Preferences>): MainStoreFixture {
        val translator = EchoTranslator()
        return MainStoreFixture(
            services = mapOf(translator.key to translator),
            configuration = MainStoreFixture.configuration(mapOf(ServiceRole.TRANSLATOR to translator.key)),
            testScope = this,
            historyData = historyData,
        )
    }

    private class FailingHistoryStore(entries: List<HistorySnapshot>) : DataStore<Preferences> {
        val preferences: Preferences =
            mutablePreferencesOf(stringPreferencesKey("translation_history_json") to Json.encodeToString(entries))
        override val data: Flow<Preferences> = MutableStateFlow(preferences)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("disk unavailable")
    }

    private class EchoTranslator : Translator {
        override val key = "t"
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            Ok(TranslationResponse("$key:${request.text}"))
    }
}