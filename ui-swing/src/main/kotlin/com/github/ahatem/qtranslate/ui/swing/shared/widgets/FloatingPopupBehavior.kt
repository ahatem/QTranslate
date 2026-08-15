package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.ui.swing.shared.util.AppIcons
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.math.abs

/**
 * The window behaviour shared by the floating popups — translate, dictionary, images.
 *
 * ### Why a helper rather than a base class
 * The three popups agree about how their *window* behaves and disagree about everything inside
 * it, including deliberately: the image popup has no idle-hide, because a grid of pictures is
 * compared and clicked through while a definition is read in a couple of seconds. Composition
 * lets that be a decision each popup makes — it simply never calls [configureIdleHide] — rather
 * than a hook a base class has to invent.
 *
 * ### What it owns
 * Window flags, the pinned border, Escape, dragging, resizing, geometry, positioning, the theme
 * listener, fading, idle-hide and pointer presence. All of it existed two or three times over
 * before, closely enough that the copies shared their bugs: an idle-close timer nothing held a
 * reference to, and a countdown no amount of typing would reset.
 *
 * ### What it does not own
 * Anything to do with content. What a popup shows, and when it asks for more of it, is its own.
 *
 * ### Ownership
 * These popups must be constructed with a **null owner**, not with the main window, and [owner]
 * here is a positioning reference only. AWT ties an owned window's fate to its owner: while the
 * owner is hidden or iconified the platform suppresses windows it owns, and showing one can pull
 * the owner back into view. This application lives in the tray, so its main window is hidden or
 * iconified most of the time — which made a hotkey sometimes summon the main window, sometimes
 * dismiss it, and sometimes produce no popup at all, depending on the state the window was last
 * left in. A null owner gives Swing's shared hidden frame, which is never shown and never
 * iconified, so none of that applies.
 */
class FloatingPopupBehavior(
    private val window: JDialog,
    private val owner: Frame,
    minimumSize: Dimension,
    private val pinnedBorderWidth: Int = 4,
    private val resizeHandle: Int = 8
) {

    /**
     * True once the user has dragged the window, after which it stops repositioning itself.
     *
     * A popup that jumped back to the pointer after being deliberately moved would be fighting
     * the person using it.
     */
    var wasManuallyMoved: Boolean = false
        private set

    private var themeListener: PropertyChangeListener? = null

    private val borderColor: Color get() = UIManager.getColor("Component.borderColor") ?: Color.GRAY
    private val accentColor: Color
        get() = UIManager.getColor("Component.focusedBorderColor")
            ?: UIManager.getColor("Component.accentColor")
            ?: borderColor

    init {
        // Applied here so every floating popup gets it: they are owned by the shared hidden
        // frame, which carries Java's default icon and passes it on.
        AppIcons.applyTo(window)
        window.isUndecorated = true
        window.isAlwaysOnTop = true
        window.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
        window.minimumSize = minimumSize
    }

    /**
     * Makes [handle] the part of the window that can be dragged, and records that it was.
     *
     * [onMoved] is called with the resting position so the caller can persist it.
     */
    fun installDrag(handle: JComponent, onMoved: (Position) -> Unit) {
        ComponentMover.builder()
            .destinationComponent(window)
            .build()
            .register(handle)

        handle.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                wasManuallyMoved = true
                onMoved(Position(window.x.coerceAtLeast(0), window.y.coerceAtLeast(0)))
            }
        })
    }

    /** Border-edge resizing, reporting the final size so the caller can persist it. */
    fun installResize(onResized: (Size) -> Unit, onStart: () -> Unit = {}, onEnd: () -> Unit = {}) {
        val inset = UIScale.scale(resizeHandle)
        ComponentResizer.builder()
            .dragInsets(Insets(inset, inset, inset, inset))
            .minimumSize(window.minimumSize)
            .onResizeStart { onStart() }
            .onResizeEnd {
                onResized(Size(window.width, window.height))
                onEnd()
            }
            .build()
            .register(window)
    }

    /**
     * Escape handling.
     *
     * [onEscape] returns true when it consumed the key — a popup showing a drilled-in view uses
     * that to step back out rather than closing and discarding what the user was looking at.
     */
    fun installEscape(onEscape: () -> Boolean) {
        window.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ESCAPE_ACTION)
        window.rootPane.actionMap.put(ESCAPE_ACTION, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                onEscape()
            }
        })
    }

    /**
     * The border that marks a pinned popup.
     *
     * Rebuilt on each call rather than stored, because a border keeps the colour it was handed and
     * a look-and-feel change does not revisit it.
     */
    fun applyPinBorder(pinned: Boolean, target: JComponent = window.rootPane) {
        target.border = if (pinned) {
            BorderFactory.createLineBorder(accentColor, pinnedBorderWidth)
        } else {
            val coloured = 2
            val padding = (pinnedBorderWidth - coloured).coerceAtLeast(0)
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, coloured),
                BorderFactory.createEmptyBorder(padding, padding, padding, padding)
            )
        }
        target.revalidate()
        target.repaint()
    }

    /**
     * Places the window near the pointer, kept fully on the screen it appears on.
     *
     * Does nothing once the user has moved it themselves.
     */
    fun positionNearMouse(offset: Int = 12) {
        if (wasManuallyMoved) return
        val screen = window.graphicsConfiguration?.bounds ?: return centreOnOwner()
        val pointer = MouseInfo.getPointerInfo()?.location ?: return centreOnOwner()
        val scaled = UIScale.scale(offset)

        // Flipped to the other side of the pointer when it will not fit, rather than clamped to
        // the screen edge: clamping slides the popup back under the cursor, which then sits on
        // top of the first thing the reader wants to look at. Taken from the dictionary popup,
        // which had the better of the two behaviours.
        var x = pointer.x + scaled
        var y = pointer.y + scaled
        if (x + window.width > screen.x + screen.width) x = pointer.x - window.width - scaled
        if (y + window.height > screen.y + screen.height) y = pointer.y - window.height - scaled

        window.setLocation(x.coerceAtLeast(screen.x), y.coerceAtLeast(screen.y))
    }

    /**
     * Places the window beside the owner, falling back to its other side when there is no room.
     *
     * Used when the popup was opened by the application rather than by a gesture, where appearing
     * at the pointer would put it wherever the mouse happened to be resting.
     */
    fun positionBesideOwner(gap: Int = 8) {
        if (wasManuallyMoved) return
        val screen = window.graphicsConfiguration?.bounds ?: return centreOnOwner()
        val bounds = owner.bounds
        val scaled = UIScale.scale(gap)

        var x = bounds.x + bounds.width + scaled
        if (x + window.width > screen.x + screen.width) x = bounds.x - window.width - scaled
        val y = bounds.y + (bounds.height - window.height) / 2

        window.setLocation(
            x.coerceIn(screen.x, (screen.x + screen.width - window.width).coerceAtLeast(screen.x)),
            y.coerceIn(screen.y, (screen.y + screen.height - window.height).coerceAtLeast(screen.y))
        )
    }

    fun applyStoredPosition(position: Position) {
        if (wasManuallyMoved) return
        window.setLocation(position.x, position.y)
    }

    fun centreOnOwner() = window.setLocationRelativeTo(owner)

    /** Forgets that the window was dragged, so the next open positions itself again. */
    fun resetManualMove() {
        wasManuallyMoved = false
    }

    /**
     * Re-runs [onThemeChanged] whenever the look and feel changes.
     *
     * Held in a field so [uninstallTheme] can detach it: `UIManager` keeps its listeners for the
     * life of the process, and an inline lambda could never be removed at all.
     */
    fun installTheme(onThemeChanged: () -> Unit) {
        uninstallTheme()
        val listener = PropertyChangeListener { event ->
            if (event.propertyName == "lookAndFeel") SwingUtilities.invokeLater(onThemeChanged)
        }
        themeListener = listener
        UIManager.addPropertyChangeListener(listener)
    }

    fun uninstallTheme() {
        themeListener?.let { UIManager.removePropertyChangeListener(it) }
        themeListener = null
    }

    // ── Fading, idle-hide, and pointer presence ───────────────────────────────
    //
    // These were written twice, identically enough that both copies carried the same two bugs:
    // an idle-close timer nothing held a reference to, and a countdown that no amount of typing
    // would reset. One implementation, fixed once.

    private var fadeTimer: Timer? = null
    private var idleTimer: Timer? = null
    private var idleCloseTimer: Timer? = null
    private var exitDebounceTimer: Timer? = null
    private var pointerListener: AWTEventListener? = null

    private var idleDelayMs: () -> Int = { 8_000 }
    private var idleFadeMs: Int = 160
    private var restingOpacity: () -> Float = { 1f }
    private var onIdleExpired: () -> Unit = {}

    /** Set by the owner whenever the user pins or unpins; nothing auto-hides while pinned. */
    var isPinned: Boolean = false

    /** True while the pointer is inside the window. */
    var isPointerOver: Boolean = false
        private set

    /**
     * @param delayMs read fresh on each start so a settings change takes effect immediately.
     * @param restingOpacity the opacity to return to once the pointer leaves.
     * @param onExpired how the owner dismisses itself. Route this through the application's
     *   state rather than hiding the window directly, or the rest of the app goes on believing
     *   the popup is open.
     */
    fun configureIdleHide(
        delayMs: () -> Int,
        fadeMs: Int = 160,
        restingOpacity: () -> Float,
        onExpired: () -> Unit
    ) {
        this.idleDelayMs = delayMs
        this.idleFadeMs = fadeMs
        this.restingOpacity = restingOpacity
        this.onIdleExpired = onExpired
    }

    fun startIdleHide() {
        stopIdleHide()
        if (isPinned) return
        idleTimer = Timer(idleDelayMs().coerceAtLeast(500)) { event ->
            (event.source as Timer).stop()
            if (isPinned) return@Timer
            fadeTo(0f, idleFadeMs)
            // Held, so stopIdleHide can cancel it. Left anonymous, a popup that had begun fading
            // closed itself regardless of the user moving back onto it or reopening it.
            idleCloseTimer = Timer(idleFadeMs + 20) { closeEvent ->
                (closeEvent.source as Timer).stop()
                if (!isPinned) onIdleExpired()
            }.apply { isRepeats = false; start() }
        }.apply { isRepeats = false; start() }
    }

    fun stopIdleHide() {
        idleTimer?.stop()
        idleCloseTimer?.stop()
        idleCloseTimer = null
    }

    /** Restarts the countdown because the user did something — typing counts. */
    fun noteActivity() {
        if (window.isVisible && !isPinned) startIdleHide()
    }

    /**
     * Animates opacity, cancelling any fade already running.
     *
     * Restarting rather than refusing while one is in flight: an earlier version guarded against
     * re-entry and so dropped the fade back to transparency whenever the mouse entered and left
     * within a single animation.
     */
    fun fadeTo(target: Float, durationMs: Int = idleFadeMs) {
        fadeTimer?.stop()
        val start = window.opacity
        val steps = FADE_STEPS
        val delay = (durationMs / steps).coerceAtLeast(10)
        var step = 0
        fadeTimer = Timer(delay) { event ->
            step++
            val value = start + (target - start) * (step.toFloat() / steps)
            if (abs(window.opacity - value) > 0.01f) window.opacity = value.coerceIn(0f, 1f)
            if (step >= steps) (event.source as Timer).stop()
        }.apply { isRepeats = true; start() }
    }

    /**
     * Watches the pointer globally so the popup can wake when it is hovered.
     *
     * Global because the popup must react to a pointer that never enters any of its components —
     * crossing the window edge is enough. Screen bounds are compared directly rather than
     * converting coordinates, which rounds and makes the state oscillate at the border.
     */
    fun installPointerTracking(exitDebounceMs: Int = 120) {
        if (pointerListener != null) return
        val listener = AWTEventListener { event ->
            val mouse = event as? MouseEvent ?: return@AWTEventListener
            if (mouse.id != MouseEvent.MOUSE_MOVED &&
                mouse.id != MouseEvent.MOUSE_ENTERED &&
                mouse.id != MouseEvent.MOUSE_EXITED
            ) return@AWTEventListener

            SwingUtilities.invokeLater {
                if (!window.isVisible) return@invokeLater
                val pointer = MouseInfo.getPointerInfo()?.location ?: return@invokeLater
                val over = window.bounds.contains(pointer)
                if (over == isPointerOver) return@invokeLater
                isPointerOver = over

                if (over) {
                    exitDebounceTimer?.stop()
                    stopIdleHide()
                    fadeTo(1f)
                } else {
                    // Debounced, so a wiggle across the border does not flicker the window.
                    exitDebounceTimer?.stop()
                    exitDebounceTimer = Timer(exitDebounceMs) { timerEvent ->
                        (timerEvent.source as Timer).stop()
                        if (!isPointerOver && !isPinned) {
                            fadeTo(restingOpacity())
                            startIdleHide()
                        }
                    }.apply { isRepeats = false; start() }
                }
            }
        }
        pointerListener = listener
        Toolkit.getDefaultToolkit()
            .addAWTEventListener(listener, AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK)
        samplePointer()
    }

    /**
     * Reads where the pointer is right now, rather than waiting to be told it moved.
     *
     * These popups open at the cursor, so the pointer is frequently inside one the instant it
     * appears — and then never generates an enter event, because it never crossed the boundary.
     * The popup therefore believed the pointer was elsewhere and counted down to hiding itself
     * while the user was sitting over it, reading.
     */
    fun samplePointer() {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        isPointerOver = window.isVisible && window.bounds.contains(pointer)
        if (isPointerOver) stopIdleHide()
    }

    fun uninstallPointerTracking() {
        exitDebounceTimer?.stop()
        exitDebounceTimer = null
        pointerListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        pointerListener = null
        isPointerOver = false
    }

    /** Everything a popup must let go of when it is hidden. */
    fun onHidden() {
        stopIdleHide()
        fadeTimer?.stop()
        uninstallPointerTracking()
    }

    private companion object {
        const val ESCAPE_ACTION = "floating-popup-escape"
        const val FADE_STEPS = 8
    }
}
