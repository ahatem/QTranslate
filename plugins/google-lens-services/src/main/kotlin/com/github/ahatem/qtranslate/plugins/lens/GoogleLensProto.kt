package com.github.ahatem.qtranslate.plugins.lens

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom

data class LensOCRResult(
    val text: String,
    val language: String?
)

object GoogleLensProto {

    fun buildRequest(
        imageBytes: ByteArray,
        width: Int,
        height: Int,
        language: String
    ): ByteArray {
        // Build requestId
        val requestId = ByteArrayOutputStream().apply {
            writeVarint(1, 0, ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE))
            writeVarint(2, 0, 1L)
            writeVarint(3, 0, 1L)
        }.toByteArray()

        // Build localeContext
        val localeContext = ByteArrayOutputStream().apply {
            writeLengthDelimited(1, language.toByteArray(StandardCharsets.UTF_8))
            writeLengthDelimited(2, "US".toByteArray(StandardCharsets.UTF_8))
            writeLengthDelimited(3, "America/New_York".toByteArray(StandardCharsets.UTF_8))
        }.toByteArray()

        // Build clientFilters
        val filter = ByteArrayOutputStream().apply {
            writeVarint(1, 0, 1L) // AUTO_FILTER = 1
        }.toByteArray()
        val clientFilters = ByteArrayOutputStream().apply {
            writeLengthDelimited(1, filter)
        }.toByteArray()

        // Build renderingContext
        val renderingContext = ByteArrayOutputStream().apply {
            writeVarint(2, 0, 1L) // RENDERING_ENV_LENS_OVERLAY = 1
        }.toByteArray()

        // Build clientContext
        val clientContext = ByteArrayOutputStream().apply {
            writeVarint(1, 0, 1L) // PLATFORM_WEB = 1
            writeVarint(2, 0, 1L) // SURFACE_CHROMIUM = 1
            writeLengthDelimited(4, localeContext)
            writeLengthDelimited(17, clientFilters)
            writeLengthDelimited(20, renderingContext)
        }.toByteArray()

        // Build requestContext
        val requestContext = ByteArrayOutputStream().apply {
            writeLengthDelimited(3, requestId)
            writeLengthDelimited(4, clientContext)
        }.toByteArray()

        // Build payload
        val payload = ByteArrayOutputStream().apply {
            writeLengthDelimited(1, imageBytes)
        }.toByteArray()

        // Build imageMetadata
        val imageMetadata = ByteArrayOutputStream().apply {
            writeVarint(1, 0, width.toLong())
            writeVarint(2, 0, height.toLong())
        }.toByteArray()

        // Build imageData
        val imageData = ByteArrayOutputStream().apply {
            writeLengthDelimited(1, payload)
            writeLengthDelimited(3, imageMetadata)
        }.toByteArray()

        // Build objectsRequest
        val objectsRequest = ByteArrayOutputStream().apply {
            writeLengthDelimited(1, requestContext)
            writeLengthDelimited(3, imageData)
        }.toByteArray()

        // Build serverRequest
        return ByteArrayOutputStream().apply {
            writeLengthDelimited(1, objectsRequest)
        }.toByteArray()
    }

    private data class RawField(val fieldNumber: Int, val wireType: Int, val varintValue: Long, val bytesValue: ByteArray)

    private fun parseRawFields(data: ByteArray): List<RawField> {
        val result = mutableListOf<RawField>()
        var idx = 0
        while (idx < data.size) {
            var shift = 0
            var tagRaw = 0
            while (idx < data.size) {
                val b = data[idx++].toInt() and 0xFF
                tagRaw = tagRaw or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            val fieldNumber = tagRaw ushr 3
            val wireType = tagRaw and 0x07
            when (wireType) {
                0 -> { // Varint
                    var v = 0L
                    shift = 0
                    while (idx < data.size) {
                        val b = data[idx++].toInt() and 0xFF
                        v = v or ((b.toLong() and 0x7FL) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    result.add(RawField(fieldNumber, wireType, v, ByteArray(0)))
                }
                1 -> { // 64-bit
                    idx += 8
                }
                2 -> { // Length-delimited
                    var len = 0
                    shift = 0
                    while (idx < data.size) {
                        val b = data[idx++].toInt() and 0xFF
                        len = len or ((b and 0x7F) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    if (idx + len <= data.size) {
                        val bytes = data.copyOfRange(idx, idx + len)
                        idx += len
                        result.add(RawField(fieldNumber, wireType, 0L, bytes))
                    } else {
                        break
                    }
                }
                5 -> { // 32-bit
                    idx += 4
                }
                else -> break
            }
        }
        return result
    }

    fun parseResponse(responseBytes: ByteArray): LensOCRResult {
        val fullLines = mutableListOf<String>()
        var detectedLang: String? = null

        val rootFields = parseRawFields(responseBytes)
        for (fRoot in rootFields) {
            if (fRoot.fieldNumber == 2 && fRoot.wireType == 2) { // objects_response
                val objFields = parseRawFields(fRoot.bytesValue)
                for (fObj in objFields) {
                    if (fObj.fieldNumber == 3 && fObj.wireType == 2) { // text
                        val textFields = parseRawFields(fObj.bytesValue)
                        for (fText in textFields) {
                            if (fText.fieldNumber == 2 && fText.wireType == 2) { // content_language
                                detectedLang = String(fText.bytesValue, StandardCharsets.UTF_8).trim()
                            } else if (fText.fieldNumber == 1 && fText.wireType == 2) { // text_layout
                                val layoutFields = parseRawFields(fText.bytesValue)
                                for (fLayout in layoutFields) {
                                    if (fLayout.fieldNumber == 1 && fLayout.wireType == 2) { // paragraphs
                                        val paraFields = parseRawFields(fLayout.bytesValue)
                                        for (fPara in paraFields) {
                                            if (fPara.fieldNumber == 2 && fPara.wireType == 2) { // lines
                                                val lineWords = StringBuilder()
                                                val lineFields = parseRawFields(fPara.bytesValue)
                                                for (fLine in lineFields) {
                                                    if (fLine.fieldNumber == 1 && fLine.wireType == 2) { // words
                                                        var wordText = ""
                                                        var separator = " "
                                                        val wordFields = parseRawFields(fLine.bytesValue)
                                                        for (fWord in wordFields) {
                                                            if (fWord.fieldNumber == 2 && fWord.wireType == 2) {
                                                                wordText = String(fWord.bytesValue, StandardCharsets.UTF_8)
                                                            } else if (fWord.fieldNumber == 3 && fWord.wireType == 2) {
                                                                separator = String(fWord.bytesValue, StandardCharsets.UTF_8)
                                                            }
                                                        }
                                                        if (wordText.isNotEmpty()) {
                                                            lineWords.append(wordText).append(separator)
                                                        }
                                                    }
                                                }
                                                val lineTrimmed = lineWords.toString().trim()
                                                if (lineTrimmed.isNotEmpty()) {
                                                    fullLines.add(lineTrimmed)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return LensOCRResult(
            text = fullLines.joinToString("\n"),
            language = detectedLang
        )
    }

    private fun ByteArrayOutputStream.writeVarint(fieldNumber: Int, wireType: Int, value: Long) {
        writeTag(fieldNumber, wireType)
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write((v and 0x7F).toInt())
    }

    private fun ByteArrayOutputStream.writeLengthDelimited(fieldNumber: Int, bytes: ByteArray) {
        writeTag(fieldNumber, 2)
        var len = bytes.size
        while (len and 0x7F.inv() != 0) {
            write((len and 0x7F) or 0x80)
            len = len ushr 7
        }
        write(len and 0x7F)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeTag(fieldNumber: Int, wireType: Int) {
        var tag = (fieldNumber shl 3) or wireType
        while (tag and 0x7F.inv() != 0) {
            write((tag and 0x7F) or 0x80)
            tag = tag ushr 7
        }
        write(tag and 0x7F)
    }
}
