package com.github.ahatem.qtranslate.ui.swing.main.input

import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * Converts native global pointer coordinates into Swing user-space coordinates.
 *
 * On Windows QInput reports `WH_MOUSE_LL` hook coordinates, which are physical device pixels
 * passed through unscaled, while every Swing window/bounds API (`JWindow.location`,
 * `Window.bounds`, `GraphicsConfiguration.bounds`, `MouseInfo` locations) speaks logical,
 * DPI-scaled pixels. Feeding one space into the other is harmless at 100% scaling (the spaces
 * coincide) and misplaces every floating surface at 125% and above.
 *
 * This is the single place where that conversion happens: [QInputBackend] applies [mapEvent]
 * once, exactly where native coordinates enter the application, so all downstream consumers
 * (selection-icon placement, outside-click dismissal, gesture distances) share one consistent
 * space. Do not add further scaling in individual popups; that would scale twice.
 */
internal class ScreenCoordinateMapper(
    private val screens: List<MappedScreen> = systemScreens(),
    private val convert: Boolean = isWindows(),
) {

    /**
     * One monitor: its bounds in Swing logical coordinates and the DPI scale that maps
     * native physical pixels onto them.
     */
    data class MappedScreen(val bounds: Rectangle, val scaleX: Double, val scaleY: Double)

    /**
     * Maps one native pointer location to the coordinates Swing windows use.
     *
     * Each screen is tried with its own scale, so mixed-DPI desktops convert per monitor
     * instead of assuming one global factor. A point that matches no screen (or conversion
     * being disabled, or an unusable scale) is returned unchanged rather than guessed at.
     */
    fun toSwing(native: Point): Point {
        if (!convert || screens.isEmpty()) return native
        for (screen in screens) {
            val scaleX = screen.scaleX.takeIf { it.isFinite() && it > 0 } ?: continue
            val scaleY = screen.scaleY.takeIf { it.isFinite() && it > 0 } ?: continue
            val candidate = Point(
                (native.x / scaleX).roundToInt(),
                (native.y / scaleY).roundToInt(),
            )
            if (containsInclusive(screen.bounds, candidate)) return candidate
        }
        return native
    }

    /**
     * Applies [toSwing] to the pointer carried by mouse events. Every other event passes
     * through untouched, so non-positional input can never be double-scaled.
     */
    fun mapEvent(event: GlobalInputEvent): GlobalInputEvent = when (event) {
        is GlobalInputEvent.MouseButton -> event.copy(location = toSwing(event.location))
        is GlobalInputEvent.MouseMove -> event.copy(location = toSwing(event.location))
        else -> event
    }

    companion object {
        fun system(): ScreenCoordinateMapper = ScreenCoordinateMapper()

        fun isWindows(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
            osName.startsWith("Windows", ignoreCase = true)

        /**
         * The connected displays with their logical bounds and per-monitor DPI scales.
         * Empty when they cannot be read (e.g. headless), in which case [toSwing] is identity.
         */
        fun systemScreens(): List<MappedScreen> = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .screenDevices
                .map { device ->
                    val config = device.defaultConfiguration
                    val transform = config.defaultTransform
                    MappedScreen(config.bounds, transform.scaleX, transform.scaleY)
                }
        }.getOrDefault(emptyList())

        /**
         * Edge-inclusive containment: a cursor at the exact last pixel of a monitor still
         * belongs to that monitor. [Rectangle.contains] excludes the right/bottom edge,
         * which would drop edge pixels into the identity fallback.
         */
        private fun containsInclusive(bounds: Rectangle, point: Point): Boolean =
            point.x in bounds.x..bounds.x + bounds.width &&
                point.y in bounds.y..bounds.y + bounds.height
    }
}
