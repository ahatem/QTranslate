package com.github.ahatem.qtranslate.ui.swing.conversation

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * The translation popup card for a hovered word or selected phrase.
 *
 * Clearly displays the translated word in a focused translation box.
 * The full sentence translation is separated into an optional context section
 * so single-word translations are never confused with whole-sentence translations.
 */
class HoverTranslationPopup(owner: Window, private val strings: ConversationStrings) {

    data class Content(
        val text: String,
        val translation: String? = null,
        val error: String? = null,
        /** Full sentence translation, shown only when requested or when translating a phrase. */
        val context: String? = null,
        val contextLoading: Boolean = false,
        val hasContextSentence: Boolean = false,
        /** Null until requested; empty when the dictionary found nothing. */
        val meanings: List<String>? = null,
        val meaningsLoading: Boolean = false,
        val canPlayWord: Boolean = false,
        val canPlaySentence: Boolean = false
    )

    var onPlayWord: () -> Unit = {}
    var onPlaySentence: () -> Unit = {}
    var onShowSentence: () -> Unit = {}
    var onMeanings: () -> Unit = {}
    var onCopy: () -> Unit = {}
    /** Called when the pointer enters (true) or leaves (false) the card. */
    var onHoverChanged: (Boolean) -> Unit = {}

    private val window = JWindow(owner).apply {
        focusableWindowState = false
        type = Window.Type.POPUP
    }

    private val source = JLabel()
    private val translation = JLabel()
    private val context = JLabel()
    private val meanings = JLabel()

    private val translationCard = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color(0x444444), 1, true),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        )
        isOpaque = false
        add(translation, BorderLayout.CENTER)
    }

    private val contextCard = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color(0x444444)),
            BorderFactory.createEmptyBorder(6, 4, 4, 4)
        )
        isOpaque = false
        add(context, BorderLayout.CENTER)
    }

    private val playWord = JButton(strings.playWord, FlatSVGIcon(Icons.PLAY, 12, 12))
    private val playSentence = JButton(strings.playSentence, FlatSVGIcon(Icons.PLAY, 12, 12))
    private val sentenceButton = JButton(strings.inContext)
    private val meaningsButton = JButton(strings.meanings)
    private val copyButton = JButton(strings.copy, FlatSVGIcon(Icons.COPY, 12, 12))

    private val panel = JPanel(MigLayout("wrap 1, insets 10 12 10 12, gap 6, wmax 430")).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.GRAY, 1, true),
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        )
        add(source)
        add(translationCard, "growx")
        add(contextCard, "growx")
        add(meanings, "growx")
        add(JPanel(MigLayout("insets 4 0 0 0, gap 4")).apply {
            isOpaque = false
            listOf(playWord, playSentence, sentenceButton, meaningsButton, copyButton).forEach { button ->
                button.putClientProperty("JButton.buttonType", "toolBarButton")
                button.isFocusable = false
                add(button)
            }
        })
    }

    private var current: Content? = null

    init {
        window.contentPane = panel
        source.foreground = UIManager.getColor("Label.disabledForeground")
        context.foreground = UIManager.getColor("Label.disabledForeground")
        translation.font = translation.font.deriveFont(Font.BOLD, translation.font.size2D + 2f)

        playWord.addActionListener { onPlayWord() }
        playSentence.addActionListener { onPlaySentence() }
        sentenceButton.addActionListener { onShowSentence() }
        meaningsButton.addActionListener { onMeanings() }
        copyButton.addActionListener { onCopy() }

        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = onHoverChanged(true)
            override fun mouseExited(e: MouseEvent) {
                val point = SwingUtilities.convertPoint(e.component, e.point, panel)
                if (!panel.contains(point)) onHoverChanged(false)
            }
        }
        listOf(panel, source, translation, context, meanings, playWord, playSentence, sentenceButton, meaningsButton, copyButton)
            .forEach { it.addMouseListener(hover) }
    }

    val isVisible: Boolean get() = window.isVisible

    val text: String? get() = current?.text?.takeIf { window.isVisible }

    /** Shows the card just below [anchor] (screen coordinates), flipping above near the screen edge. */
    fun show(anchor: Rectangle, content: Content) {
        update(content)
        val screen = window.graphicsConfiguration?.bounds ?: owner().graphicsConfiguration.bounds
        val size = window.size
        var x = anchor.x
        var y = anchor.y + anchor.height + 6
        if (y + size.height > screen.y + screen.height) y = anchor.y - size.height - 6
        if (x + size.width > screen.x + screen.width) x = screen.x + screen.width - size.width - 4
        window.setLocation(x.coerceAtLeast(screen.x), y.coerceAtLeast(screen.y))
        window.isVisible = true
    }

    fun update(content: Content) {
        current = content
        source.text = html(content.text, bold = true)
        translation.text = html(
            when {
                content.error != null -> strings.failed(content.error)
                content.translation == null -> strings.loading
                else -> content.translation
            }
        )

        // Show context only when requested or loading
        val showContext = content.context != null || content.contextLoading
        contextCard.isVisible = showContext
        if (showContext) {
            context.text = when {
                content.contextLoading -> html(strings.loading, small = true)
                content.context != null -> html("${strings.inContext} ${content.context}", small = true)
                else -> ""
            }
        }

        meanings.isVisible = content.meanings != null || content.meaningsLoading
        meanings.text = when {
            content.meaningsLoading -> html(strings.loading, small = true)
            content.meanings.isNullOrEmpty() -> html(strings.noMeanings, small = true)
            else -> "<html><div style='width:360px'>" +
                content.meanings.joinToString("<br>") { "• ${escape(it)}" } + "</div></html>"
        }

        playWord.isVisible = content.canPlayWord
        playSentence.isVisible = content.canPlaySentence
        sentenceButton.isVisible = content.hasContextSentence && content.context == null
        sentenceButton.isEnabled = !content.contextLoading
        meaningsButton.isEnabled = content.meanings == null && !content.meaningsLoading
        copyButton.isEnabled = content.translation != null

        window.pack()
    }

    fun setPlayingState(playingWord: Boolean, playingSentence: Boolean) {
        playWord.icon = FlatSVGIcon(if (playingWord) Icons.STOP else Icons.PLAY, 12, 12)
        playWord.text = if (playingWord) strings.stop else strings.playWord
        playSentence.icon = FlatSVGIcon(if (playingSentence) Icons.STOP else Icons.PLAY, 12, 12)
        playSentence.text = if (playingSentence) strings.stop else strings.playSentence
    }

    fun hide() {
        window.isVisible = false
        current = null
    }

    fun dispose() = window.dispose()

    private fun owner(): Window = window.owner

    private fun html(text: String, bold: Boolean = false, small: Boolean = false): String {
        val body = escape(text).let { if (bold) "<b>$it</b>" else it }
        val size = if (small) "font-size:95%;" else ""
        val width = if (text.length > 40) "width:360px;" else ""
        return "<html><div style='$width$size'>$body</div></html>"
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
}
