package com.github.ahatem.qtranslate.ui.swing.update

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.UIManager
import javax.swing.event.HyperlinkEvent

class UpdateDialog(owner: Frame) : JDialog(owner, false) {

    // ------------------------------------------------------------------ UI
    private val versionBanner = JLabel().apply {
        putClientProperty(FlatClientProperties.STYLE_CLASS, "h3")
        horizontalAlignment = JLabel.CENTER
    }
    private val subLabel = JLabel().apply {
        horizontalAlignment = JLabel.CENTER
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val notesPane = JEditorPane("text/html", "").apply {
        isEditable = false
        isOpaque = true
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIManager.getFont("Label.font")
        addHyperlinkListener { ev ->
            if (ev.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val uri = runCatching { ev.url?.toURI() }.getOrNull()
                    ?: runCatching { URI(ev.description) }.getOrNull()
                uri?.let { openUrl(it) }
            }
        }
    }
    private val skipButton = JButton()
    private val remindLaterButton = JButton()
    private val downloadButton = JButton().apply {
        putClientProperty("JButton.buttonType", "default")
    }
    private val viewOnGitHubButton = JButton()

    // ------------------------------------------------------------------ state
    private var downloadUrl: String? = null
    private var releaseUrl: String? = null
    private var onSkip: (() -> Unit)? = null
    private var onRemindLater: (() -> Unit)? = null

    // ------------------------------------------------------------------ markdown parser (lazy, thread-local)
    private val mdParser: Parser = Parser.builder().build()
    private val mdRenderer: HtmlRenderer = HtmlRenderer.builder()
        .attributeProviderFactory { _ ->
            AttributeProvider { node: Node, _, attributes ->
                when (node) {
                    // Open links in system browser
                    is Link -> attributes["target"] = "_blank"
                    // Images: JEditorPane can't render remote images well —
                    // replace the src with the destination URL (so HyperlinkListener fires),
                    // keep the alt text visible, and set a border so it looks like a link.
                    is Image -> {
                        attributes["src"] = node.destination ?: ""
                        val alt = attributes["alt"]?.takeIf { it.isNotBlank() }
                            ?: node.title?.takeIf { it.isNotBlank() }
                            ?: "image"
                        attributes["alt"] = "[$alt]"
                        attributes["title"] = node.destination ?: ""
                    }
                }
            }
        }
        .build()

    // ------------------------------------------------------------------ init
    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        isResizable = true

        // Header panel
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(8, 0, 12, 0)
            add(versionBanner.also { it.alignmentX = JLabel.CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(4))
            add(subLabel.also { it.alignmentX = JLabel.CENTER_ALIGNMENT })
        }

        // Notes scroll pane
        val notesScroll = JScrollPane(notesPane).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(""),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            )
            preferredSize = Dimension(UIScale.scale(520), UIScale.scale(260))
        }

        // Button row: [Skip] [Remind Later]  <glue>  [View on GitHub] [Download]
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(skipButton)
            add(Box.createHorizontalStrut(UIScale.scale(6)))
            add(remindLaterButton)
            add(Box.createHorizontalGlue())
            add(viewOnGitHubButton)
            add(Box.createHorizontalStrut(UIScale.scale(6)))
            add(downloadButton)
        }

        // Root content
        val content = JPanel(BorderLayout(0, UIScale.scale(10))).apply {
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(20), UIScale.scale(24),
                UIScale.scale(20), UIScale.scale(24)
            )
            add(headerPanel, BorderLayout.NORTH)
            add(notesScroll, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }

        contentPane = content

        // Wire actions
        skipButton.addActionListener {
            onSkip?.invoke()
            isVisible = false
        }
        remindLaterButton.addActionListener {
            onRemindLater?.invoke()
            isVisible = false
        }
        downloadButton.addActionListener {
            downloadUrl?.let { openUrl(URI(it)) }
            isVisible = false
        }
        viewOnGitHubButton.addActionListener {
            releaseUrl?.let { openUrl(URI(it)) }
        }
    }

    // ------------------------------------------------------------------ public API
    fun show(state: UpdateDialogState) {
        title = state.title
        versionBanner.text = state.header
        subLabel.text = "<html>${state.details}</html>"

        notesPane.text = markdownToHtml(state.releaseNotes)
        notesPane.caretPosition = 0

        skipButton.text = state.skipButton
        remindLaterButton.text = state.remindLaterButton
        downloadButton.text = state.downloadButton
        viewOnGitHubButton.text = state.viewOnGitHubButton

        downloadUrl = state.downloadUrl
        releaseUrl = state.releaseUrl
        onSkip = state.onSkip
        onRemindLater = state.onRemindLater

        downloadButton.isEnabled = state.downloadUrl != null
        viewOnGitHubButton.isVisible = state.releaseUrl != null

        if (!isVisible) {
            pack()
            minimumSize = Dimension(UIScale.scale(480), UIScale.scale(380))
            setLocationRelativeTo(owner)
        }
        isVisible = true
        toFront()
    }

    // ------------------------------------------------------------------ Markdown → HTML
    private fun markdownToHtml(markdown: String): String {
        val document = mdParser.parse(markdown)
        val body = mdRenderer.render(document)

        val bg = colorToHex(UIManager.getColor("EditorPane.background") ?: Color.WHITE)
        val fg = colorToHex(UIManager.getColor("EditorPane.foreground") ?: Color.BLACK)
        val link = colorToHex(UIManager.getColor("Component.linkColor") ?: Color(0x2196F3))
        val codeBg = colorToHex(
            UIManager.getColor("TextField.disabledBackground")
                ?: UIManager.getColor("Panel.background")
                ?: Color.LIGHT_GRAY
        )
        val font = UIManager.getFont("Label.font") ?: Font("SansSerif", Font.PLAIN, 12)

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <style>
              body {
                font-family: '${font.family}', SansSerif;
                font-size: ${font.size}pt;
                color: $fg;
                background-color: $bg;
                margin: 0; padding: 4px 6px;
                line-height: 1.5;
              }
              a { color: $link; }
              code, pre {
                font-family: monospace;
                font-size: ${font.size - 1}pt;
                background-color: $codeBg;
                border-radius: 3px;
                padding: 1px 4px;
              }
              pre { padding: 8px; overflow-x: auto; }
              h1, h2, h3, h4 { margin-top: 12px; margin-bottom: 4px; }
              ul, ol { padding-left: 20px; margin: 4px 0; }
              li { margin-bottom: 2px; }
              hr { border: none; border-top: 1px solid $codeBg; margin: 8px 0; }
              blockquote { border-left: 3px solid $codeBg; margin: 8px 0 8px 12px; padding: 2px 8px; }
              img { max-width: 100%; }
              p { margin: 6px 0; }
            </style>
            </head>
            <body>
            $body
            </body>
            </html>
        """.trimIndent()
    }

    private fun colorToHex(c: Color): String =
        "#%02x%02x%02x".format(c.red, c.green, c.blue)

    private fun openUrl(uri: URI) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            runCatching { Desktop.getDesktop().browse(uri) }
        }
    }
}
