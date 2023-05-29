package com.pnix.qtranslate.presentation.components

import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.main_frame.panels.UnderlineHighlighter
import com.pnix.qtranslate.utils.controlKeyWith
import com.pnix.qtranslate.utils.isRTL
import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JTextPane
import javax.swing.undo.UndoManager


class TTextPane : JTextPane() {
  private val painter = UnderlineHighlighter.UnderlineHighlightPainter(Color.RED)
  val undoManager = UndoManager()

  private var lastIsRTL = false

  init {
    font = Font(Configurations.inputsFontName, Font.PLAIN, Configurations.inputsFontSize)
    highlighter = UnderlineHighlighter()
    document.addUndoableEditListener(undoManager)

    resetKeybindings()
    addUndoRedoKeys()
    addZoomListener()
    addTextDirectionDetectionListener()
  }

  private fun resetKeybindings() {
    val im = getInputMap(WHEN_FOCUSED)
    im.put(controlKeyWith(KeyEvent.VK_H), "none")
  }

  private fun addUndoRedoKeys() {
    val im = getInputMap(WHEN_FOCUSED)

    im.put(controlKeyWith(KeyEvent.VK_Z), "Undo")
    im.put(controlKeyWith(KeyEvent.VK_Y), "Redo")

    actionMap.put("Undo", object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent) {
        runCatching { if (undoManager.canUndo()) undoManager.undo() }
      }
    })

    actionMap.put("Redo", object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent) {
        runCatching { if (undoManager.canRedo()) undoManager.redo() }
      }
    })
  }

  private fun addTextDirectionDetectionListener() {
    addKeyListener(object : KeyAdapter() {
      override fun keyTyped(e: KeyEvent) {
        removeUnderlineHighlights()
        repaint()
        val isRTL = text.isRTL()
        if (isRTL != lastIsRTL) {
          componentOrientation = if (isRTL) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
          lastIsRTL = isRTL
        }
      }
    })
  }

  private fun addZoomListener() {
    addMouseWheelListener { e ->
      if (e.isControlDown) {
        if (e.wheelRotation < 0 && font.size < 72f) {
          font = font.deriveFont(font.size + 1f)
        } else if (e.wheelRotation > 0 && font.size > 8f) {
          font = font.deriveFont(font.size - 1f)
        }
      } else {
        parent.dispatchEvent(e)
      }
    }
  }

  fun removeUnderlineHighlights() {
    val highlights = highlighter.highlights
    for (i in highlights.indices)
      if (highlights[i].painter is UnderlineHighlighter.UnderlineHighlightPainter)
        highlighter.removeHighlight(highlights[i])
  }

  fun addUnderlineHighlight(startIndex: Int, endIndex: Int) {
    highlighter.addHighlight(startIndex, endIndex, painter)
  }

  override fun getScrollableTracksViewportWidth(): Boolean {
    return true
  }
}