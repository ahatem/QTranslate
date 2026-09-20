package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.plugins.systemocr.corruptImageData
import com.github.ahatem.qtranslate.plugins.systemocr.imageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.unwrap
import com.github.ahatem.qtranslate.plugins.systemocr.unwrapError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcrImageTest {

    @Test
    fun `passes an image that already fits through untouched`() {
        val image = imageData(width = 100, height = 50)

        val prepared = OcrImage.prepare(image, maxDimension = 200).unwrap()

        assertContentEquals(image.bytes, prepared.bytes)
        assertEquals("png", prepared.format)
        assertEquals(100, prepared.width)
        assertEquals(50, prepared.height)
    }

    @Test
    fun `a null maximum leaves the image untouched`() {
        val image = imageData(width = 5_000, height = 2_000)

        val prepared = OcrImage.prepare(image, maxDimension = null).unwrap()

        assertContentEquals(image.bytes, prepared.bytes)
        assertEquals(5_000, prepared.width)
    }

    @Test
    fun `scales an oversized image down preserving its aspect ratio`() {
        val prepared = OcrImage.prepare(imageData(width = 400, height = 200), maxDimension = 100).unwrap()

        assertEquals(100, prepared.width)
        assertEquals(50, prepared.height)
        assertEquals("png", prepared.format)
    }

    @Test
    fun `scales by the longest edge and rounds deterministically`() {
        val prepared = OcrImage.prepare(imageData(width = 1_000, height = 333), maxDimension = 100).unwrap()

        assertEquals(100, prepared.width)
        assertEquals(33, prepared.height)
    }

    @Test
    fun `rejects an image with no data`() {
        val empty = imageData().let { it.copy(bytes = ByteArray(0)) }

        assertTrue(OcrImage.prepare(empty, maxDimension = null).unwrapError() is ServiceError.InvalidInputError)
    }

    @Test
    fun `rejects non-positive dimensions`() {
        val zero = imageData().let { it.copy(width = 0) }

        assertTrue(OcrImage.prepare(zero, maxDimension = null).unwrapError() is ServiceError.InvalidInputError)
    }

    @Test
    fun `rejects a corrupt image`() {
        assertTrue(OcrImage.prepare(corruptImageData(), maxDimension = null).unwrapError() is ServiceError.InvalidInputError)
    }
}
