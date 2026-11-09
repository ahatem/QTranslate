package com.github.ahatem.qtranslate.api.ocr

import kotlin.test.Test
import kotlin.test.assertFailsWith

class StructuredOcrContractTest {

    @Test
    fun `a region rejects an inverted rectangle`() {
        assertFailsWith<IllegalArgumentException> {
            OCRTextRegion("text", left = 10, top = 0, right = 5, bottom = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            OCRTextRegion("text", left = 0, top = 10, right = 4, bottom = 5)
        }
    }

    @Test
    fun `a region rejects confidence outside the normalized range`() {
        assertFailsWith<IllegalArgumentException> {
            OCRTextRegion("text", 0, 0, 1, 1, confidence = 1.5f)
        }
        assertFailsWith<IllegalArgumentException> {
            OCRTextRegion("text", 0, 0, 1, 1, confidence = -0.1f)
        }
    }

    @Test
    fun `a structured response rejects confidence outside the normalized range`() {
        assertFailsWith<IllegalArgumentException> {
            StructuredOCRResponse("text", confidence = 2f)
        }
    }

    @Test
    fun `text-only providers can return no regions`() {
        val response = StructuredOCRResponse(text = "plain")
        assert(response.regions.isEmpty())
    }
}
