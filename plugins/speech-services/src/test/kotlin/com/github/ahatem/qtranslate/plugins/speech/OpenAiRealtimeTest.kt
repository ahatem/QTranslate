package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.get
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAiRealtimeTest {

    @Test
    fun `parse openai realtime delta and final messages`() {
        val readyJson = """{"type":"session.created"}"""
        val ready = parseOpenAiRealtimeMessage(readyJson)
        assertEquals(OpenAiParsedMessage.Ready, ready)

        val deltaJson = """{"type":"conversation.item.input_audio_transcription.delta","delta":" hello"}"""
        val delta = parseOpenAiRealtimeMessage(deltaJson)
        assertTrue(delta is OpenAiParsedMessage.PartialDelta)
        assertEquals(" hello", delta.delta)

        val completedJson = """
            {
              "type": "conversation.item.input_audio_transcription.completed",
              "transcript": "Hello, how are you?"
            }
        """.trimIndent()
        val completed = parseOpenAiRealtimeMessage(completedJson)
        assertTrue(completed is OpenAiParsedMessage.Final)
        assertEquals("Hello, how are you?", completed.text)

        val errorJson = """{"type":"error","error":{"message":"Rate limit reached"}}"""
        val error = parseOpenAiRealtimeMessage(errorJson)
        assertTrue(error is OpenAiParsedMessage.Error)
        assertEquals("Rate limit reached", error.message)
    }

    @Test
    fun `openai realtime requires api key`() = runBlocking {
        val service = OpenAiRealtimeService {
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
    fun `session config is sent as transcription session with chosen model and language`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var firstFrame: String? = null
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 3_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                firstFrame = runCatching { readClientTextFrame(input) }.getOrNull()
                val ready = """{"type":"session.created"}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), ready.size.toByte()) + ready)
                output.flush()
                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime",
            languageTag = "ar"
        ).get()
        session?.cancel()
        serverThread.join(5_000)
        server.close()

        assertNotNull(firstFrame, "no frame reached the server: session.update was dropped")
        assertTrue(firstFrame!!.contains("\"type\":\"session.update\""), firstFrame)
        assertTrue(firstFrame!!.contains("\"type\":\"transcription\""), firstFrame)
        assertTrue(firstFrame!!.contains("\"model\":\"gpt-live-transcribe\""), firstFrame)
        assertTrue(firstFrame!!.contains("\"languages\":[\"ar\"]"), firstFrame)
        // No conversation turn response creation
        assertTrue(!firstFrame!!.contains("\"create_response\""), firstFrame)
    }

    @Test
    fun `session config with auto language does not include languages array`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var firstFrame: String? = null
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 3_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                firstFrame = runCatching { readClientTextFrame(input) }.getOrNull()
                val ready = """{"type":"session.created"}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), ready.size.toByte()) + ready)
                output.flush()
                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime",
            languageTag = null
        ).get()
        session?.cancel()
        serverThread.join(5_000)
        server.close()

        assertNotNull(firstFrame)
        assertTrue(firstFrame!!.contains("\"type\":\"transcription\""), firstFrame)
        assertTrue(!firstFrame!!.contains("\"languages\""), firstFrame)
    }

    @Test
    fun `partial deltas accumulate per item and reset on final completion`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 3_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                runCatching { readClientTextFrame(input) }

                fun sendServerText(json: String) {
                    val bytes = json.toByteArray()
                    output.write(byteArrayOf(0x81.toByte(), bytes.size.toByte()) + bytes)
                    output.flush()
                }

                sendServerText("""{"type":"session.created"}""")
                sendServerText("""{"type":"conversation.item.input_audio_transcription.delta","delta":"Hello"}""")
                sendServerText("""{"type":"conversation.item.input_audio_transcription.delta","delta":" world"}""")
                sendServerText("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"Hello world"}""")
                sendServerText("""{"type":"conversation.item.input_audio_transcription.delta","delta":"Second"}""")

                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        val collected = mutableListOf<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent>()
        val collectJob = this.launch {
            session.events.collect { collected.add(it) }
        }

        kotlinx.coroutines.delay(500)
        session.cancel()
        collectJob.cancel()
        serverThread.join(5_000)
        server.close()

        val partials = collected.filterIsInstance<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Partial>().map { it.text }
        val finals = collected.filterIsInstance<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final>().map { it.text }

        assertEquals(listOf("Hello", "Hello world", "Second"), partials)
        assertEquals(listOf("Hello world"), finals)
    }

    @Test
    fun `error event before ready fails openSession`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 3_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                runCatching { readClientTextFrame(input) }

                val err = """{"type":"error","error":{"message":"Invalid model name"}}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), err.size.toByte()) + err)
                output.flush()

                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val res = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "bad-model",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        )
        serverThread.join(5_000)
        server.close()

        val err = res.getError()
        assertNotNull(err)
        assertTrue(err.message.contains("Invalid model name"), err.message)
    }

    @Test
    fun `error event after ready emits Failed event`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 3_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                runCatching { readClientTextFrame(input) }

                fun sendServerText(json: String) {
                    val bytes = json.toByteArray()
                    output.write(byteArrayOf(0x81.toByte(), bytes.size.toByte()) + bytes)
                    output.flush()
                }

                sendServerText("""{"type":"session.created"}""")
                Thread.sleep(100)
                sendServerText("""{"type":"error","error":{"message":"Audio buffer overflow"}}""")

                runCatching { while (input.read() >= 0) { /* drain */ } }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        val collected = mutableListOf<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent>()
        val collectJob = this.launch {
            session.events.collect { collected.add(it) }
        }

        kotlinx.coroutines.delay(500)
        session.cancel()
        collectJob.cancel()
        serverThread.join(5_000)
        server.close()

        val failed = collected.filterIsInstance<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Failed>().firstOrNull()
        assertNotNull(failed, "Expected LiveSpeechEvent.Failed event")
        assertTrue(failed.error.message.contains("Audio buffer overflow"), failed.error.message)
    }

    private fun generateSinePcm16(frequencyHz: Double, durationMs: Int, sampleRate: Int = 16_000, amplitude: Short = 10_000): ByteArray {
        val numSamples = (sampleRate * durationMs) / 1000
        val bytes = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val sample = (amplitude * kotlin.math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate)).toInt().toShort()
            bytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `speech then silence triggers commit`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val receivedFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 4_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()

                val ready = """{"type":"session.created"}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), ready.size.toByte()) + ready)
                output.flush()

                runCatching {
                    while (true) {
                        val frame = readClientTextFrame(input)
                        receivedFrames.add(frame)
                    }
                }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        // Send 500ms of speech (sine wave)
        session.sendAudio(generateSinePcm16(frequencyHz = 440.0, durationMs = 500))
        // Send 700ms of silence in chunks of 100ms
        for (i in 0 until 7) {
            session.sendAudio(ByteArray(1600 * 2)) // 100ms silence at 16kHz
        }

        kotlinx.coroutines.delay(400)
        session.cancel()
        serverThread.join(5_000)
        server.close()

        val commitCount = receivedFrames.count { it.contains("\"type\":\"input_audio_buffer.commit\"") }
        assertEquals(1, commitCount, "Expected exactly 1 commit after speech followed by silence: $receivedFrames")
    }

    @Test
    fun `continuous speech triggers commit at max turn duration`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val receivedFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 4_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()

                val ready = """{"type":"session.created"}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), ready.size.toByte()) + ready)
                output.flush()

                runCatching {
                    while (true) {
                        val frame = readClientTextFrame(input)
                        receivedFrames.add(frame)
                    }
                }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        // Continuous speech for 16 seconds (16 chunks of 1000ms each)
        for (i in 0 until 16) {
            session.sendAudio(generateSinePcm16(frequencyHz = 440.0, durationMs = 1000))
        }

        kotlinx.coroutines.delay(400)
        session.cancel()
        serverThread.join(5_000)
        server.close()

        val commitCount = receivedFrames.count { it.contains("\"type\":\"input_audio_buffer.commit\"") }
        assertEquals(1, commitCount, "Expected commit at max turn duration (>=15s) during continuous speech: $receivedFrames")
    }

    @Test
    fun `silence only never triggers commit`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val receivedFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 4_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()

                val ready = """{"type":"session.created"}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), ready.size.toByte()) + ready)
                output.flush()

                runCatching {
                    while (true) {
                        val frame = readClientTextFrame(input)
                        receivedFrames.add(frame)
                    }
                }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        // Send 2000ms of pure silence
        for (i in 0 until 20) {
            session.sendAudio(ByteArray(1600 * 2)) // 100ms silence
        }

        kotlinx.coroutines.delay(400)
        // Note: finish() commits uncommitted buffer, but cancel() does not commit
        session.cancel()
        serverThread.join(5_000)
        server.close()

        val commitCount = receivedFrames.count { it.contains("\"type\":\"input_audio_buffer.commit\"") }
        assertEquals(0, commitCount, "Expected 0 commits for silence-only audio: $receivedFrames")
    }

    @Test
    fun `silence-only session then finish does not commit`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val receivedFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 4_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()

                val ready = """{"type":"session.created"}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), ready.size.toByte()) + ready)
                output.flush()

                runCatching {
                    while (true) {
                        val frame = readClientTextFrame(input)
                        receivedFrames.add(frame)
                    }
                }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        // Send 2 seconds of silence
        for (i in 0 until 20) {
            session.sendAudio(ByteArray(1600 * 2)) // 100ms
        }

        // Call finish()
        session.finish()
        serverThread.join(5_000)
        server.close()

        val commitCount = receivedFrames.count { it.contains("\"type\":\"input_audio_buffer.commit\"") }
        assertEquals(0, commitCount, "Expected 0 commits when finish() called after silence only: $receivedFrames")
    }

    @Test
    fun `long silence before speech does not prematurely cut the turn`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val receivedFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 6_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()

                val ready = """{"type":"session.created"}""".toByteArray()
                output.write(byteArrayOf(0x81.toByte(), ready.size.toByte()) + ready)
                output.flush()

                runCatching {
                    while (true) {
                        val frame = readClientTextFrame(input)
                        receivedFrames.add(frame)
                    }
                }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        // 20s of silence (20 chunks of 1s each)
        for (i in 0 until 20) {
            session.sendAudio(ByteArray(16000 * 2))
        }

        // 2s of continuous speech (2 chunks of 1s each)
        session.sendAudio(generateSinePcm16(frequencyHz = 440.0, durationMs = 1000))
        session.sendAudio(generateSinePcm16(frequencyHz = 440.0, durationMs = 1000))

        // 700ms of silence
        for (i in 0 until 7) {
            session.sendAudio(ByteArray(1600 * 2))
        }

        kotlinx.coroutines.delay(400)
        session.cancel()
        serverThread.join(5_000)
        server.close()

        val commitCount = receivedFrames.count { it.contains("\"type\":\"input_audio_buffer.commit\"") }
        assertEquals(1, commitCount, "Expected exactly 1 commit after 20s silence then speech then silence: $receivedFrames")

        val clearCount = receivedFrames.count { it.contains("\"type\":\"input_audio_buffer.clear\"") }
        assertTrue(clearCount > 0, "Expected periodic clear events during 20s of leading silence: $receivedFrames")
    }

    @Test
    fun `two turns emit two Final events`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = thread(isDaemon = true) {
            server.accept().use { client ->
                client.soTimeout = 4_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val wsKey = generateSequence { readHttpLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()

                fun sendServerText(json: String) {
                    val bytes = json.toByteArray()
                    output.write(byteArrayOf(0x81.toByte(), bytes.size.toByte()) + bytes)
                    output.flush()
                }

                sendServerText("""{"type":"session.created"}""")

                var commitCount = 0
                runCatching {
                    while (true) {
                        val frame = readClientTextFrame(input)
                        if (frame.contains("\"type\":\"input_audio_buffer.commit\"")) {
                            commitCount++
                            sendServerText("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"Sentence $commitCount"}""")
                        }
                    }
                }
            }
        }

        val session = OpenAiRealtimeSession.open(
            apiKey = "test-key",
            model = "gpt-live-transcribe",
            endpoint = "ws://127.0.0.1:${server.localPort}/v1/realtime"
        ).get()
        assertNotNull(session)

        val collected = mutableListOf<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent>()
        val collectJob = this.launch {
            session.events.collect { collected.add(it) }
        }

        // Turn 1: 500ms speech + 700ms silence
        session.sendAudio(generateSinePcm16(frequencyHz = 440.0, durationMs = 500))
        for (i in 0 until 7) {
            session.sendAudio(ByteArray(1600 * 2))
        }

        // Turn 2: 500ms speech + 700ms silence
        session.sendAudio(generateSinePcm16(frequencyHz = 440.0, durationMs = 500))
        for (i in 0 until 7) {
            session.sendAudio(ByteArray(1600 * 2))
        }

        kotlinx.coroutines.delay(500)
        session.cancel()
        collectJob.cancel()
        serverThread.join(5_000)
        server.close()

        val finals = collected.filterIsInstance<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final>().map { it.text }
        assertEquals(listOf("Sentence 1", "Sentence 2"), finals)
    }

    private fun readHttpLine(input: DataInputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == 10) break
            if (c != 13) line.append(c.toChar())
        }
        return line.toString()
    }

    private fun readClientTextFrame(input: DataInputStream): String {
        input.readUnsignedByte()
        val lengthByte = input.readUnsignedByte() and 0x7f
        val length = when (lengthByte) {
            126 -> input.readUnsignedShort()
            127 -> input.readLong().toInt()
            else -> lengthByte
        }
        val mask = ByteArray(4).also { input.readFully(it) }
        val payload = ByteArray(length).also { input.readFully(it) }
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return payload.decodeToString()
    }
}
