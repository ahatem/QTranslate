package com.github.ahatem.qtranslate.ui.swing.main.input

import io.github.ahatem.qinput.QInputEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QInput native event mapping boundary: every raw event class the backend can deliver maps
 * to the adapter model the listener consumes, and non-actionable events map to null.
 */
class QInputBackendMappingTest {

    private fun key(
        keyClass: QInputEvent.KeyClass = QInputEvent.KeyClass.CONTROL,
        pressed: Boolean = true,
        flags: Int = 0,
        nativeCode: Int = 162,
        timestampMicros: Long = 1_500_000L,
    ) = QInputEvent(
        QInputEvent.Kind.KEY, flags, 0L, keyClass, nativeCode,
        if (pressed) QInputEvent.State.PRESSED else QInputEvent.State.RELEASED,
        QInputEvent.MouseButton.NONE, 0.0, 0.0, timestampMicros
    )

    @Test
    fun `hotkey press maps to action id and release maps to null`() {
        val pressed = QInputEvent(
            QInputEvent.Kind.HOTKEY, 0, 7L, QInputEvent.KeyClass.OTHER, 0,
            QInputEvent.State.PRESSED, QInputEvent.MouseButton.NONE, 0.0, 0.0, 0L
        )
        assertEquals(GlobalInputEvent.Hotkey(7L), QInputBackend.toGlobalEvent(pressed))

        val released = QInputEvent(
            QInputEvent.Kind.HOTKEY, 0, 7L, QInputEvent.KeyClass.OTHER, 0,
            QInputEvent.State.RELEASED, QInputEvent.MouseButton.NONE, 0.0, 0.0, 0L
        )
        assertNull(QInputBackend.toGlobalEvent(released))
    }

    @Test
    fun `key maps class state flags and native code`() {
        val event = assertIs<GlobalInputEvent.Key>(
            QInputBackend.toGlobalEvent(key(flags = QInputEvent.FLAG_INJECTED or QInputEvent.FLAG_REPEAT))
        )
        assertEquals(KeyClass.CONTROL, event.keyClass)
        assertEquals(162, event.nativeCode)
        assertTrue(event.pressed)
        assertTrue(event.injected)
        assertTrue(event.repeat)
        assertEquals(1500L, event.timestampMs)
    }

    @Test
    fun `key release and other classes map`() {
        val release = assertIs<GlobalInputEvent.Key>(
            QInputBackend.toGlobalEvent(key(pressed = false, keyClass = QInputEvent.KeyClass.OTHER))
        )
        assertEquals(KeyClass.OTHER, release.keyClass)
        assertEquals(false, release.pressed)

        val meta = assertIs<GlobalInputEvent.Key>(
            QInputBackend.toGlobalEvent(key(keyClass = QInputEvent.KeyClass.META))
        )
        assertEquals(KeyClass.META, meta.keyClass)
    }

    @Test
    fun `mouse buttons map with coordinates`() {
        val down = assertIs<GlobalInputEvent.MouseButton>(
            QInputBackend.toGlobalEvent(
                QInputEvent(
                    QInputEvent.Kind.MOUSE_BUTTON, 0, 0L, QInputEvent.KeyClass.OTHER, 0,
                    QInputEvent.State.PRESSED, QInputEvent.MouseButton.LEFT, 12.7, 99.2, 0L
                )
            )
        )
        assertEquals(MouseButtonId.LEFT, down.button)
        assertTrue(down.pressed)
        assertEquals(12, down.location.x)
        assertEquals(99, down.location.y)

        val move = assertIs<GlobalInputEvent.MouseMove>(
            QInputBackend.toGlobalEvent(
                QInputEvent(
                    QInputEvent.Kind.MOUSE_MOVE, 0, 0L, QInputEvent.KeyClass.OTHER, 0,
                    QInputEvent.State.PRESSED, QInputEvent.MouseButton.NONE, 5.0, 6.0, 0L
                )
            )
        )
        assertEquals(5, move.location.x)
    }

    @Test
    fun `self-injected flag maps`() {
        val self = assertIs<GlobalInputEvent.Key>(
            QInputBackend.toGlobalEvent(key(flags = QInputEvent.FLAG_SELF_INJECTED))
        )
        assertTrue(self.selfInjected)
        assertTrue(self.injected == false)

        val plain = assertIs<GlobalInputEvent.Key>(
            QInputBackend.toGlobalEvent(key())
        )
        assertTrue(!plain.selfInjected)
    }

    @Test
    fun `backend and unknown events map to null`() {
        val backend = QInputEvent(
            QInputEvent.Kind.BACKEND, 0, 0L, QInputEvent.KeyClass.OTHER, 0,
            QInputEvent.State.PRESSED, QInputEvent.MouseButton.NONE, 0.0, 0.0, 0L
        )
        assertNull(QInputBackend.toGlobalEvent(backend))
        assertNull(QInputBackend.toGlobalEvent(key().copy(kind = QInputEvent.Kind.UNKNOWN)))
    }

    private fun QInputEvent.copy(kind: QInputEvent.Kind) = QInputEvent(
        kind, flags(), id(), keyClass(), nativeCode(),
        if (isPressed) QInputEvent.State.PRESSED else QInputEvent.State.RELEASED,
        button(), x(), y(), timestampMicros()
    )
}
