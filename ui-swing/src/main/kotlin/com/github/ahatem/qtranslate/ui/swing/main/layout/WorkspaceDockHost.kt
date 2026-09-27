package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.util.UIScale
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.LayoutManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * The main window's outer arrangement: the translation workspace, and beside it, when asked for
 * and when there is room, a dock for the lookup tools.
 *
 * Built for exactly that and nothing more general. The dock sits at the logical line end, so it is
 * on the right in a left-to-right interface and on the left in a right-to-left one, from one
 * layout that reads the component orientation rather than from two. Between the two is a single
 * draggable divider: a thin line inside a wider strip that carries the resize cursor, so the target
 * is easy to hit and the boundary stays quiet.
 *
 * The dock width is remembered for the session, and clamped when it is applied, never when it is
 * remembered. Shrinking the window and growing it again therefore gives back the width the user
 * chose, while neither region can ever be dragged or squeezed below the width it needs.
 *
 * When the window is too narrow for both, the dock is not shown and [canDock] says so; nothing else
 * changes, so the workspace is never crushed to make room. What to do about a lookup asked for in
 * that state is the owner's decision.
 */
class WorkspaceDockHost(
    private val workspace: JComponent,
    private val dock: JComponent,
) : JPanel(null) {

    private val divider = DockDivider()

    /** Whether the dock has been asked for. It is shown only while [canDock] as well. */
    var isDockVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    /** Called, after layout, whenever the window crosses between having room for the dock and not. */
    var onDockabilityChanged: ((canDock: Boolean) -> Unit)? = null

    /** The dock width the user last chose this session, or 0 until they have chosen one. */
    var rememberedDockWidth: Int = 0
        private set

    private var lastCanDock: Boolean? = null

    init {
        isOpaque = false
        layout = DockLayout()
        add(workspace)
        add(divider)
        add(dock)
        divider.isVisible = false
        dock.isVisible = false
    }

    /** Whether the window is wide enough for the workspace and the dock together. */
    val canDock: Boolean get() = width <= 0 || width >= dockingThreshold()

    /** Whether the dock is on screen right now. */
    val isDockPresented: Boolean get() = isDockVisible && canDock

    /** The width the dock has, or would have if it were shown, at the current window width. */
    val effectiveDockWidth: Int get() = clampedDockWidth(width)

    /** The window width below which there is no room for the dock. */
    fun dockingThreshold(): Int =
        UISpacing.WORKSPACE_MIN_WIDTH + UISpacing.DOCK_DIVIDER_HIT_WIDTH + UISpacing.LOOKUP_DOCK_MIN_WIDTH

    /** Sets the dock width, as the user dragging the divider would. */
    fun setDockWidth(wanted: Int) {
        rememberedDockWidth = wanted.coerceAtLeast(UISpacing.LOOKUP_DOCK_MIN_WIDTH)
        revalidate()
    }

    /** The dock's edge is on the left in a right-to-left interface. */
    val isDockOnLeft: Boolean get() = !componentOrientation.isLeftToRight

    internal fun dividerForTest(): JComponent = divider

    private fun clampedDockWidth(hostWidth: Int): Int {
        val room = hostWidth - UISpacing.DOCK_DIVIDER_HIT_WIDTH
        val widest = (room - UISpacing.WORKSPACE_MIN_WIDTH).coerceAtLeast(UISpacing.LOOKUP_DOCK_MIN_WIDTH)
        val wanted = if (rememberedDockWidth > 0) rememberedDockWidth
        else (hostWidth * UISpacing.LOOKUP_DOCK_DEFAULT_SHARE).toInt()
        return wanted.coerceIn(UISpacing.LOOKUP_DOCK_MIN_WIDTH, widest)
    }

    private fun announceDockability() {
        val now = canDock
        if (lastCanDock == now) return
        val first = lastCanDock == null
        lastCanDock = now
        // Not for the first layout: a window that opens too narrow has not crossed anything.
        if (!first) SwingUtilities.invokeLater { onDockabilityChanged?.invoke(now) }
    }

    private inner class DockLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension {
            val base = workspace.preferredSize
            if (!isDockPresented) return Dimension(base)
            val dockWidth = rememberedDockWidth.takeIf { it > 0 } ?: UISpacing.LOOKUP_DOCK_MIN_WIDTH
            return Dimension(base.width + UISpacing.DOCK_DIVIDER_HIT_WIDTH + dockWidth, base.height)
        }

        override fun minimumLayoutSize(parent: Container): Dimension {
            val base = workspace.minimumSize
            if (!isDockPresented) return Dimension(base)
            return Dimension(base.width + UISpacing.DOCK_DIVIDER_HIT_WIDTH + UISpacing.LOOKUP_DOCK_MIN_WIDTH, base.height)
        }

        override fun layoutContainer(parent: Container) {
            val w = parent.width
            val h = parent.height
            announceDockability()

            val presented = isDockVisible && w >= dockingThreshold()
            dock.isVisible = presented
            divider.isVisible = presented
            if (!presented) {
                workspace.setBounds(0, 0, w, h)
                return
            }

            val strip = UISpacing.DOCK_DIVIDER_HIT_WIDTH
            val dockWidth = clampedDockWidth(w)
            val workspaceWidth = w - strip - dockWidth
            if (isDockOnLeft) {
                dock.setBounds(0, 0, dockWidth, h)
                divider.setBounds(dockWidth, 0, strip, h)
                workspace.setBounds(dockWidth + strip, 0, workspaceWidth, h)
            } else {
                workspace.setBounds(0, 0, workspaceWidth, h)
                divider.setBounds(workspaceWidth, 0, strip, h)
                dock.setBounds(workspaceWidth + strip, 0, dockWidth, h)
            }
        }
    }

    /**
     * The boundary between the workspace and the dock.
     *
     * The pointer is followed directly: the dock width is derived from where the pointer is in the
     * host, less the distance from the strip's edge at which it was grabbed, so the divider stays
     * under the pointer whichever side the dock is on and however the window was laid out.
     */
    private inner class DockDivider : JComponent() {
        private var grabOffset = 0

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    grabOffset = e.x
                }

                override fun mouseDragged(e: MouseEvent) {
                    val pointerInHost = e.x + x
                    val edge = pointerInHost - grabOffset
                    val strip = UISpacing.DOCK_DIVIDER_HIT_WIDTH
                    val wanted = if (isDockOnLeft) edge else this@WorkspaceDockHost.width - strip - edge
                    setDockWidth(wanted)
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
        }

        override fun paintComponent(g: Graphics) {
            val line = UIManager.getColor("Separator.foreground")
                ?: UIManager.getColor("Component.borderColor")
                ?: Color.GRAY
            val thickness = UIScale.scale(1f).toInt().coerceAtLeast(1)
            g.color = line
            g.fillRect((width - thickness) / 2, 0, thickness, height)
        }
    }
}
