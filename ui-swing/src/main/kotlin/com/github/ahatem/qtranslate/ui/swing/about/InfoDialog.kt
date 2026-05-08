package com.github.ahatem.qtranslate.ui.swing.about

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.*

class InfoDialog(owner: Frame) : JDialog(owner, true) {

    // ── Widgets ───────────────────────────────────────────────────────────────
    private val iconLabel     = JLabel()
    private val titleLabel    = JLabel()
    private val versionLabel  = JLabel()
    private val descLabel     = JLabel()    // HTML-wrapped, width-constrained
    private val linkLabel     = JLabel()
    private val supportButton = JButton()
    private val closeButton   = JButton()

    companion object {
        /** Inner text column width in pixels. Drives text wrapping and dialog width. */
        private const val CONTENT_W = 300
        /** Edge padding on each side. */
        private const val PAD = 28
    }

    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        isResizable = false

        // ── Root: fixed-width vertical column ────────────────────────────────
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(PAD, PAD, PAD - 4, PAD)
            // Fix width so the dialog never grows wider than CONTENT_W + padding.
            maximumSize = Dimension(CONTENT_W + PAD * 2, Int.MAX_VALUE)
        }
        contentPane = root

        // ── Helper — center any component in the column ────────────────────
        fun centered(comp: Component) = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            add(comp)
        }

        // ── Icon ──────────────────────────────────────────────────────────────
        iconLabel.alignmentX  = Component.CENTER_ALIGNMENT
        titleLabel.alignmentX = Component.CENTER_ALIGNMENT
        versionLabel.alignmentX = Component.CENTER_ALIGNMENT
        descLabel.alignmentX = Component.CENTER_ALIGNMENT
        linkLabel.alignmentX = Component.CENTER_ALIGNMENT

        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2")
        versionLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "medium")
        versionLabel.foreground = UIManager.getColor("Label.disabledForeground")
        linkLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        // Style the support button as a filled accent (FlatLaf "default" type).
        supportButton.putClientProperty(BUTTON_TYPE, "default")
        supportButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        root.add(centered(iconLabel))
        root.add(Box.createVerticalStrut(16))

        root.add(titleLabel)
        root.add(Box.createVerticalStrut(4))

        root.add(centered(versionLabel))
        root.add(Box.createVerticalStrut(16))

        // Thin separator under header
        root.add(makeSeparator())
        root.add(Box.createVerticalStrut(16))

        root.add(descLabel)
        root.add(Box.createVerticalStrut(10))

        root.add(centered(linkLabel))

        // ── Footer ────────────────────────────────────────────────────────────
        root.add(Box.createVerticalStrut(20))
        root.add(makeSeparator())
        root.add(Box.createVerticalStrut(16))

        // Buttons side-by-side, centred
        val btnRow = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
        }
        btnRow.add(supportButton)
        btnRow.add(closeButton)
        root.add(btnRow)

        closeButton.addActionListener { isVisible = false }
        rootPane.defaultButton = closeButton
    }

    // ── Show ──────────────────────────────────────────────────────────────────

    fun showDialog(state: InfoDialogState) {
        title          = state.title
        titleLabel.text  = state.appName
        versionLabel.text = state.versionText
        iconLabel.icon   = state.icon

        // Description — wrap at CONTENT_W, centred via HTML.
        val safe = state.descriptionHtml
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        descLabel.text =
            "<html><body style='width:${CONTENT_W}px; text-align:center;'>$safe</body></html>"

        // Website link
        if (state.websiteUrl.isNotBlank()) {
            linkLabel.text = "<html><a href=''>${state.websiteUrl}</a></html>"
            linkLabel.mouseListeners.forEach { linkLabel.removeMouseListener(it) }
            linkLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = openUrl(state.websiteUrl)
            })
            linkLabel.isVisible = true
        } else {
            linkLabel.isVisible = false
        }

        // Support button
        if (state.supportUrl.isNotBlank() && state.supportButtonText.isNotBlank()) {
            supportButton.text = state.supportButtonText
            supportButton.actionListeners.forEach { supportButton.removeActionListener(it) }
            supportButton.addActionListener { openUrl(state.supportUrl) }
            supportButton.isVisible = true
        } else {
            supportButton.isVisible = false
        }

        closeButton.text = state.closeButtonText

        pack()
        setLocationRelativeTo(owner)
        isVisible = true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeSeparator(): Component = object : JComponent() {
        init {
            maximumSize   = Dimension(Int.MAX_VALUE, 1)
            preferredSize = Dimension(CONTENT_W, 1)
            alignmentX    = Component.CENTER_ALIGNMENT
        }
        override fun paintComponent(g: Graphics) {
            val c = UIManager.getColor("Separator.foreground") ?: Color(80, 80, 80)
            g.color = Color(c.red, c.green, c.blue, 80)
            g.drawLine(0, 0, width, 0)
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            ) Desktop.getDesktop().browse(URI(url))
        }
    }
}
