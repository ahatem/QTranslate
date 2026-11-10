package com.github.ahatem.qtranslate.core.conversation

sealed interface PlaybackTag {
    val segmentId: String

    data class Line(override val segmentId: String) : PlaybackTag
    data class Word(override val segmentId: String, val startMillis: Long, val endMillis: Long) : PlaybackTag
}
