package com.github.ahatem.qtranslate.plugins.lens

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleLensProtoTest {

    /**
     * Builds a valid synthetic binary Protobuf response adhering to the Google Lens
     * `crupload` response schema.
     */
    private fun buildRecordedResponseFixture(
        lines: List<List<Pair<String, String>>>,
        language: String?
    ): ByteArray {
        val textLayoutStream = ByteArrayOutputStream()

        for (lineWords in lines) {
            val lineStream = ByteArrayOutputStream()
            for ((word, separator) in lineWords) {
                val wordStream = ByteArrayOutputStream()
                writeLengthDelimited(wordStream, 2, word.toByteArray(StandardCharsets.UTF_8))
                writeLengthDelimited(wordStream, 3, separator.toByteArray(StandardCharsets.UTF_8))
                writeLengthDelimited(lineStream, 1, wordStream.toByteArray())
            }

            val paragraphStream = ByteArrayOutputStream()
            writeLengthDelimited(paragraphStream, 2, lineStream.toByteArray()) // lines = field 2
            writeLengthDelimited(textLayoutStream, 1, paragraphStream.toByteArray()) // paragraphs = field 1
        }

        val textStream = ByteArrayOutputStream()
        writeLengthDelimited(textStream, 1, textLayoutStream.toByteArray()) // text_layout = field 1
        if (language != null) {
            writeLengthDelimited(textStream, 2, language.toByteArray(StandardCharsets.UTF_8)) // content_language = field 2
        }

        val objectsResponseStream = ByteArrayOutputStream()
        writeLengthDelimited(objectsResponseStream, 3, textStream.toByteArray()) // text = field 3

        val rootStream = ByteArrayOutputStream()
        writeLengthDelimited(rootStream, 2, objectsResponseStream.toByteArray()) // objects_response = field 2

        return rootStream.toByteArray()
    }

    private fun writeLengthDelimited(stream: ByteArrayOutputStream, fieldNumber: Int, bytes: ByteArray) {
        val tag = (fieldNumber shl 3) or 2
        writeVarint(stream, tag)
        writeVarint(stream, bytes.size)
        stream.write(bytes)
    }

    private fun writeVarint(stream: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            stream.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        stream.write(v and 0x7F)
    }

    @Test
    fun `buildRequest encodes valid protobuf with dimensions and payload`() {
        val testImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) // PNG header
        val encoded = GoogleLensProto.buildRequest(
            imageBytes = testImage,
            width = 800,
            height = 600,
            language = "es"
        )

        assertTrue(encoded.isNotEmpty())
        val rootFields = GoogleLensProto.parseRawFields(encoded)
        // Root has field 1: objects_request
        val objectsRequestField = rootFields.find { it.fieldNumber == 1 && it.wireType == 2 }
        assertNotNull(objectsRequestField, "Root must contain field 1 (objects_request)")

        val objFields = GoogleLensProto.parseRawFields(objectsRequestField.bytesValue)
        val imageDataField = objFields.find { it.fieldNumber == 3 && it.wireType == 2 }
        assertNotNull(imageDataField, "objects_request must contain field 3 (image_data)")

        val imageDataSubFields = GoogleLensProto.parseRawFields(imageDataField.bytesValue)
        val payloadField = imageDataSubFields.find { it.fieldNumber == 1 && it.wireType == 2 }
        assertNotNull(payloadField, "image_data must contain field 1 (payload)")

        val innerPayloadFields = GoogleLensProto.parseRawFields(payloadField.bytesValue)
        val rawImageField = innerPayloadFields.find { it.fieldNumber == 1 && it.wireType == 2 }
        assertNotNull(rawImageField, "payload must contain field 1 with image bytes")
        assertTrue(rawImageField.bytesValue.contentEquals(testImage), "Payload must match original image bytes")
    }

    @Test
    fun `parseResponse decodes multi-line recorded response with language`() {
        val fixture = buildRecordedResponseFixture(
            lines = listOf(
                listOf("Hello" to " ", "World" to ""),
                listOf("Google" to " ", "Lens" to " ", "OCR" to "")
            ),
            language = "en"
        )

        val result = GoogleLensProto.parseResponse(fixture)
        assertEquals("Hello World\nGoogle Lens OCR", result.text)
        assertEquals("en", result.language)
    }

    @Test
    fun `parseResponse returns empty result for empty byte array`() {
        val result = GoogleLensProto.parseResponse(ByteArray(0))
        assertEquals("", result.text)
        assertNull(result.language)
    }

    @Test
    fun `parseResponse handles empty text object cleanly`() {
        // Construct response with objects_response but empty text
        val textStream = ByteArrayOutputStream()
        val objectsResponseStream = ByteArrayOutputStream()
        writeLengthDelimited(objectsResponseStream, 3, textStream.toByteArray())
        val rootStream = ByteArrayOutputStream()
        writeLengthDelimited(rootStream, 2, objectsResponseStream.toByteArray())

        val result = GoogleLensProto.parseResponse(rootStream.toByteArray())
        assertEquals("", result.text)
        assertNull(result.language)
    }

    @Test
    fun `parseResponse handles truncated and corrupted responses gracefully without throwing`() {
        val validFixture = buildRecordedResponseFixture(
            lines = listOf(listOf("Sample" to " ", "text" to "")),
            language = "fr"
        )

        // Truncate at several byte boundaries: must never throw an unhandled exception
        val truncationPoints = listOf(1, 2, 5, 10, 15, validFixture.size / 2, validFixture.size - 1)
        for (cutoff in truncationPoints) {
            val truncated = validFixture.copyOfRange(0, cutoff)
            val result = GoogleLensProto.parseResponse(truncated)
            assertNotNull(result) // safely returns LensOCRResult
        }

        // Corrupted arbitrary bytes: must fail closed cleanly
        val garbageBytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x12, 0x34)
        val garbageResult = GoogleLensProto.parseResponse(garbageBytes)
        assertEquals("", garbageResult.text)
        assertNull(garbageResult.language)
    }

    @Test
    fun `schema change test fails informatively when field numbers deviate`() {
        // If the server changes the root field from 2 to 99, parser yields empty result
        val objectsResponseStream = ByteArrayOutputStream()
        val textStream = ByteArrayOutputStream()
        writeLengthDelimited(textStream, 2, "es".toByteArray(StandardCharsets.UTF_8))
        writeLengthDelimited(objectsResponseStream, 3, textStream.toByteArray())

        val changedRootStream = ByteArrayOutputStream()
        writeLengthDelimited(changedRootStream, 99, objectsResponseStream.toByteArray()) // Changed tag 99

        val result = GoogleLensProto.parseResponse(changedRootStream.toByteArray())
        assertEquals("", result.text, "Parser must safely reject unrecognised schema layout")
        assertNull(result.language)
    }
}
