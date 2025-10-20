package com.github.ahatem.qtranslate.core.audio

import kotlinx.coroutines.flow.StateFlow

interface AudioPlayer {

    val isPlaying: StateFlow<Boolean>

    fun play(audioData: ByteArray)

    fun stop()

    fun close()
}