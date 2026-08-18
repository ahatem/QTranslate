package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That a window position on a display left of the primary one can be stored at all.
 *
 * [Position] used to require both coordinates to be non-negative. A display arranged to the left of
 * or above the primary one occupies negative coordinates, so a window on it threw from the middle
 * of saving. Five of the seven call sites had grown a `coerceAtLeast(0)` to get past it, which is
 * how the constraint survived: the crash only reached the two that had not.
 */
class WindowPositionTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `a position on a display left of the primary one is accepted`() {
        val position = Position(x = -1720, y = 240)

        assertEquals(-1720, position.x)
        assertEquals(240, position.y)
    }

    @Test
    fun `a position above the primary display is accepted`() {
        assertEquals(-980, Position(x = 300, y = -980).y)
    }

    @Test
    fun `a negative position survives a round trip through storage`() {
        // Serialising was never the problem; reading it back on the next launch is where a
        // constructor requirement would have bitten instead.
        val original = Configuration.DEFAULT.copy(mainWindowPosition = Position(-1720, -300))

        val restored = json.decodeFromString<Configuration>(json.encodeToString(original))

        assertEquals(Position(-1720, -300), restored.mainWindowPosition)
    }

    @Test
    fun `a size must still be positive`() {
        // The matching requirement on Size is correct and stays: there is no such thing as a
        // window minus four pixels wide, and it is not what negative coordinates mean.
        val failure = runCatching { Size(width = -1, height = 100) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, failure!!::class)
    }
}
