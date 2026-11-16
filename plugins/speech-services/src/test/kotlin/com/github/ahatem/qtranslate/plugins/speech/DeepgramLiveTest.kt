package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.michaelbull.result.getError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeepgramLiveTest {

    @Test
    fun `parse interim and final deepgram messages`() {
        val partialJson = """
            {
              "type": "Results",
              "is_final": false,
              "channel": {
                "alternatives": [
                  {
                    "transcript": "good morning",
                    "confidence": 0.95
                  }
                ]
              }
            }
        """.trimIndent()
        val partial = parseDeepgramMessage(partialJson)
        assertTrue(partial is DeepgramParsedMessage.Partial)
        assertEquals("good morning", partial.text)

        val finalJson = """
            {
              "type": "Results",
              "is_final": true,
              "channel": {
                "alternatives": [
                  {
                    "transcript": "good morning everyone",
                    "words": [
                      {"word": "good", "start": 0.1, "end": 0.4, "confidence": 0.98},
                      {"word": "morning", "start": 0.5, "end": 0.9, "confidence": 0.42},
                      {"word": "everyone", "start": 1.0, "end": 1.4}
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val finalResult = parseDeepgramMessage(finalJson)
        assertTrue(finalResult is DeepgramParsedMessage.Final)
        assertEquals("good morning everyone", finalResult.text)
        assertEquals(3, finalResult.words.size)
        assertEquals("good", finalResult.words[0].text)
        assertEquals(100L, finalResult.words[0].startMillis)
        assertEquals(400L, finalResult.words[0].endMillis)
        assertEquals(0.98, finalResult.words[0].confidence)
        assertEquals(0.42, finalResult.words[1].confidence)
        assertEquals(null, finalResult.words[2].confidence)
        assertEquals(1400L, finalResult.words[2].endMillis)
    }

    @Test
    fun `parse deepgram error message`() {
        val errorJson = """{"type":"Error","description":"Invalid credentials"}"""
        val error = parseDeepgramMessage(errorJson)
        assertTrue(error is DeepgramParsedMessage.Error)
        assertEquals("Invalid credentials", error.message)
    }

    @Test
    fun `deepgram live service requires api key`() = runBlocking {
        val service = DeepgramLiveService {
            SpeechSettings(apiKey = "", liveApiKey = "")
        }
        val err = service.openSession(LanguageCode.ENGLISH).getError()
        assertNotNull(err)
        assertTrue(err.message.contains("API key"))

        val validateErr = service.validate().getError()
        assertNotNull(validateErr)
        assertTrue(validateErr.message.contains("API key"))
    }

    @Test
    fun `query builder includes model and multi for auto language`() {
        val autoQuery = buildDeepgramQuery("auto", "nova-3")
        assertTrue(autoQuery.contains("model=nova-3"))
        assertTrue(autoQuery.contains("language=multi"))
        assertFalse(autoQuery.contains("detect_language=true"))

        val explicitQuery = buildDeepgramQuery("ar", "nova-2-general")
        assertTrue(explicitQuery.contains("model=nova-2-general"))
        assertTrue(explicitQuery.contains("language=ar"))
    }

    @Test
    fun `deepgram live warns when multi language is unsupported by model`() = runBlocking {
        val service = DeepgramLiveService {
            SpeechSettings(apiKey = "key", liveApiKey = "key", liveModel = "enhanced")
        }
        val err = service.openSession(LanguageCode.AUTO).getError()
        assertNotNull(err)
        assertTrue(err.message.contains("enhanced") && err.message.contains("multi"))
    }
}
