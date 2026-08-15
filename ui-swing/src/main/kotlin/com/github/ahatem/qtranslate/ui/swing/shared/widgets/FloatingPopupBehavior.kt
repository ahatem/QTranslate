package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.event.ActionEvent
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
import javax.swing.UIManager

/**
 * The window behaviour shared by the floating popups — translate, dictionary, images.
 *
 * ### Why a helper rather than a base class
 * The three popups agree about how their *window* behaves and disagree about everything inside
 * it, including deliberately: the image popup has no idle-hide, because a grid of pictures is
 * compared and clicked through while a definition is read in a couple of seconds. A base class
 * would have to expose each of those differences as a hook, and the fade and idle-hide logic in
 * the two older dialogs carries edge cases that were found the hard way — a mouse leaving during
 * a fade, a drag that must suspend the hide timer. Those stay where they were debugged. What
 * moves here is the part that was three identical copies.
 *
 * ### What it does not own
 * Fade animation, idle-hide, and mouse-over tracking. Two of the three popups have them; moving
 * them would mean rewriting working code for symmetry's sake.
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

    private companion object {
        const val ESCAPE_ACTION = "floating-popup-escape"
    }
}
