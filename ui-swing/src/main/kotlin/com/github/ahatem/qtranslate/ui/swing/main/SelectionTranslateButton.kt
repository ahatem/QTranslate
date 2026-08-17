package com.github.ahatem.qtranslate.ui.swing.main

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.max
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

/**
 * A small floating button shown next to text the user selected in another application.
 *
 * Deliberately unobtrusive: it never takes focus, fades in and out rather than snapping,
 * and keeps itself out of the way of the selection that triggered it.
 *
 * ### Behaviour
 * - Auto-hides after [VISIBLE_MS], but the countdown pauses while the pointer is over it,
 *   so the button never disappears from under a user who is reaching for it.
 * - Repositions itself to stay on the monitor under the pointer, flipping to the other
 *   side of the cursor near a screen edge instead of being clamped half off-screen.
 * - [dismissIfOutside] lets the caller close it when the user clicks elsewhere.
 *
 * ### Rendering
 * All colours are read from [UIManager] at paint time, so the button follows theme
 * changes without needing to be rebuilt. When the platform supports per-pixel
 * translucency the window is transparent and the button paints its own rounded body
 * and soft shadow; otherwise it falls back to a plain opaque square.
 */
internal class SelectionTranslateButton(
    owner: Window,
    iconManager: IconManager,
    tooltip: String,
    private val onTranslate: (String) -> Unit
) : JWindow(owner) {

    private var selectedText = ""
    private val hideTimer = Timer(VISIBLE_MS) { fadeOutAndHide() }.apply { isRepeats = false }
    private var fadeTimer: Timer? = null

    private val translucent: Boolean = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
    }.getOrDefault(false)

    private val icon: FlatSVGIcon =
        (iconManager.getIcon(ICON_PATH, ICON_SIZE, ICON_SIZE) as FlatSVGIcon).applyForegroundColorFilter()

    private val face = ButtonFace()

    init {
        type = Window.Type.POPUP
        isAlwaysOnTop = true
        // Never take focus — the user is mid-task in another application and the
        // button appearing must not interrupt whatever they are doing.
        focusableWindowState = false
        if (translucent) background = TRANSPARENT

        contentPane = face
        val edge = FACE_SIZE + PADDING * 2
        size = Dimension(edge, edge)
        face.toolTipText = tooltip
    }

    /** Shows the button near [pointer] for [text], choosing a corner that stays on screen. */
    fun showAt(pointer: Point, text: String) {
        if (text.isBlank()) return
        selectedText = text
        face.hovered = false
        location = placeNear(pointer)

        if (!isVisible) {
            setOpacitySafely(0f)
            isVisible = true
        }
        fadeTo(1f)
        hideTimer.restart()
    }

    /** Closes the button when the user presses the mouse anywhere outside it. */
    fun dismissIfOutside(screenPoint: Point) {
        if (!isVisible) return
        if (!bounds.contains(screenPoint)) dismiss()
    }

    fun dismiss() {
        hideTimer.stop()
        fadeTimer?.stop()
        fadeTimer = null
        isVisible = false
        selectedText = ""
    }

    override fun dispose() {
        hideTimer.stop()
        fadeTimer?.stop()
        super.dispose()
    }

    // ── Placement ────────────────────────────────────────────────────────────

    /**
     * Prefers below-right of the cursor, which is where the drag ended and therefore
     * clear of the text just selected. Flips horizontally or vertically when that
     * corner would leave the monitor, so the button is never clipped or shoved on top
     * of the selection.
     */
    private fun placeNear(pointer: Point): Point {
        val screen = screenBoundsFor(pointer)
        val w = width
        val h = height

        var x = pointer.x + GAP - PADDING
        var y = pointer.y + GAP - PADDING

        if (x + w > screen.x + screen.width) x = pointer.x - GAP - FACE_SIZE - PADDING
        if (y + h > screen.y + screen.height) y = pointer.y - GAP - FACE_SIZE - PADDING

        x = x.coerceIn(screen.x, max(screen.x, screen.x + screen.width - w))
        y = y.coerceIn(screen.y, max(screen.y, screen.y + screen.height - h))
        return Point(x, y)
    }

    private fun screenBoundsFor(pointer: Point): Rectangle =
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
            .firstOrNull { it.contains(pointer) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds

    // ── Fading ───────────────────────────────────────────────────────────────

    private fun fadeTo(target: Float, onDone: (() -> Unit)? = null) {
        fadeTimer?.stop()
        if (!translucent) {
            setOpacitySafely(target)
            onDone?.invoke()
            return
        }
        val start = opacity
        if (abs(start - target) < 0.01f) {
            onDone?.invoke()
            return
        }
        var step = 0
        fadeTimer = Timer(FADE_MS / FADE_STEPS) { event ->
            step++
            setOpacitySafely(start + (target - start) * (step.toFloat() / FADE_STEPS))
            if (step >= FADE_STEPS) {
                (event.source as Timer).stop()
                onDone?.invoke()
            }
        }.apply { isRepeats = true; start() }
    }

    private fun fadeOutAndHide() = fadeTo(0f) { if (opacity <= 0.01f) dismiss() }

    private fun setOpacitySafely(value: Float) {
        runCatching { opacity = value.coerceIn(0f, 1f) }
    }

    // ── Painting ─────────────────────────────────────────────────────────────

    private inner class ButtonFace : JComponent() {
        var hovered = false
            set(value) {
                if (field != value) { field = value; repaint() }
            }

        init {
            isOpaque = !translucent
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    // Hold the button open while the pointer is on it — nothing is more
                    // annoying than a target that vanishes as you reach for it.
                    hideTimer.stop()
                    fadeTimer?.stop()
                    setOpacitySafely(1f)
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    if (isVisible) hideTimer.restart()
                }

                override fun mousePressed(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    val text = selectedText
                    dismiss()
                    if (text.isNotBlank()) onTranslate(text)
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                val faceRect = Rectangle(PADDING, PADDING, FACE_SIZE, FACE_SIZE)

                if (translucent) paintShadow(g2, faceRect)

                g2.color = if (hovered) hoverBackground() else background()
                g2.fillRoundRect(faceRect.x, faceRect.y, faceRect.width, faceRect.height, ARC, ARC)

                g2.color = borderColor()
                g2.drawRoundRect(faceRect.x, faceRect.y, faceRect.width - 1, faceRect.height - 1, ARC, ARC)

                icon.paintIcon(
                    this, g2,
                    faceRect.x + (faceRect.width - icon.iconWidth) / 2,
                    faceRect.y + (faceRect.height - icon.iconHeight) / 2
                )
            } finally {
                g2.dispose()
            }
        }

        /** Layered translucent rounded rects — cheap approximation of a soft drop shadow. */
        private fun paintShadow(g2: Graphics2D, face: Rectangle) {
            val composite = g2.composite
            for (i in PADDING downTo 1) {
                val alpha = SHADOW_ALPHA * (1f - i.toFloat() / (PADDING + 1))
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                g2.color = Color.BLACK
                g2.fillRoundRect(
                    face.x - i, face.y - i + SHADOW_Y_OFFSET,
                    face.width + i * 2, face.height + i * 2,
                    ARC + i, ARC + i
                )
            }
            g2.composite = composite
        }

        /**
         * A surface that reads as raised above whatever is behind it. Theme background
         * colours alone are too close to the window the button floats over — especially
         * in dark themes — so the base colour is nudged away from the page.
         */
        private fun background(): Color {
            val base = UIManager.getColor("Panel.background") ?: Color.WHITE
            return if (FlatSVGIcon.isDarkLaf()) base.shift(0.15f) else base.shift(0.55f)
        }

        /**
         * Hover leans on the theme accent rather than `Button.hoverBackground`, which in
         * several light themes is almost identical to the base and leaves the button
         * looking inert under the cursor.
         */
        private fun hoverBackground(): Color = background().blend(accent(), 0.20f)

        private fun borderColor(): Color =
            if (hovered) accent()
            else UIManager.getColor("Component.borderColor")
                ?: UIManager.getColor("Separator.foreground")
                ?: Color.GRAY

        private fun accent(): Color =
            UIManager.getColor("Component.focusColor")
                ?: UIManager.getColor("Component.accentColor")
                ?: UIManager.getColor("Component.focusedBorderColor")
                ?: Color(0x2D_7D_F6)

        /** Moves a colour toward white (positive [amount]) by the given fraction. */
        private fun Color.shift(amount: Float): Color = blend(Color.WHITE, amount)

        private fun Color.blend(other: Color, ratio: Float): Color {
            val r = ratio.coerceIn(0f, 1f)
            return Color(
                (red + (other.red - red) * r).toInt().coerceIn(0, 255),
                (green + (other.green - green) * r).toInt().coerceIn(0, 255),
                (blue + (other.blue - blue) * r).toInt().coerceIn(0, 255)
            )
        }
    }

    private companion object {
        val ICON_PATH = Icons.TRANSLATE
        const val ICON_SIZE = 16

        /** Visible button body; the window is larger to leave room for the shadow. */
        const val FACE_SIZE = 30
        const val PADDING = 6
        const val ARC = 10

        /** Distance from the cursor to the nearest edge of the button body. */
        const val GAP = 14

        const val VISIBLE_MS = 4_000
        const val FADE_MS = 140
        const val FADE_STEPS = 7

        const val SHADOW_ALPHA = 0.16f
        const val SHADOW_Y_OFFSET = 1

        val TRANSPARENT = Color(0, 0, 0, 0)
    }
}
