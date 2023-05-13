package com.pnix.qtranslate.presentation.main_frame

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.pnix.qtranslate.domain.models.Configurations
import com.pnix.qtranslate.domain.models.SpellCheckCorrection
import com.pnix.qtranslate.presentation.actions.ActionManager
import com.pnix.qtranslate.utils.copyToClipboard
import com.pnix.qtranslate.utils.isRTL
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
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

class InputPanel : JPanel() {

  data class SpellCheckHelper(var text: String, var corrections: MutableList<SpellCheckCorrection>) {
    fun getNewCorrections(newText: String): List<SpellCheckCorrection> {
      val oldWords = text.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
      val newWords = newText.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
      val newCorrections = mutableListOf<SpellCheckCorrection>()
      for (w in oldWords) {
        if (!listOf(*newWords).contains(w)) {
          // If a word in the old text is not present in the new text, it might need to be corrected
          for (c in corrections) {
            if (c.startIndex < text.length && c.endIndex >= 0 && c.startIndex <= c.endIndex) {
              // Check if the correction overlaps with the missing word
              val startIndex = newText.indexOf(w)
              val endIndex = startIndex + w.length
              if (startIndex <= c.startIndex && endIndex >= c.endIndex) {
                newCorrections.add(c)
              }
            }
          }
        }
      }
      return newCorrections
    }
  }

  val spellCheckHelper = SpellCheckHelper("", mutableListOf())
  val inputTextArea = JTextArea().apply {
    var fontSize = 16f
    var lastIsRTL = false
    val undoManager = UndoManager()

    font = Font(Font.SANS_SERIF, Font.PLAIN, fontSize.toInt())
    lineWrap = true
    wrapStyleWord = true

    document.addUndoableEditListener(undoManager)
    document.addDocumentListener(object : DocumentListener {
      private val translateTimer = Timer(1000) {
        if (Configurations.instantTranslation) GlobalScope.launch { QTranslateViewModel.translate() }
        requestFocus()
      }.apply { isRepeats = false; }

      private var spellCheckJob: Job? = null
      private val spellCheckTimer = Timer(1500) {
        if (Configurations.spellChecking) checkSpelling()
        requestFocus()
      }.apply { isRepeats = false; }

      private val highlightPainter = SquigglePainter(Color.RED)

      override fun insertUpdate(e: DocumentEvent) = update()
      override fun removeUpdate(e: DocumentEvent) = update()
      override fun changedUpdate(e: DocumentEvent) = Unit

      private fun update() {
        QTranslateViewModel.setInputText(text)
        translateTimer.restart()
        spellCheckTimer.restart()
      }

      private fun checkSpelling() {
        highlighter.removeAllHighlights()
        spellCheckJob?.cancel()
        /*spellCheckJob = GlobalScope.launch {
          val spellingResult = QTranslateViewModel.checkSpelling(text)
           spellCheckHelper.text = text
           spellCheckHelper.corrections.addAll(spellingResult.corrections)
           for (word in spellingResult.corrections) {
             highlighter.addHighlight(word.startIndex, word.endIndex, highlightPainter)
           }
        }*/
      }
    })

    addKeyListener(object : KeyAdapter() {
      override fun keyTyped(e: KeyEvent) {
        val isRTL = isRTL(text)
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
              val spellCheckCorrection = spellCheckHelper.corrections.find { it.originalWord == highlightedText } ?: continue
              spellingMenu = JMenu("Spelling")
              spellCheckCorrection.suggestions.forEach { suggestion ->
                spellingMenu.add(JMenuItem(suggestion).apply {
                  addActionListener {
                    document.remove(highlight.startOffset, highlight.endOffset - highlight.startOffset)
                    document.insertString(highlight.startOffset, suggestion, null)
                    spellCheckHelper.corrections.remove(spellCheckCorrection)
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
            menu.add(JMenuItem("Cut All").apply { addActionListener { text.copyToClipboard(); text = "" } })
            menu.add(JMenuItem("Copy All").apply { addActionListener { text.copyToClipboard() } })
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
    val buttonsPanel = createButtonsPanel()

    add(buttonsPanel, BorderLayout.EAST)
    add(scrollPane)
  }

  private fun createButtonsPanel(): JPanel {
    return JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(createButton("app-icons/star.svg", "Toggle favourite").apply { isVisible = false })
      add(createButton("app-icons/microphone.svg", "Listen to speech").apply { isVisible = false })
      add(
        createButton(
          "app-icons/copy-alt.svg",
          "Copy Text"
        ).apply { addActionListener { inputTextArea.text.copyToClipboard() } })
      add(
        createButton(
          "app-icons/headphones.svg",
          "Listen to text"
        ).apply { addActionListener(ActionManager.actions["listen_to_input"]) })
    }
  }

  private fun createButton(iconPath: String, tooltip: String): JButton {
    return JButton().apply {
      toolTipText = tooltip
      icon = FlatSVGIcon(iconPath, 16, 16).apply {
        colorFilter = FlatSVGIcon.ColorFilter { _: Color? -> UIManager.getColor("Label.foreground") }
      }
    }
  }
}