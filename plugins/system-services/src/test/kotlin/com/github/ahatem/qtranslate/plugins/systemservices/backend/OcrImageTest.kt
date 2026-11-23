package com.github.ahatem.qtranslate.plugins.systemservices.backend

import com.github.ahatem.qtranslate.plugins.systemservices.corruptImageData
import com.github.ahatem.qtranslate.plugins.systemservices.imageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.unwrap
import com.github.ahatem.qtranslate.plugins.systemservices.unwrapError
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
    fun `scales an oversized image down by its longest edge, preserving the aspect ratio`() {
        // (width, height, max dimension) -> expected (width, height)
        val cases = listOf(
            Triple(400 to 200, 100, 100 to 50),
            Triple(1_000 to 333, 100, 100 to 33),
        )
        for ((size, max, expected) in cases) {
            val prepared = OcrImage.prepare(imageData(width = size.first, height = size.second), maxDimension = max).unwrap()

            assertEquals(expected, prepared.width to prepared.height, "scaling $size to $max")
            assertEquals("png", prepared.format)
        }
    }

    @Test
    fun `rejects an image with no data, no size or corrupt bytes`() {
        val rejected = mapOf(
            "no data" to imageData().copy(bytes = ByteArray(0)),
            "non-positive dimensions" to imageData().copy(width = 0),
            "corrupt bytes" to corruptImageData(),
        )
        for ((label, image) in rejected) {
            assertTrue(OcrImage.prepare(image, maxDimension = null).unwrapError() is ServiceError.InvalidInputError, label)
        }
    }
}
