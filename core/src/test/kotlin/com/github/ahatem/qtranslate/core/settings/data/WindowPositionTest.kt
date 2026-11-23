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
    fun `positions left of or above the primary display are accepted`() {
        assertEquals(-1720, Position(x = -1720, y = 240).x)
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
}
