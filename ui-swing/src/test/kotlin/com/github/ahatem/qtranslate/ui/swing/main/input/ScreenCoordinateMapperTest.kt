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
    fun `100 percent is identity`() {
        val map = mapper(screen(0, 0, 2560, 1440, 1.0))
        assertEquals(Point(1890, 845), map.toSwing(Point(1890, 845)))
    }

    @Test
    fun `125 percent converts physical to logical`() {
        // 1920x1080 physical at 125% -> 1536x864 logical.
        val map = mapper(screen(0, 0, 1536, 864, 1.25))
        assertEquals(Point(800, 600), map.toSwing(Point(1000, 750)))
        assertEquals(Point(0, 0), map.toSwing(Point(0, 0)))
        assertEquals(Point(1524, 860), map.toSwing(Point(1905, 1075)))
    }

    @Test
    fun `150 percent converts physical to logical`() {
        val map = mapper(screen(0, 0, 1280, 720, 1.5))
        assertEquals(Point(1000, 500), map.toSwing(Point(1500, 750)))
    }

    @Test
    fun `200 percent converts physical to logical`() {
        val map = mapper(screen(0, 0, 960, 540, 2.0))
        assertEquals(Point(500, 250), map.toSwing(Point(1000, 500)))
    }

    @Test
    fun `fractional results round to the nearest logical pixel`() {
        val map = mapper(screen(0, 0, 1536, 864, 1.25))
        assertEquals(Point(801, 601), map.toSwing(Point(1001, 751)))
    }

    @Test
    fun `right edge pixel still belongs to its screen`() {
        // Physical width is 2400; the last column must map inside, not fall back to identity.
        val map = mapper(screen(0, 0, 1920, 1080, 1.25))
        assertEquals(Point(1916, 400), map.toSwing(Point(2395, 500)))
        assertEquals(Point(1920, 1080), map.toSwing(Point(2400, 1350)))
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

    @Test
    fun `windows detection matches platform naming`() {
        assertTrue(ScreenCoordinateMapper.isWindows("Windows 10"))
        assertTrue(ScreenCoordinateMapper.isWindows("Windows 11"))
        assertTrue(!ScreenCoordinateMapper.isWindows("Linux"))
        assertTrue(!ScreenCoordinateMapper.isWindows("Mac OS X"))
        assertTrue(!ScreenCoordinateMapper.isWindows(""))
    }
}
