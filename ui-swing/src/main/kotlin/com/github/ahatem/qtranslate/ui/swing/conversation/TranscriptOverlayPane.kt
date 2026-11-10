package com.github.ahatem.qtranslate.ui.swing.conversation

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JScrollPane

/**
 * Hosts [transcriptScroll] and overlays [pill] centred horizontally near the top edge
 * of the transcript viewport without taking part in layout flow or displacing the transcript.
 */
class TranscriptOverlayPane(
    val transcriptScroll: JScrollPane,
    val pill: JComponent,
    val banners: List<JComponent> = emptyList()
) : JLayeredPane() {

    init {
        isOpaque = false
        // Layer 0: normal component layer for scrollpane
        add(transcriptScroll, DEFAULT_LAYER)
        // Layer 300: modal/popup layer so the pill and banners sit clearly above the transcript contents
        add(pill, POPUP_LAYER)
        banners.forEach { banner ->
            add(banner, POPUP_LAYER)
            banner.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentShown(e: java.awt.event.ComponentEvent?) {
                    doLayout()
                    repaint()
                }

                override fun componentHidden(e: java.awt.event.ComponentEvent?) {
                    doLayout()
                    repaint()
                }
            })
        }
        layout = OverlayLayout()

        pill.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentShown(e: java.awt.event.ComponentEvent?) {
                doLayout()
                repaint()
            }

            override fun componentHidden(e: java.awt.event.ComponentEvent?) {
                doLayout()
                repaint()
            }
        })
        pill.addPropertyChangeListener("text") {
            doLayout()
            repaint()
        }
    }

    private inner class OverlayLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension =
            transcriptScroll.preferredSize ?: Dimension(100, 100)

        override fun minimumLayoutSize(parent: Container): Dimension =
            transcriptScroll.minimumSize ?: Dimension(50, 50)

        override fun layoutContainer(parent: Container) {
            val insets = parent.insets
            val w = parent.width - insets.left - insets.right
            val h = parent.height - insets.top - insets.bottom

            // 1. Fill entire layered pane with the scrollpane
            transcriptScroll.setBounds(insets.left, insets.top, w, h)

            val viewportBounds = transcriptScroll.viewport.bounds
            val scrollBarW = if (transcriptScroll.verticalScrollBar.isVisible) transcriptScroll.verticalScrollBar.width else 0
            val availW = (w - scrollBarW).coerceAtLeast(32)

            // 2. Position visible banners stacked at the top edge of the viewport
            var bannerY = insets.top + viewportBounds.y + 2
            banners.forEach { banner ->
                if (banner.isVisible) {
                    val pref = banner.preferredSize
                    val bannerW = availW - 12
                    val bannerH = pref.height.coerceAtLeast(24)
                    banner.setBounds(insets.left + viewportBounds.x + 6, bannerY, bannerW, bannerH)
                    bannerY += bannerH + 4
                }
            }

            // 3. Position pill over top edge of viewport (below any visible banners)
            val pillPref = pill.preferredSize
            val pillW = pillPref.width.coerceAtLeast(32)
            val pillH = pillPref.height.coerceAtLeast(20)

            val pillX = insets.left + viewportBounds.x + (availW - pillW) / 2
            val pillY = if (banners.any { it.isVisible }) bannerY + 2 else insets.top + viewportBounds.y + 6

            pill.setBounds(pillX, pillY, pillW, pillH)
        }
    }
}
