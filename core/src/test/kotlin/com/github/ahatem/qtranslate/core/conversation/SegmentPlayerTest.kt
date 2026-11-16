package com.github.ahatem.qtranslate.core.conversation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentPlayerTest {

    @Test
    fun `stop clears playing and ends playback`() {
        val player = SegmentPlayer()
        val dummyPcm = ByteArray(3200 * 5) // 500ms of audio

        val played = player.play(dummyPcm, "tag-1")
        if (played) {
            assertEquals("tag-1", player.playing.value)
            player.stop()
            assertNull(player.playing.value)

            // Live threads check
            Thread.sleep(150)
            val survivingThreads = Thread.getAllStackTraces().keys.filter {
                it.name == "conversation-playback" && it.isAlive
            }
            assertTrue(survivingThreads.isEmpty(), "Playback thread should terminate after stop")
        } else {
            player.stop()
            assertNull(player.playing.value)
        }
    }

    @Test
    fun `second play replaces the first`() {
        val player = SegmentPlayer()
        val dummyPcm = ByteArray(3200 * 5)

        val played1 = player.play(dummyPcm, "tag-1")
        if (played1) {
            assertEquals("tag-1", player.playing.value)
            val played2 = player.play(dummyPcm, "tag-2")
            if (played2) {
                assertEquals("tag-2", player.playing.value)
            }
            player.stop()
            assertNull(player.playing.value)
        }
    }
}
