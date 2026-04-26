package com.github.ahatem.qtranslate.ui.swing.about

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class InfoDialog(owner: Frame) : JDialog(owner, true) {

    private val iconLabel = JLabel()
    private val titleLabel = JLabel()
    private val versionLabel = JLabel()
    private val descriptionLabel = JLabel()

    private val linkLabel = JLabel()
    private val closeButton = JButton()

    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        isResizable = false

        val rootPanel = JPanel()
        contentPane = rootPanel
        val grid = GridBag(rootPanel)

        val edgePadding = 24 // Generous padding from window edges
        val sectionGap = 20  // Larger gap between Header <-> Body <-> Footer
        val titleGap = 4     // Small gap between title and version
        val textGap = 12     // Standard gap between paragraphs/links

        iconLabel.horizontalAlignment = SwingConstants.CENTER
        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h1")
        versionLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "medium")
        versionLabel.foreground = UIManager.getColor("Label.disabledForeground")
        linkLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        grid.defaultAnchor(GridBagConstraints.CENTER)
            .defaultFill(GridBagConstraints.HORIZONTAL)

            // --- Header Section ---
            .insets(top = edgePadding, left = edgePadding, bottom = 0, right = edgePadding)
            .add(iconLabel)

            .nextRow().insets(top = sectionGap, left = edgePadding, bottom = 0, right = edgePadding)
            .add(titleLabel)

            .nextRow().insets(top = titleGap, left = edgePadding, bottom = 0, right = edgePadding)
            .add(versionLabel)

            // --- Body Section ---
            .nextRow().insets(top = sectionGap, left = edgePadding, bottom = 0, right = edgePadding)
            .add(descriptionLabel)

            .nextRow().insets(top = textGap, left = edgePadding, bottom = 0, right = edgePadding)
            .add(linkLabel)

            // --- Footer Section ---
            .nextRow().insets(top = sectionGap, left = edgePadding, bottom = edgePadding, right = edgePadding)
            .fill(GridBagConstraints.NONE) // Do not stretch the button
            .add(closeButton)

        closeButton.addActionListener { isVisible = false }
        rootPane.defaultButton = closeButton
    }

    fun showDialog(state: InfoDialogState) {
        title = state.title
        titleLabel.text = state.appName
        versionLabel.text = state.versionText
        iconLabel.icon = state.icon
        descriptionLabel.text = "<html><div style='text-align: center;'>${state.descriptionHtml.replace("\n", "<br>")}</div></html>"

        if (state.websiteUrl.isNotBlank()) {
            linkLabel.text = "<html><a href=''>${state.websiteUrl}</a></html>"
            linkLabel.mouseListeners.forEach { linkLabel.removeMouseListener(it) }
            linkLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    try {
                        Desktop.getDesktop().browse(java.net.URI(state.websiteUrl))
                    } catch (_: Exception) {
                    }
                }
            })
            linkLabel.isVisible = true
        } else {
            linkLabel.isVisible = false
        }
        closeButton.text = state.closeButtonText

        pack()
        setLocationRelativeTo(owner)
        isVisible = true
    }
}