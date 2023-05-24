package com.pnix.qtranslate.presentation.quick_translate_dialog

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.components.FlatButton
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.listeners.common.ComponentMover
import com.pnix.qtranslate.presentation.listeners.common.ComponentResizer
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.presentation.main_frame.panels.TranslatorsPanel
import com.pnix.qtranslate.utils.copyToClipboard
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
  private var fontSize = Configurations.inputsFontSize.toFloat()
  private val padding = 4

  val quickTranslateDialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

  val contentPanel = JPanel(BorderLayout())

  private val outputTextArea = object : JTextPane() {
    override fun getScrollableTracksViewportWidth(): Boolean {
      return true
    }
  }.apply {
    text = QTranslateViewModel.translation.value
    font = Font(Configurations.inputsFontName, Font.PLAIN, Configurations.inputsFontSize)
    isEditable = false
    componentOrientation =
      ComponentOrientation.getOrientation(Locale.forLanguageTag(QTranslateViewModel.outputLanguage.value.alpha2))
    caretPosition = 0

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

  private val buttonGroup = ButtonGroup()
  private val translatorsButtons = QTranslateViewModel.translators.mapIndexed { index, it ->
    createToggleButton(it.serviceName, index)
  }

  private val scrollPane = JScrollPane(outputTextArea).apply {
    border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
  }

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
    val closeButton = createTopPanelButton("app-icons/cross-circle.svg", 13).apply {
      border = BorderFactory.createEmptyBorder(4, 2, 4, 2)
      addActionListener {
        this@QuickTranslateDialog.isVisible = false
        this@QuickTranslateDialog.dispatchEvent(WindowEvent(this@QuickTranslateDialog, WindowEvent.WINDOW_CLOSING))
      }
    }

    val favouriteButton = createTopPanelButton("app-icons/star.svg", 13).apply {
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      addActionListener { dispose() }
    }
    val dictionaryButton = createTopPanelButton("app-icons/notebook-alt.svg", 13).apply {
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      addActionListener { }
    }
    val listenButton = createTopPanelButton("app-icons/headphones.svg", 13).apply {
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      addActionListener { quickTranslateDialogScope.launch { QTranslateViewModel.listenToInput() } }
    }
    val copyButton = createTopPanelButton("app-icons/copy-alt.svg", 13).apply {
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      addActionListener { QTranslateViewModel.input.value.copyToClipboard(); dispose() }
    }
    val replaceButton = createTopPanelButton("app-icons/replace.svg", 13).apply {
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
      addActionListener { }
    }


    return JPanel().apply {
      layout = BoxLayout(this, BoxLayout.X_AXIS)
      border = null
      add(closeButton)
      add(Box.createHorizontalStrut(4))
      add(JLabel(getDialogTitle()))
      add(Box.createHorizontalGlue())
      add(favouriteButton)
      add(dictionaryButton)
      add(listenButton)
      add(copyButton)
      add(replaceButton)
    }
  }

  private fun createTopPanelButton(iconPath: String, iconSize: Int): FlatButton {
    return FlatButton().apply {
      icon = FlatSVGIcon(iconPath, iconSize, iconSize).apply {
        colorFilter = FlatSVGIcon.ColorFilter { _: Color? -> UIManager.getColor("Label.foreground") }
      }
      buttonType = FlatButton.ButtonType.toolBarButton
    }
  }

  private fun createToggleButton(name: String, index: Int) = JToggleButton(name).apply {
    icon = FlatSVGIcon("translator-icons/${name.lowercase()}.svg", (16 * 1.25).toInt(), (16 * 1.25).toInt())
    addActionListener {
      QTranslateViewModel.setSelectedTranslatorIndex(index)
      WindowKeyListeners.Translate.action.actionPerformed(it)
    }
    buttonGroup.add(this)
  }

  private fun getDialogTitle(): String {
    val inputLanguageName = QTranslateViewModel.inputLanguage.value.name
    val outputLanguageName = QTranslateViewModel.outputLanguage.value.name
    return "From $inputLanguageName to $outputLanguageName"
  }

}