package com.pnix.qtranslate.presentation.main_frame.panels

import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.copyToClipboard
import com.pnix.qtranslate.utils.isRTL
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.*
import javax.swing.text.DefaultHighlighter.DefaultHighlightPainter
import javax.swing.undo.UndoManager


class SquigglePainter(color: Color?) : DefaultHighlightPainter(color) {
  override fun paintLayer(
    g: Graphics,
    offs0: Int,
    offs1: Int,
    bounds: Shape,
    c: JTextComponent,
    view: View
  ): Rectangle? {
    val r = getDrawingArea(offs0, offs1, bounds, view) ?: return null

    //  Do your custom painting
    val color = color
    g.color = color ?: c.selectionColor

    //  Draw the squiggles
    val squiggle = 2
    val twoSquiggles = squiggle * 2
    val y = r.y + r.height - squiggle
    var x = r.x
    while (x <= r.x + r.width - twoSquiggles) {
      g.drawArc(x, y, squiggle, squiggle, 0, 180)
      g.drawArc(x + squiggle, y, squiggle, squiggle, 180, 181)
      x += twoSquiggles
    }

    // Return the drawing area
    return r
  }

  private fun getDrawingArea(offs0: Int, offs1: Int, bounds: Shape, view: View): Rectangle? {
    // Contained in view, can just use bounds.
    if (offs0 == view.startOffset && offs1 == view.endOffset) {
      val alloc: Rectangle = if (bounds is Rectangle) {
        bounds
      } else {
        bounds.bounds
      }
      return alloc
    } else {
      // Should only render part of View.
      try {
        // --- determine locations ---
        val shape = view.modelToView(
          offs0,
          Position.Bias.Forward,
          offs1,
          Position.Bias.Backward,
          bounds
        )
        return if (shape is Rectangle) shape else shape.bounds
      } catch (e: BadLocationException) {
        // can't render
      }
    }

    // Can't render
    return null
  }
}

class TranslationInputPanel : JPanel() {

  companion object {
    val misspelledHighlighter = SquigglePainter(Color.RED)
  }

  val inputTextArea = object : JTextPane() {
    override fun getScrollableTracksViewportWidth(): Boolean {
      return true
    }
  }.apply inputTextArea@{
    var fontSize = Configurations.inputsFontSize.toFloat()
    var lastIsRTL = false
    val undoManager = UndoManager()

    val defaultFont = Font(Configurations.inputsFontName, Font.PLAIN, Configurations.inputsFontSize)
    font = defaultFont

    document.addUndoableEditListener(undoManager)
    document.addDocumentListener(object : DocumentListener {
      private val translateTimer = Timer(1000) {
        if (Configurations.instantTranslation) GlobalScope.launch {
          QTranslateViewModel.translate()
        }
        requestFocus()
      }.apply { isRepeats = false; }

      private val spellCheckTimer = Timer(1500) {
        if (Configurations.spellChecking) GlobalScope.launch {
          QTranslateViewModel.spellCheck()
        }
        requestFocus()
      }.apply { isRepeats = false; }

      override fun insertUpdate(e: DocumentEvent) = update()
      override fun removeUpdate(e: DocumentEvent) = update()
      override fun changedUpdate(e: DocumentEvent) = Unit

      private fun update() {
        QTranslateViewModel.setInputText(text)
        translateTimer.restart()
        spellCheckTimer.restart()
      }
    })

    addKeyListener(object : KeyAdapter() {
      override fun keyTyped(e: KeyEvent) {
        highlighter.removeAllHighlights()
        repaint()
        val isRTL = text.isRTL()
        if (isRTL != lastIsRTL) {
          componentOrientation = if (isRTL) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
          lastIsRTL = isRTL
        }
      }
    })
    addMouseListener(object : MouseAdapter() {

      override fun mousePressed(ev: MouseEvent) = maybeShowPopup(ev)
      override fun mouseReleased(ev: MouseEvent) = maybeShowPopup(ev)

      private fun maybeShowPopup(ev: MouseEvent) {
        if (ev.isPopupTrigger) {
          var spellingMenu: JMenu? = null

          val offset: Int = viewToModel2D(ev.point)
          val highlights: Array<Highlighter.Highlight> = highlighter.highlights
          for (highlight in highlights) {
            if (offset >= highlight.startOffset && offset < highlight.endOffset) {
              val highlightedText = document.getText(highlight.startOffset, highlight.endOffset - highlight.startOffset)
              val spellCheckCorrection =
                QTranslateViewModel.spells.value.corrections.find { it.originalWord == highlightedText } ?: continue
              spellingMenu = JMenu("Spelling")
              spellCheckCorrection.suggestions.forEach { suggestion ->
                spellingMenu.add(JMenuItem(suggestion).apply {
                  addActionListener {
                    document.remove(highlight.startOffset, highlight.endOffset - highlight.startOffset)
                    document.insertString(highlight.startOffset, suggestion, null)
//                    QTranslateViewModel.spells.value.corrections.remove(spellCheckCorrection)
                  }
                })
              }
            }
          }

          val menu = JPopupMenu()
          spellingMenu?.let {
            menu.add(spellingMenu)
            menu.addSeparator()
          }
          menu.add(JMenuItem("Undo").apply { addActionListener { undoManager.undo() } })
          menu.addSeparator()
          if (selectedText == null) {
            menu.add(JMenuItem("Cut All").apply {
              addActionListener {
                this@inputTextArea.text.copyToClipboard()
                this@inputTextArea.text = ""
              }
            })
            menu.add(JMenuItem("Copy All").apply { addActionListener { this@inputTextArea.text.copyToClipboard() } })
          } else {
            menu.add(JMenuItem("Cut").apply { addActionListener { cut() } })
            menu.add(JMenuItem("Copy").apply { addActionListener { copy() } })
          }

          menu.add(JMenuItem("Paste").apply { addActionListener { paste() } })
          menu.addSeparator()
          menu.add(JMenuItem("Translate").apply {
            addActionListener {
              GlobalScope.launch {
                if (selectedText == null) QTranslateViewModel.translate()
                else QTranslateViewModel.translate(selectedText)
              }
            }
          })
          menu.add(JMenuItem("Listen").apply {
            addActionListener {
              GlobalScope.launch {
                if (selectedText == null) QTranslateViewModel.listenToInput()
                else QTranslateViewModel.listenToInput(selectedText)
              }
            }
          })
          menu.show(ev.component, ev.x, ev.y)
        }
      }
    })
    addMouseWheelListener { e ->
      if (e.isControlDown) {
        if (e.wheelRotation < 0 && fontSize < 72f) {
          fontSize += 1f
        } else if (e.wheelRotation > 0 && fontSize > 8f) {
          fontSize -= 1f
        }
        font = font.deriveFont(fontSize)
      } else {
        parent.dispatchEvent(e)
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

    add(buttonsPanel, BorderLayout.EAST)
    add(scrollPane)
  }

}