package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRRegionKind
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.ocr.StructuredOCRResponse
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.unwrap
import com.github.michaelbull.result.unwrapError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The Vision response below is the documented shape of `images:annotate`, trimmed to the fields the
 * service reads. The coordinates are small on purpose so every mapping is checkable by hand.
 */
class GoogleOCRServiceTest {

    private fun service(
        response: String = VISION_JSON,
        apiKey: String = "test-key",
    ) = GoogleOCRService(
        FakePluginContext(),
        GoogleSettings(visionApiKey = apiKey),
        FixedResponseClient(response),
        GoogleLanguageMapper,
        ApiConfig(),
    )

    private fun request(width: Int = 200, height: Int = 100) =
        OCRRequest(ImageData(bytes = byteArrayOf(1, 2, 3), format = "png", width = width, height = height))

    @Test
    fun `word boxes are reported in the caller's pixels`() = runBlocking {
        // Vision analyzed a 100x50 page; the caller holds the same image at 200x100, so every
        // coordinate in the fixture doubles on the way out.
        val result = service().extractStructuredText(request())

        val response = result.unwrap()

        assertEquals(2, response.regions.size)
        val first = response.regions[0]
        assertEquals("Hello", first.text)
        assertEquals(OCRRegionKind.WORD, first.kind)
        assertEquals(20, first.left)
        assertEquals(40, first.top)
        assertEquals(120, first.right)
        assertEquals(68, first.bottom)
        assertEquals(0.98f, first.confidence)

        val second = response.regions[1]
        assertEquals("world", second.text)
        assertEquals(128, second.left)
        assertEquals(40, second.top)
        // 208 in page space is clamped to the caller's 200-pixel width rather than sticking out.
        assertEquals(200, second.right)
        assertEquals(72, second.bottom)
    }

    @Test
    fun `boxes are scaled when Vision analyzed a smaller image`() = runBlocking {
        // The page reports 100x50 while the caller's image is 400x200: every box must scale by 4.
        val result = service().extractStructuredText(request(width = 400, height = 200))

        val response = result.unwrap()
        val first = response.regions.first()
        // Page is 100x50, caller is 400x200, so the scale is 4x in both directions.
        assertEquals(40, first.left)
        assertEquals(80, first.top)
        assertEquals(240, first.right)
        assertEquals(136, first.bottom)
    }

    @Test
    fun `a page with no size reports text without boxes`() = runBlocking {
        val noSize = VISION_JSON
            .replace("\"width\": 100,", "")
            .replace("\"height\": 50,", "")
        val result = service(noSize).extractStructuredText(request())

        val response = result.unwrap()
        assertEquals("Hello world", response.text)
        assertTrue(response.regions.isEmpty(), "no page size means no reliable mapping")
    }

    @Test
    fun `words without a bounding polygon are skipped`() = runBlocking {
        // The second word keeps its symbols but loses its polygon. It must be skipped rather than
        // given a guessed box: an invented highlight is worse than none.
        val noBox = VISION_JSON.replace(Regex(Regex.escape(SECOND_WORD_BOX)), "")
        assertTrue(noBox != VISION_JSON, "the fixture no longer contains the box being removed")

        val result = service(noBox).extractStructuredText(request())

        val response = result.unwrap()
        assertEquals(listOf("Hello"), response.regions.map { it.text })
    }

    @Test
    fun `plain extraction and structured extraction report the same text`() = runBlocking {
        val google = service()

        val plain = google.extractText(request()).unwrap()
        val structured = google.extractStructuredText(request()).unwrap()

        assertEquals(plain.text, structured.text)
        assertEquals(LanguageCode.ENGLISH, structured.detectedLanguage)
    }

    @Test
    fun `no annotation is an empty result rather than a failure`() = runBlocking {
        val result = service("""{"responses": [{}]}""").extractStructuredText(request())

        val response = result.unwrap()
        assertEquals("", response.text)
        assertTrue(response.regions.isEmpty())
        assertNull(response.detectedLanguage)
    }

    @Test
    fun `a missing key is reported as an authentication error`() = runBlocking {
        val result = service(apiKey = "").extractStructuredText(request())

        assertTrue(result.isErr, "a missing key must fail")
        assertIs<ServiceError.AuthenticationError>(result.unwrapError())
    }

    private companion object {
        const val VISION_JSON = """
{
  "responses": [
    {
      "fullTextAnnotation": {
        "text": "Hello world\n",
        "pages": [
          {
            "width": 100,
            "height": 50,
            "property": {
              "detectedLanguages": [
                {"languageCode": "en", "confidence": 0.97}
              ]
            },
            "blocks": [
              {
                "blockType": "TEXT",
                "confidence": 0.99,
                "boundingBox": {
                  "vertices": [
                    {"x": 10, "y": 20}, {"x": 104, "y": 20},
                    {"x": 104, "y": 36}, {"x": 10, "y": 36}
                  ]
                },
                "paragraphs": [
                  {
                    "confidence": 0.98,
                    "words": [
                      {
                        "confidence": 0.98,
                        "boundingBox": {
                          "vertices": [
                            {"x": 10, "y": 20},
                            {"x": 60, "y": 20},
                            {"x": 60, "y": 34},
                            {"x": 10, "y": 34}
                          ]
                        },
                        "symbols": [
                          {"text": "H"}, {"text": "e"}, {"text": "l"}, {"text": "l"}, {"text": "o"}
                        ]
                      },
                      {
                        "confidence": 0.95,
                        "boundingBox": {
                          "vertices": [
                            {"x": 64, "y": 20},
                            {"x": 104, "y": 20},
                            {"x": 104, "y": 36},
                            {"x": 64, "y": 36}
                          ]
                        },
                        "symbols": [
                          {"text": "w"}, {"text": "o"}, {"text": "r"}, {"text": "l"}, {"text": "d"}
                        ]
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    }
  ]
}
"""

        /** The second word's polygon, exactly as it appears in [VISION_JSON]. */
        const val SECOND_WORD_BOX = """,
                        "boundingBox": {
                          "vertices": [
                            {"x": 64, "y": 20},
                            {"x": 104, "y": 20},
                            {"x": 104, "y": 36},
                            {"x": 64, "y": 36}
                          ]
                        }"""
    }
}

/** Serves one canned body for the annotate call, so the parse and mapping are what is under test. */
private class FixedResponseClient(private val body: String) : TextHttpClient() {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
    ): Result<String, ServiceError> = error("Vision annotates with a POST, not a GET")

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>,
    ): Result<String, ServiceError> = Ok(this@FixedResponseClient.body)
}
