package com.pnix.qtranslate.presentation.quick_translate

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.components.FlatButton
import com.pnix.qtranslate.presentation.components.ComponentMover
import com.pnix.qtranslate.presentation.components.ComponentResizer
import com.pnix.qtranslate.presentation.main_frame.QTranslateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.*
import javax.swing.border.EtchedBorder
import kotlin.math.abs


class QuickTranslateDialog(frame: JFrame) : JDialog(null as Frame?, "", false) {
  private var fontSize = 16.0f
  val label = JLabel().apply { font = Font(Font.SANS_SERIF, Font.PLAIN, fontSize.toInt()) }
  val outputTextArea = JTextArea().apply {
    font = Font(Font.SANS_SERIF, Font.PLAIN, fontSize.toInt())
    lineWrap = true
    wrapStyleWord = true
    isEditable = false


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
    defaultCloseOperation = DISPOSE_ON_CLOSE
    isUndecorated = true
    isAlwaysOnTop = true
    minimumSize = Dimension(250, 65)

    val padding = 4

    rootPane.registerKeyboardAction(
      { dispose() },
      KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
      JComponent.WHEN_IN_FOCUSED_WINDOW
    )

    val insets = Insets(padding, padding, padding, padding)

    val closeButton = FlatButton().apply {
      icon = FlatSVGIcon("app-icons/cross.svg", 10, 10).apply {
        colorFilter = FlatSVGIcon.ColorFilter { _: Color? -> UIManager.getColor("Label.foreground") }
      }
      buttonType = FlatButton.ButtonType.toolBarButton
      border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
      addActionListener { dispose() }
    }


    val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
    topPanel.border = null
    topPanel.add(closeButton)
    topPanel.add(JLabel(
//      "From ${QTranslateViewModel.uiState.value.sourceLanguage.name} to ${QTranslateViewModel.uiState.value.targetLanguage.name}"
    ))

    val contentPanel = JPanel(BorderLayout())
    contentPanel.border = BorderFactory.createCompoundBorder(
      BorderFactory.createEtchedBorder(EtchedBorder.RAISED),
      BorderFactory.createEmptyBorder(padding, padding, padding, padding)
    )
    contentPanel.add(topPanel, BorderLayout.NORTH)

    ComponentMover(this, topPanel).apply {
      edgeInsets = insets
      dragInsets = insets
      registerComponent()
    }
    ComponentResizer().apply {
      this.setDragInsets(insets)
      registerComponent(this@QuickTranslateDialog)
    }

   /* GlobalScope.launch(Dispatchers.Swing) {
      QTranslateViewModel.uiState.collect { state ->
        if (outputTextArea.text != state.outputText) {
          outputTextArea.text = state.outputText

          val locale = Locale.forLanguageTag(state.targetLanguage.alpha2)
          outputTextArea.componentOrientation = ComponentOrientation.getOrientation(locale)

          val scrollPane = JScrollPane(outputTextArea).apply { border = null }
          contentPanel.add(scrollPane)
          contentPane.add(contentPanel)

          val viewportSize: Dimension = outputTextArea.preferredSize
          val screenSize = Toolkit.getDefaultToolkit().screenSize

          val maxWidth = (screenSize.width * 0.67).toInt()
          val width = outputTextArea.getFontMetrics(outputTextArea.font).stringWidth(outputTextArea.text).coerceAtMost(maxWidth)

          val maxHeight = (screenSize.height * 0.5).toInt()
          val height = viewportSize.height.coerceAtMost(maxHeight)

          val h = (preferredSize.height + abs(viewportSize.height - preferredSize.height) + 15).coerceAtMost(maxHeight)

          preferredSize = Dimension(width, h)
          pack()

          repaint()
          revalidate()

          val mousePosition = MouseInfo.getPointerInfo().location
          setLocation(mousePosition.x, mousePosition.y + 10)
        }
      }
    }
*/
    isVisible = true
  }

}