package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.tts.AudioFormat
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertTrue

class JLayerAudioPlayerTest {
    private class Log : Logger {
        val errors = AtomicInteger()
        val warnings = AtomicInteger()
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) { warnings.incrementAndGet() }
        override fun error(message: String, error: Throwable?) { errors.incrementAndGet() }
    }

    private class Output(private val releaseOnClose: Boolean = true) : AudioOutput {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = AtomicInteger()
        override fun play(shouldContinue: () -> Boolean) {
            started.countDown()
            finish.await(5, TimeUnit.SECONDS)
        }
        override fun close() {
            closed.incrementAndGet()
            if (releaseOnClose) finish.countDown()
        }
    }

    private suspend fun until(predicate: () -> Boolean) = withTimeout(5_000) {
        while (!predicate()) delay(10)
    }
    private fun audio(format: AudioFormat) = TTSAudio.Bytes(byteArrayOf(1, 2, 3), format)
    private fun player(log: Log, factory: AudioOutputFactory) =
        JLayerAudioPlayer(CoroutineScope(SupervisorJob() + Dispatchers.Default), log, factory)

    @Test fun `MP3 and WAV paths run and playing state completes`() = runBlocking {
        val log = Log()
        val formats = mutableListOf<AudioFormat>()
        val outputs = mutableListOf<Output>()
        val player = player(log, AudioOutputFactory { format, _ ->
            synchronized(outputs) { formats += format; Output().also(outputs::add) }
        })
        try {
            assertFalse(player.isPlaying.value)
            for (format in listOf(AudioFormat.MP3, AudioFormat.WAV)) {
                player.play(audio(format))
                until { player.isPlaying.value }
                val output = synchronized(outputs) { outputs.last() }
                assertTrue(output.started.await(5, TimeUnit.SECONDS))
                output.finish.countDown()
                until { !player.isPlaying.value }
                assertEquals(1, output.closed.get())
            }
            assertEquals(listOf(AudioFormat.MP3, AudioFormat.WAV), formats)
        } finally { player.close() }
    }

    @Test fun `unsupported format does not replace active WAV`() = runBlocking {
        val log = Log()
        val output = Output()
        val player = player(log, AudioOutputFactory { _, _ -> output })
        try {
            player.play(audio(AudioFormat.WAV))
            until { player.isPlaying.value }
            player.play(audio(AudioFormat.OGG))
            assertTrue(player.isPlaying.value)
            assertEquals(0, output.closed.get())
            assertEquals(1, log.warnings.get())
        } finally { player.close() }
    }

    @Test fun `replacement closes old WAV and stale completion cannot stop new playback`() = runBlocking {
        val old = Output(releaseOnClose = false)
        val next = Output()
        val queue = ArrayDeque(listOf(old, next))
        val player = player(Log(), AudioOutputFactory { _, _ -> synchronized(queue) { queue.removeFirst() } })
        try {
            player.play(audio(AudioFormat.WAV))
            until { player.isPlaying.value && old.started.count == 0L }
            player.play(audio(AudioFormat.WAV))
            until { old.closed.get() == 1 && next.started.count == 0L }
            old.finish.countDown()
            delay(50)
            assertTrue(player.isPlaying.value)
            assertEquals(0, next.closed.get())
            player.stop()
            assertFalse(player.isPlaying.value)
            assertEquals(1, next.closed.get())
        } finally { old.finish.countDown(); next.finish.countDown(); player.close() }
    }

    @Test fun `malformed WAV failure permits later playback`() = runBlocking {
        val log = Log()
        val valid = Output()
        var calls = 0
        val player = player(log, AudioOutputFactory { _, _ ->
            if (calls++ == 0) throw IllegalArgumentException("Malformed WAV")
            valid
        })
        try {
            player.play(audio(AudioFormat.WAV))
            until { log.errors.get() == 1 }
            assertFalse(player.isPlaying.value)
            player.play(audio(AudioFormat.WAV))
            until { player.isPlaying.value }
            player.stop()
            assertFalse(player.isPlaying.value)
            assertEquals(1, valid.closed.get())
            assertFails { SystemAudioOutputFactory.create(AudioFormat.WAV, byteArrayOf(1, 2, 3)) }
        } finally { player.close() }
        Unit
    }

    @Test fun `close stops playback but leaves the parent scope active`() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = Output(releaseOnClose = false)
        val player = JLayerAudioPlayer(parentScope, Log(), AudioOutputFactory { _, _ -> output })

        player.play(audio(AudioFormat.WAV))
        until { player.isPlaying.value }

        player.close()

        until { !player.isPlaying.value }
        assertEquals(1, output.closed.get())
        assertTrue(parentScope.isActive)

        // The parent scope must still be usable for unrelated application work.
        val ran = CountDownLatch(1)
        parentScope.launch { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        parentScope.cancel()
    }

    @Test fun `close is safe to call more than once`() = runBlocking {
        val player = player(Log(), AudioOutputFactory { _, _ -> Output() })
        player.play(audio(AudioFormat.WAV))
        until { player.isPlaying.value }
        player.close()
        player.close()
        assertFalse(player.isPlaying.value)
    }
}
