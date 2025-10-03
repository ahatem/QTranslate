package com.github.ahatem.qtranslate.presentation.components

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Configurations
import com.github.ahatem.qtranslate.presentation.main_frame.panels.UnderlineHighlighter
import com.github.ahatem.qtranslate.utils.controlKeyWith
import com.github.ahatem.qtranslate.utils.copyToClipboard
import com.github.ahatem.qtranslate.utils.isRTL
import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Font
import java.awt.event.*
import javax.swing.*
import javax.swing.undo.UndoManager

class QtTextPaneMenu(private val qtTextPane: QtTextPane, listener: QtTextPaneListeners?, event: MouseEvent) :
    JPopupMenu() {

    init {
        runCatching { listener?.createMenuHeader(event)?.let { add(it); addSeparator() } }
        add(JMenuItem(Localizer.localize("menu_item_undo")).apply {
            isEnabled = qtTextPane.isEditable && qtTextPane.undoManager.canUndo()
            addActionListener { qtTextPane.undoManager.undo() }
        })
        addSeparator()
        if (qtTextPane.selectedText == null) {
            add(JMenuItem(Localizer.localize("menu_item_cut_all")).apply {
                isEnabled = qtTextPane.isEditable
                addActionListener {
                    qtTextPane.text.copyToClipboard()
                    qtTextPane.text = ""
                }
            })
            add(JMenuItem(Localizer.localize("menu_item_copy_all")).apply { addActionListener { qtTextPane.text.copyToClipboard() } })
        } else {
            add(JMenuItem(Localizer.localize("menu_item_cut")).apply {
                isEnabled = qtTextPane.isEditable; addActionListener { qtTextPane.cut() }
            })
            add(JMenuItem(Localizer.localize("menu_item_copy")).apply { addActionListener { qtTextPane.copy() } })
        }

        add(JMenuItem(Localizer.localize("menu_item_paste")).apply {
            isEnabled = qtTextPane.isEditable; addActionListener { qtTextPane.paste() }
        })
        addSeparator()

        add(JMenuItem(Localizer.localize("menu_item_translate")).apply {
            addActionListener {
                val text = if (qtTextPane.selectedText == null) text else qtTextPane.selectedText
                listener?.onMenuItemTranslateClicked(text)
            }
        })
        add(JMenuItem(Localizer.localize("menu_item_listen")).apply {
            addActionListener {
                val text = if (qtTextPane.selectedText == null) text else qtTextPane.selectedText
                listener?.onMenuItemListenClicked(text)
            }
        })
    }
}

interface QtTextPaneListeners {
    fun createMenuHeader(event: MouseEvent): JMenu? = null

    fun onMenuItemTranslateClicked(selectedText: String)
    fun onMenuItemListenClicked(selectedText: String)
}

class QtTextPane : JTextPane() {
    private val painter = UnderlineHighlighter.UnderlineHighlightPainter(Color.RED)
    private var lastIsRTL = false

    val undoManager = UndoManager()
    var listener: QtTextPaneListeners? = null
    var allowPopupMenu = true

    init {
        font = Font(Configurations.inputsFontName, Font.PLAIN, Configurations.inputsFontSize)
        focusTraversalKeysEnabled = false
        highlighter = UnderlineHighlighter()
        document.addUndoableEditListener(undoManager)
        resetKeybindings()
        addUndoRedoKeys()
        addZoomListener()
        addTextDirectionDetectionListener()
        addDefaultPopupMenuListeners()
    }

    private fun addDefaultPopupMenuListeners() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(ev: MouseEvent) = maybeShowPopup(ev)
            override fun mouseReleased(ev: MouseEvent) = maybeShowPopup(ev)

            private fun maybeShowPopup(event: MouseEvent) {
                if (!event.isPopupTrigger || !allowPopupMenu) return

                val menu = QtTextPaneMenu(this@QtTextPane, listener, event)
                menu.show(event.component, event.x, event.y)
            }
        })
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
                    componentOrientation =
                        if (isRTL) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
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