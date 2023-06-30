package com.pnix.qtranslate.presentation.quick_translate_dialog

import com.formdev.flatlaf.extras.components.FlatButton
import com.pnix.qtranslate.common.Localizer
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.components.QtTextPane
import com.pnix.qtranslate.presentation.listeners.common.ComponentMover
import com.pnix.qtranslate.presentation.listeners.common.ComponentResizer
import com.pnix.qtranslate.presentation.main_frame.panels.TranslatorsPanel
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.copyToClipboard
import com.pnix.qtranslate.utils.createButtonWithIcon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.*
import java.util.*
import javax.swing.*
import javax.swing.Timer
import kotlin.math.min

class QuickTranslateDialog(frame: JFrame) : JDialog(frame, ModalityType.MODELESS) {
  val quickTranslateDialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

  private val padding = 4

  private val contentPanel = JPanel(BorderLayout())
  private val dialogTitle = getDialogTitle()

  private val outputTextArea = QtTextPane().apply {
    text = QTranslateViewModel.translation.value
    isEditable = false
    caretPosition = 0
    allowPopupMenu = false
  }

  private val scrollPane = JScrollPane(outputTextArea).apply { border = BorderFactory.createEmptyBorder(2, 0, 0, 0) }

  init {
    defaultCloseOperation = DISPOSE_ON_CLOSE
    isUndecorated = true
    isAlwaysOnTop = true
    focusableWindowState = false
    minimumSize = Dimension(200, 65)
    getRootPane().border =
      BorderFactory.createMatteBorder(padding, padding, padding, padding, UIManager.getColor("Component.borderColor"))
    rootPane.isOpaque = false
    rootPane.registerKeyboardAction(
      { dispose() },
      KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
      JComponent.WHEN_IN_FOCUSED_WINDOW
    )

    UIManager.addPropertyChangeListener { evt ->
      if ("lookAndFeel" == evt.propertyName) {
        getRootPane().border = BorderFactory.createMatteBorder(
          padding, padding, padding, padding,
          UIManager.getColor("Component.borderColor")
        )
      }
    }

    val topPanel = createTopPanel()
    val translatorsPanel = TranslatorsPanel()

    contentPanel.add(topPanel, BorderLayout.NORTH)
    contentPanel.add(scrollPane)
    contentPanel.add(translatorsPanel, BorderLayout.SOUTH)

    val buttons = Collections.list(translatorsPanel.buttonGroup.elements).toList()
    buttons.withIndex().forEach { (index, button) ->
      if (index == QTranslateViewModel.selectedTranslatorIndex.value && !button.isSelected) button.isSelected = true
    }

    initMoveAndResize(topPanel)

    contentPane.add(contentPanel)
    resize()
    reposition()

    val autoHideTimer =
      Timer(Configurations.popupAutoHideDelay * 1000) { dispose() }.apply { this.isRepeats = false; start() }
    val timer = Timer(100) { _ ->
      val point = MouseInfo.getPointerInfo().location
      SwingUtilities.convertPointFromScreen(point, this)
      if (contentPane.contains(point)) {
        opacity = 1f
        autoHideTimer.restart()
      } else {
        opacity = (100f - Configurations.popupTransparency).div(100)
      }
    }

    addWindowListener(object : WindowAdapter() {
      override fun windowOpened(e: WindowEvent) = timer.start()
      override fun windowClosing(e: WindowEvent) {
        quickTranslateDialogScope.cancel()
        timer.stop()
        Configurations.popupLastPosition = "${location.x},${location.y}"
        Configurations.popupLastSize = "${size.width},${size.height}"
      }
    })

    topPanel.addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent?) {
        if (Configurations.popupEnablePinWhenDragging) {
          autoHideTimer.delay = Integer.MAX_VALUE
          autoHideTimer.initialDelay = Integer.MAX_VALUE
          autoHideTimer.restart()
        }
      }
    })

    quickTranslateDialogScope.launch(Dispatchers.Swing) {
      launch {
        QTranslateViewModel.translation.collectLatest {
          if (outputTextArea.text != it) {
            outputTextArea.text = it
            val locale = Locale.forLanguageTag(QTranslateViewModel.outputLanguage.value.alpha2)
            outputTextArea.componentOrientation = ComponentOrientation.getOrientation(locale)
            outputTextArea.caretPosition = 0
            autoHideTimer.restart()
          }
        }
      }

      launch {
        QTranslateViewModel.selectedTranslatorIndex.collectLatest {
          dialogTitle.text = getDialogTitle().text
          translatorsPanel.selectIndex(it)
          QTranslateViewModel.translate()
          autoHideTimer.restart()
        }
      }
    }

    applyComponentOrientation(frame.componentOrientation)
    outputTextArea.componentOrientation =
      ComponentOrientation.getOrientation(Locale.forLanguageTag(QTranslateViewModel.outputLanguage.value.alpha2))

    isVisible = true
    focusableWindowState = true
  }

  private fun reposition() {
    if (!Configurations.popupEnableAutoPosition) {
      val coordinates = Configurations.popupLastPosition.split(",").map { it.toInt() }
      setLocation(coordinates[0], coordinates[1])
      return
    }

    val mousePosition = MouseInfo.getPointerInfo().location
    val screenWidth = Toolkit.getDefaultToolkit().screenSize.width
    val screenHeight = Toolkit.getDefaultToolkit().screenSize.height

    val dialogWidth = size.width
    val dialogHeight = size.height
    val dialogX = min(mousePosition.x, screenWidth - dialogWidth)
    val dialogY = min(mousePosition.y + 10, screenHeight - dialogHeight)

    setLocation(dialogX, dialogY)
  }

  private fun resize() {
    if (!Configurations.popupEnableAutoSize) {
      val size = Configurations.popupLastSize.split(",").map { it.toInt() }
      preferredSize = Dimension(size[0], size[1])
      pack()
      return
    }

    val maxWidth = (Toolkit.getDefaultToolkit().screenSize.width * (0.430)).toInt()
    val maxHeight = (Toolkit.getDefaultToolkit().screenSize.height * (0.330)).toInt()

    val fontMetrics: FontMetrics = outputTextArea.getFontMetrics(outputTextArea.font)
    val text = outputTextArea.text.replace("\n", " ")
    val width = fontMetrics.stringWidth(text)
    if (width > maxWidth) {
      val preferredSize = Dimension(maxWidth, outputTextArea.preferredSize.height)
      scrollPane.preferredSize = preferredSize
      pack()
      val requiredHeight = outputTextArea.preferredSize.height.plus(3).coerceAtMost(maxHeight)
      scrollPane.preferredSize = Dimension(scrollPane.preferredSize.width, requiredHeight)
    }
    pack()
  }

  private fun initMoveAndResize(movePanel: JPanel) {
    val insets = Insets(padding, padding, padding, padding)
    ComponentMover(this, movePanel).apply {
      edgeInsets = insets
      dragInsets = insets
      registerComponent()
    }
    ComponentResizer().apply {
      this.setDragInsets(insets)
      registerComponent(this@QuickTranslateDialog)
    }
  }

  private fun createTopPanel(): JPanel {
    val closeButton = createButtonWithIcon("app-icons/cross-circle.svg", 13).apply {
      border = BorderFactory.createEmptyBorder(4, 2, 4, 2)
      buttonType = FlatButton.ButtonType.toolBarButton
      addActionListener {
        this@QuickTranslateDialog.isVisible = false
        this@QuickTranslateDialog.dispatchEvent(WindowEvent(this@QuickTranslateDialog, WindowEvent.WINDOW_CLOSING))
      }
    }

    val favouriteButton = createButtonWithIcon("app-icons/star.svg", 13).apply {
      isEnabled = false
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      buttonType = FlatButton.ButtonType.toolBarButton
    }
    val dictionaryButton = createButtonWithIcon("app-icons/notebook-alt.svg", 13).apply {
      isEnabled = false
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      buttonType = FlatButton.ButtonType.toolBarButton
      addActionListener { }
    }
    val listenButton = createButtonWithIcon("app-icons/headphones.svg", 13).apply {
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      buttonType = FlatButton.ButtonType.toolBarButton
      addActionListener { quickTranslateDialogScope.launch { QTranslateViewModel.listenToInput() } }
    }
    val copyButton = createButtonWithIcon("app-icons/copy-alt.svg", 13).apply {
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      buttonType = FlatButton.ButtonType.toolBarButton
      addActionListener { QTranslateViewModel.input.value.copyToClipboard(); dispose() }

    }
    val replaceButton = createButtonWithIcon("app-icons/replace.svg", 13).apply {
      isEnabled = false
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      buttonType = FlatButton.ButtonType.toolBarButton
      addActionListener { }
    }


    return JPanel().apply {
      layout = BoxLayout(this, BoxLayout.LINE_AXIS)
      border = null
      add(closeButton)
      add(Box.createHorizontalStrut(4))
      add(dialogTitle)
      add(Box.createHorizontalGlue())
      add(favouriteButton)
      add(dictionaryButton)
      add(listenButton)
      add(copyButton)
      add(replaceButton)
    }
  }

  // TODO Extract to member variable and update it when translator index change
  private fun getDialogTitle(): JLabel {
    val inputLanguageName = QTranslateViewModel.inputLanguage.value.name
    val outputLanguageName = QTranslateViewModel.outputLanguage.value.name
    val inputLangText = """<FONT style="font-weight: 600;">${inputLanguageName}</FONT>"""
    val outputLangText = """<FONT style="font-weight: 600;">${outputLanguageName}</FONT>"""
    val text = """
      <HTML>
        <BODY>
          ${Localizer.localize("quick_translate_panel_title").format(inputLangText, outputLangText)}${"&nbsp;".repeat(6)}
        </BODY>
      </HTML>
    """.trimIndent()
    return JLabel(text)
  }

}