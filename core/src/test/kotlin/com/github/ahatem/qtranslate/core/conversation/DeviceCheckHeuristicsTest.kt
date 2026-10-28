package com.github.ahatem.qtranslate.core.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCheckHeuristicsTest {

    @Test
    fun `identifies suspicious microphone names`() {
        assertTrue(DeviceCheckHeuristics.isSuspiciousMicrophone("SteelSeries Sonar - Stream"))
        assertTrue(DeviceCheckHeuristics.isSuspiciousMicrophone("Stereo Mix (Realtek)"))
        assertTrue(DeviceCheckHeuristics.isSuspiciousMicrophone("Monitor of Built-in Audio"))
        assertTrue(DeviceCheckHeuristics.isSuspiciousMicrophone("SteelSeries Sonar - Chat"))
        assertTrue(DeviceCheckHeuristics.isSuspiciousMicrophone("WASAPI Loopback Capture"))

        assertFalse(DeviceCheckHeuristics.isSuspiciousMicrophone("Microphone (Realtek(R) Audio)"))
        assertFalse(DeviceCheckHeuristics.isSuspiciousMicrophone("HyperX SoloCast"))
        assertFalse(DeviceCheckHeuristics.isSuspiciousMicrophone("USB Audio Device"))
        assertFalse(DeviceCheckHeuristics.isSuspiciousMicrophone(""))
        assertFalse(DeviceCheckHeuristics.isSuspiciousMicrophone(null))
    }

    @Test
    fun `detects when general output is selected while chat output exists`() {
        val outputs = listOf(
            "Headphones (HyperX Cloud)",
            "SteelSeries Sonar - Chat (Virtual Audio)",
            "Speakers (Realtek)"
        )

        // General output chosen -> warns and suggests Sonar - Chat
        assertEquals(
            "SteelSeries Sonar - Chat (Virtual Audio)",
            DeviceCheckHeuristics.findSuggestedChatOutput("Headphones (HyperX Cloud)", outputs)
        )

        // Chat output already chosen -> no warning
        assertNull(
            DeviceCheckHeuristics.findSuggestedChatOutput("SteelSeries Sonar - Chat (Virtual Audio)", outputs)
        )

        // No chat output available in the list -> no warning
        val noChatOutputs = listOf("Headphones (HyperX Cloud)", "Speakers (Realtek)")
        assertNull(
            DeviceCheckHeuristics.findSuggestedChatOutput("Headphones (HyperX Cloud)", noChatOutputs)
        )
    }

    @Test
    fun `pickDefaultMicrophone prefers dedicated microphones over virtual mix streams`() {
        val devices = listOf(
            AudioDevice("stream", "SteelSeries Sonar - Stream"),
            AudioDevice("mic", "Microphone (HyperX SoloCast)"),
            AudioDevice("loop", "Stereo Mix")
        )

        val selected = DeviceCheckHeuristics.pickDefaultMicrophone(devices)
        assertEquals("mic", selected?.id)
    }

    @Test
    fun `pickDefaultCallOutput prefers chat outputs`() {
        val outputs = listOf(
            AudioDevice("general", "Speakers (Realtek)"),
            AudioDevice("chat", "SteelSeries Sonar - Chat"),
            AudioDevice("headphones", "HyperX Earphone")
        )

        val selected = DeviceCheckHeuristics.pickDefaultCallOutput(outputs)
        assertEquals("chat", selected?.id)
    }
}
