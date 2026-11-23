package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.ui.swing.main.input.ScreenCoordinateMapper.MappedScreen
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Native physical pointer coordinates must become Swing logical coordinates exactly once.
 *
 * Every case pins a scale and a monitor geometry explicitly, so no test depends on the
 * machine it runs on actually using that DPI setting. The reporter's case is 125% on one
 * monitor: native (1000, 750) is the physical pixel under a cursor Swing sees at (800, 600).
 */
class ScreenCoordinateMapperTest {

    private fun mapper(vararg screens: MappedScreen, convert: Boolean = true) =
        ScreenCoordinateMapper(screens.toList(), convert)

    private fun screen(x: Int, y: Int, w: Int, h: Int, scale: Double) =
        MappedScreen(Rectangle(x, y, w, h), scale, scale)

    @Test
    fun `physical coordinates convert to logical at each scale and round to the nearest pixel`() {
        // (scale, logical screen size, physical in, logical out)
        listOf(
            listOf(1.0, 2560 to 1440, Point(1890, 845), Point(1890, 845)),
            listOf(1.25, 1536 to 864, Point(1000, 750), Point(800, 600)),
            listOf(1.25, 1536 to 864, Point(0, 0), Point(0, 0)),
            listOf(1.25, 1536 to 864, Point(1905, 1075), Point(1524, 860)),
            listOf(1.5, 1280 to 720, Point(1500, 750), Point(1000, 500)),
            listOf(2.0, 960 to 540, Point(1000, 500), Point(500, 250)),
            // Fractional results round to the nearest logical pixel.
            listOf(1.25, 1536 to 864, Point(1001, 751), Point(801, 601)),
            // The right-edge pixel still belongs to its screen instead of falling back to identity.
            listOf(1.25, 1920 to 1080, Point(2395, 500), Point(1916, 400)),
            listOf(1.25, 1920 to 1080, Point(2400, 1350), Point(1920, 1080)),
        ).forEach { (scale, size, physical, logical) ->
            @Suppress("UNCHECKED_CAST")
            val (w, h) = size as Pair<Int, Int>
            val map = mapper(screen(0, 0, w, h, scale as Double))
            assertEquals(logical, map.toSwing(physical as Point), "scale $scale, $physical")
        }
    }

    @Test
    fun `monitor origin offset is honored`() {
        // Second of two 125% monitors: logical origin x=1536.
        val map = mapper(
            screen(0, 0, 1536, 864, 1.25),
            screen(1536, 0, 1536, 864, 1.25),
        )
        assertEquals(Point(3072, 400), map.toSwing(Point(3840, 500)))
    }

    @Test
    fun `negative monitor origin is honored`() {
        val map = mapper(
            screen(-1920, 0, 1920, 1080, 1.0),
            screen(0, 0, 1536, 864, 1.25),
        )
        assertEquals(Point(-500, 300), map.toSwing(Point(-500, 300)))
        assertEquals(Point(800, 600), map.toSwing(Point(1000, 750)))
    }

    @Test
    fun `mixed dpi uses each screens own scale`() {
        val map = mapper(
            screen(0, 0, 1920, 1080, 1.0),
            screen(1920, 0, 1536, 864, 1.25),
        )
        // A point physically on the 100% monitor converts with scale 1.
        assertEquals(Point(1000, 500), map.toSwing(Point(1000, 500)))
        // A point physically on the 125% monitor converts with scale 1.25.
        assertEquals(Point(2400, 400), map.toSwing(Point(3000, 500)))
    }

    @Test
    fun `point matching no screen returns unchanged`() {
        val map = mapper(screen(0, 0, 1536, 864, 1.25))
        assertEquals(Point(9999, 9999), map.toSwing(Point(9999, 9999)))
    }

    @Test
    fun `disabled conversion passes through`() {
        val map = mapper(screen(0, 0, 1536, 864, 1.25), convert = false)
        assertEquals(Point(1000, 750), map.toSwing(Point(1000, 750)))
    }

    @Test
    fun `empty screen table passes through`() {
        val map = ScreenCoordinateMapper(emptyList(), convert = true)
        assertEquals(Point(1000, 750), map.toSwing(Point(1000, 750)))
    }

    @Test
    fun `non-positive scales are skipped not divided by`() {
        val map = mapper(
            MappedScreen(Rectangle(0, 0, 1536, 864), 0.0, 0.0),
            screen(0, 0, 1536, 864, 1.25),
        )
        assertEquals(Point(800, 600), map.toSwing(Point(1000, 750)))
    }

    @Test
    fun `click physically inside the button counts as inside after conversion`() {
        // The 125% failure mechanism from #226: the button sits at logical (808, 608, 42, 42)
        // next to a cursor Swing sees at (800, 600). The hook reports the click that lands on
        // the button's centre in physical pixels; comparing it raw against logical bounds
        // (the old behavior) reads "outside" and dismisses the button before it can fire.
        val map = mapper(screen(0, 0, 1536, 864, 1.25))
        val button = Rectangle(808, 608, 42, 42)
        val physicalClickOnButton = Point(1036, 786)

        assertTrue(
            !button.contains(physicalClickOnButton),
            "raw physical point must miss the logical bounds (the reported bug)",
        )
        assertTrue(
            button.contains(map.toSwing(physicalClickOnButton)),
            "converted point must hit: ${map.toSwing(physicalClickOnButton)}",
        )
    }

    @Test
    fun `click outside still counts as outside after conversion`() {
        val map = mapper(screen(0, 0, 1536, 864, 1.25))
        val button = Rectangle(808, 608, 42, 42)
        // Physical pixel of logical (1000, 750): well clear of the button.
        assertTrue(!button.contains(map.toSwing(Point(1250, 937))))
    }

    @Test
    fun `mapEvent converts mouse locations and leaves other events alone`() {
        val map = mapper(screen(0, 0, 1536, 864, 1.25))
        val button = assertIs<GlobalInputEvent.MouseButton>(
            map.mapEvent(GlobalInputEvent.MouseButton(MouseButtonId.LEFT, true, Point(1000, 750)))
        )
        assertEquals(Point(800, 600), button.location)

        val move = assertIs<GlobalInputEvent.MouseMove>(
            map.mapEvent(GlobalInputEvent.MouseMove(Point(1250, 937)))
        )
        assertEquals(Point(1000, 750), move.location)

        val key = GlobalInputEvent.Key(KeyClass.CONTROL, 162, true, false, false, timestampMs = 1L)
        assertEquals(key, map.mapEvent(key))
        val hotkey = GlobalInputEvent.Hotkey(7L)
        assertEquals(hotkey, map.mapEvent(hotkey))
    }

    @Test
    fun `raw companion mapping performs no scaling on its own`() {
        // The single conversion lives in ScreenCoordinateMapper via mapEvent; the raw
        // QInputEvent -> GlobalInputEvent step must stay a pure truncation so the two
        // can never stack.
        val down = assertIs<GlobalInputEvent.MouseButton>(
            QInputBackend.toGlobalEvent(
                io.github.ahatem.qinput.QInputEvent(
                    io.github.ahatem.qinput.QInputEvent.Kind.MOUSE_BUTTON, 0, 0L,
                    io.github.ahatem.qinput.QInputEvent.KeyClass.OTHER, 0,
                    io.github.ahatem.qinput.QInputEvent.State.PRESSED,
                    io.github.ahatem.qinput.QInputEvent.MouseButton.LEFT, 1000.7, 750.2, 0L
                )
            )
        )
        assertEquals(Point(1000, 750), down.location)
    }
}
