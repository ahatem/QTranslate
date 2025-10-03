package com.github.ahatem.qtranslate.presentation.main_frame.panels

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Configurations
import com.github.ahatem.qtranslate.presentation.components.QtTextPane
import com.github.ahatem.qtranslate.presentation.components.QtTextPaneListeners
import com.github.ahatem.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.utils.SimpleDocumentListener
import com.github.ahatem.qtranslate.utils.copyToClipboard
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.text.*

class UnderlineHighlighter : DefaultHighlighter() {
    override fun setDrawsLayeredHighlights(newValue: Boolean) {
        if (!newValue) throw IllegalArgumentException("UnderlineHighlighter only draws layered highlights")
        super.setDrawsLayeredHighlights(true)
    }

    class UnderlineHighlightPainter(private val color: Color? = null) : LayerPainter() {
        override fun paint(g: Graphics?, p0: Int, p1: Int, bounds: Shape?, c: JTextComponent?) = Unit

        override fun paintLayer(
            g: Graphics,
            offs0: Int,
            offs1: Int,
            bounds: Shape,
            c: JTextComponent,
            view: View
        ): Shape? {
            val r = getDrawingArea(offs0, offs1, bounds, view) ?: return null

            /*val y = r.y + r.height - 2
              val x1 = r.x
              val x2 = r.x + r.width
              val g2d = g as Graphics2D
              val prevStroke = g2d.stroke
              val newStroke = BasicStroke(2f)
              g2d.stroke = newStroke
              g.drawLine(x1, y, x2, y)
              g2d.stroke = prevStroke*/

            g.color = color ?: c.selectionColor
            val squiggle = 2
            val twoSquiggles = squiggle * 2
            val y = r.y + r.height - squiggle
            var x = r.x
            while (x <= r.x + r.width - twoSquiggles) {
                g.drawArc(x, y, squiggle, squiggle, 0, 180)
                g.drawArc(x + squiggle, y, squiggle, squiggle, 180, 181)
                x += twoSquiggles
            }
            return r
        }

        private fun getDrawingArea(offs0: Int, offs1: Int, bounds: Shape, view: View): Rectangle? {
            if (offs0 == view.startOffset && offs1 == view.endOffset) {
                return if (bounds is Rectangle) bounds else bounds.bounds
            } else {
                try {
                    val shape = view.modelToView(offs0, Position.Bias.Forward, offs1, Position.Bias.Backward, bounds)
                    return if (shape is Rectangle) shape else shape.bounds
                } catch (_: BadLocationException) {
                }
            }
            return null
        }
    }
}

class TranslationInputPanel : JPanel() {

    private val translateTimer = Timer(1000) {
        if (Configurations.instantTranslation) GlobalScope.launch { QTranslateViewModel.translate() }
    }.apply { isRepeats = false; }

    private val spellCheckTimer = Timer(1500) {
        if (Configurations.spellChecking) GlobalScope.launch { QTranslateViewModel.spellCheck() }
    }.apply { isRepeats = false; }

    val inputTextArea = QtTextPane().apply inputTextArea@{

        document.addDocumentListener(SimpleDocumentListener {
            QTranslateViewModel.setInputText(text)
            translateTimer.restart()
            spellCheckTimer.restart()
        })

        listener = object : QtTextPaneListeners {
            override fun createMenuHeader(event: MouseEvent): JMenu? {
                var spellingMenu: JMenu? = null
                val offset: Int = viewToModel2D(event.point)
                val highlights: Array<Highlighter.Highlight> = highlighter.highlights
                for (highlight in highlights) {
                    if (offset >= highlight.startOffset && offset < highlight.endOffset) {
                        val highlightedText =
                            document.getText(highlight.startOffset, highlight.endOffset - highlight.startOffset)
                        val spellCheckCorrection =
                            QTranslateViewModel.spells.value.corrections.find { it.originalWord == highlightedText }
                                ?: continue
                        spellingMenu = JMenu(Localizer.localize("menu_item_spelling"))
                        spellCheckCorrection.suggestions.forEach { suggestion ->
                            spellingMenu.add(JMenuItem(suggestion).apply {
                                addActionListener {
                                    document.remove(highlight.startOffset, highlight.endOffset - highlight.startOffset)
                                    document.insertString(highlight.startOffset, suggestion, null)
                                }
                            })
                        }
                    }
                }
                return spellingMenu
            }

            override fun onMenuItemTranslateClicked(selectedText: String) {
                GlobalScope.launch { QTranslateViewModel.translate(selectedText) }
            }

            override fun onMenuItemListenClicked(selectedText: String) {
                GlobalScope.launch { QTranslateViewModel.listenToInput(selectedText) }
            }
        }

    }

    init {
        layout = BorderLayout()

        val scrollPane = JScrollPane(inputTextArea)
        val buttonsPanel = TranslationActionsPanel().apply {
            copyButton.addActionListener { inputTextArea.text.copyToClipboard() }
            listenButton.addActionListener(WindowKeyListeners.ListenToInput.action)
        }

        add(buttonsPanel, BorderLayout.LINE_END)
        add(scrollPane)
    }
}