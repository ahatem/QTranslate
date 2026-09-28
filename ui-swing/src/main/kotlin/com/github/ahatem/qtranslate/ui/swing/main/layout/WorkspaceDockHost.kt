package com.github.ahatem.qtranslate.ui.swing.main.layout

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

/**
 * The main window's outer arrangement: the translation workspace, and beside it, when asked for,
 * a dock for the lookup tools.
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
 * A requested dock is always shown: [isDockPresented] follows [isDockVisible] alone. A window too
 * narrow for [comfortableWidth] does not refuse the dock, it just gives both regions less than they
 * would like, down to a hard minimum each — see [UISpacing.WORKSPACE_HARD_MIN_WIDTH] and
 * [UISpacing.LOOKUP_DOCK_HARD_MIN_WIDTH]. [comfortableWidth] exists only for a caller that can grow
 * the window first, to decide whether doing so would help.
 */
class WorkspaceDockHost(
    private val workspace: JComponent,
    private val dock: JComponent,
) : JPanel(null) {

    private val divider = DockDivider()

    /** Whether the dock has been asked for. Presentation follows this alone. */
    var isDockVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    /** The dock width the user last chose this session, or 0 until they have chosen one. */
    var rememberedDockWidth: Int = 0
        private set

    init {
        isOpaque = false
        layout = DockLayout()
        add(workspace)
        add(divider)
        add(dock)
        divider.isVisible = false
        dock.isVisible = false
    }

    /** Whether the dock is on screen right now: exactly whether it was asked for. */
    val isDockPresented: Boolean get() = isDockVisible

    /** The width the dock has, or would have if it were shown, at the current window width. */
    val effectiveDockWidth: Int get() = clampedDockWidth(width)

    /**
     * The width at which the workspace and the dock both have comfortable room together.
     *
     * A caller that can resize the window (the main frame) uses this to decide whether growing it
     * would help before opening the dock. It plays no part in whether the dock is shown: below this
     * width the dock still opens, just narrower than either side would prefer.
     */
    fun comfortableWidth(): Int =
        UISpacing.WORKSPACE_COMFORTABLE_WIDTH + UISpacing.DOCK_DIVIDER_HIT_WIDTH + UISpacing.LOOKUP_DOCK_COMFORTABLE_WIDTH

    /** Sets the dock width, as the user dragging the divider would. */
    fun setDockWidth(wanted: Int) {
        rememberedDockWidth = wanted.coerceAtLeast(UISpacing.LOOKUP_DOCK_HARD_MIN_WIDTH)
        revalidate()
    }

    /** The dock's edge is on the left in a right-to-left interface. */
    val isDockOnLeft: Boolean get() = !componentOrientation.isLeftToRight

    internal fun dividerForTest(): JComponent = divider

    private fun clampedDockWidth(hostWidth: Int): Int {
        val room = hostWidth - UISpacing.DOCK_DIVIDER_HIT_WIDTH
        val widest = (room - UISpacing.WORKSPACE_HARD_MIN_WIDTH).coerceAtLeast(UISpacing.LOOKUP_DOCK_HARD_MIN_WIDTH)
        val wanted = if (rememberedDockWidth > 0) rememberedDockWidth
        else (hostWidth * UISpacing.LOOKUP_DOCK_DEFAULT_SHARE).toInt()
        return wanted.coerceIn(UISpacing.LOOKUP_DOCK_HARD_MIN_WIDTH, widest)
    }

    private inner class DockLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension {
            val base = workspace.preferredSize
            if (!isDockPresented) return Dimension(base)
            val dockWidth = rememberedDockWidth.takeIf { it > 0 } ?: UISpacing.LOOKUP_DOCK_COMFORTABLE_WIDTH
            return Dimension(base.width + UISpacing.DOCK_DIVIDER_HIT_WIDTH + dockWidth, base.height)
        }

        override fun minimumLayoutSize(parent: Container): Dimension {
            val base = workspace.minimumSize
            if (!isDockPresented) return Dimension(base)
            return Dimension(base.width + UISpacing.DOCK_DIVIDER_HIT_WIDTH + UISpacing.LOOKUP_DOCK_HARD_MIN_WIDTH, base.height)
        }

        override fun layoutContainer(parent: Container) {
            val w = parent.width
            val h = parent.height

            dock.isVisible = isDockPresented
            divider.isVisible = isDockPresented
            if (!isDockPresented) {
                workspace.setBounds(0, 0, w, h)
                return
            }

            val strip = UISpacing.DOCK_DIVIDER_HIT_WIDTH
            val dockWidth = clampedDockWidth(w)
            val workspaceWidth = (w - strip - dockWidth).coerceAtLeast(0)
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
     *
     * Painted by [BoundaryDivider]: a hairline at rest that thickens to the theme's accent colour on
     * hover and while dragging, inside the same fixed-width strip so the hover state never moves or
     * resizes either region. It marks where the workspace ends, so unlike the workspace's own
     * internal splits ([WorkspaceGripDivider]) it is a line, not a grip.
     */
    private inner class DockDivider : JComponent() {
        private var grabOffset = 0
        private var hovered = false
        private var dragging = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
            val mouse = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }

                override fun mousePressed(e: MouseEvent) {
                    grabOffset = e.x
                    dragging = true
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    dragging = false
                    repaint()
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
            BoundaryDivider.paint(g, width, height, vertical = true, active = hovered || dragging)
        }
    }
}
