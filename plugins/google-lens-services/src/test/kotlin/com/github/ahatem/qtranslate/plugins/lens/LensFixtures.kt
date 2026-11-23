package com.github.ahatem.qtranslate.plugins.lens

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Builds a synthetic binary Protobuf response following the Google Lens `crupload` schema.
 * Each line is a list of (word, separator) pairs.
 */
internal fun lensResponse(lines: List<List<Pair<String, String>>>, language: String?): ByteArray {
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
    return wrapText(textStream.toByteArray())
}

/** Convenience form: each string is one line, split into space-separated words. */
internal fun lensResponseOfLines(lines: List<String>, language: String? = null): ByteArray =
    lensResponse(
        lines.map { line ->
            val words = line.split(" ")
            words.mapIndexed { index, word -> word to if (index == words.lastIndex) "" else " " }
        },
        language
    )

/** Wraps an encoded `text` message into objects_response (field 2 -> field 3). */
internal fun wrapText(textBytes: ByteArray): ByteArray {
    val objectsResponseStream = ByteArrayOutputStream()
    writeLengthDelimited(objectsResponseStream, 3, textBytes) // text = field 3
    val rootStream = ByteArrayOutputStream()
    writeLengthDelimited(rootStream, 2, objectsResponseStream.toByteArray()) // objects_response = field 2
    return rootStream.toByteArray()
}

internal fun writeLengthDelimited(stream: ByteArrayOutputStream, fieldNumber: Int, bytes: ByteArray) {
    writeVarint(stream, (fieldNumber shl 3) or 2)
    writeVarint(stream, bytes.size)
    stream.write(bytes)
}

internal fun writeVarint(stream: ByteArrayOutputStream, value: Int) {
    var v = value
    while (v and 0x7F.inv() != 0) {
        stream.write((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    stream.write(v and 0x7F)
}
