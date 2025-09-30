package com.github.ahatem.qtranslate.presentation.settings_dialog.panels

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Hotkeys
import com.github.ahatem.qtranslate.presentation.listeners.global.QTranslateHotkeyListener
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.utils.GBHelper
import com.github.ahatem.qtranslate.utils.getReadableKeyStrokeText
import com.github.ahatem.qtranslate.utils.isRTL
import com.github.ahatem.qtranslate.utils.setPadding
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.TableCellRenderer

class HotkeysPanel(owner: Dialog) : JPanel() {

  init {
    layout = BorderLayout()

    val columns = arrayOf(
      Localizer.localize("hotkey_panel_column_action"),
      Localizer.localize("hotkey_panel_column_hotkey")
    )
    val data = Hotkeys.hotkeys.values.map { arrayOf(it.description, it.toUserReadableFormat()) }.toTypedArray()

    val table: JTable = object : JTable(data, columns) {
      override fun prepareRenderer(renderer: TableCellRenderer, row: Int, col: Int): Component {
        val comp = super.prepareRenderer(renderer, row, col)
        val hotkeyDescription = model.getValueAt(row, 0) as String
        val hotkey = Hotkeys.getHotkeyByDescription(hotkeyDescription)

        val isRTL = (getValueAt(row, col) as String).isRTL()
        comp.applyComponentOrientation(
          if (isRTL)
            ComponentOrientation.RIGHT_TO_LEFT
          else
            ComponentOrientation.LEFT_TO_RIGHT
        )

        if (!hotkey.customizable) {
          comp.background = UIManager.getColor("TextArea.disabledBackground")
          comp.foreground = UIManager.getColor("Label.disabledForeground")
        } else {
          if (isRowSelected(row)) {
            comp.background = getSelectionBackground()
            comp.foreground = getSelectionForeground()
          } else {
            comp.background = UIManager.getColor("Table.background")
            comp.foreground = UIManager.getColor("Table.foreground")
          }
        }
        return comp
      }
    }

    table.setDefaultEditor(Any::class.java, null)
    table.addMouseListener(object : MouseAdapter() {
      override fun mousePressed(mouseEvent: MouseEvent) {
        val point = mouseEvent.point
        val row = table.rowAtPoint(point)
        if (mouseEvent.clickCount == 2 && table.selectedRow != -1) {
          val hotkeyDescription = table.getValueAt(row, 0) as String
          val hotkey = Hotkeys.getHotkeyByDescription(hotkeyDescription)
          if (!hotkey.customizable) return

          QTranslateHotkeyListener.unRegisterGlobalListener()
          QTranslateViewModel.mainFrame.unregisterHotkeys()

          val capturedKeyStroke = KeyStrokeCaptureDialog(owner).showDialog()
          val isCapturedKeyStrokeValid = true

          if (capturedKeyStroke != null && isCapturedKeyStrokeValid) {
            val newHotkey = Hotkeys.updateHotkey(hotkey.id, capturedKeyStroke)
            if (newHotkey != null) {
              table.setValueAt(newHotkey.toUserReadableFormat(), row, 1)
            } else {
              JOptionPane.showMessageDialog(
                owner,
                Localizer.localize("hotkey_panel_message_body_in_use"),
                Localizer.localize("hotkey_panel_message_title_in_use"),
                JOptionPane.INFORMATION_MESSAGE
              )
            }
          }

          QTranslateHotkeyListener.registerGlobalListener()
          QTranslateViewModel.mainFrame.registerHotkeys()

        }
      }
    })


    val firstColumn = table.columnModel.getColumn(0)
    firstColumn.preferredWidth = 0
    var maxWidth = 0
    for (row in 0 until table.rowCount) {
      val cellRenderer = table.getCellRenderer(row, 0)
      val value = table.getValueAt(row, 0)
      val c = cellRenderer.getTableCellRendererComponent(table, value, false, false, row, 0)
      maxWidth = maxWidth.coerceAtLeast(c.preferredSize.width)
    }
    firstColumn.preferredWidth = maxWidth + table.intercellSpacing.width


    val buttonsPanel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.LINE_AXIS)
      add(JButton(Localizer.localize("hotkey_panel_button_text_reset_defaults")).apply {
        addActionListener {
          QTranslateHotkeyListener.unRegisterGlobalListener()
          QTranslateViewModel.mainFrame.unregisterHotkeys()

          Hotkeys.reset()
          val newData = Hotkeys.hotkeys.values.map { arrayOf(it.description, it.toUserReadableFormat()) }.toTypedArray()
          newData.forEachIndexed { index, value -> table.setValueAt(value[1], index, 1) }

          QTranslateHotkeyListener.registerGlobalListener()
          QTranslateViewModel.mainFrame.registerHotkeys()
        }
      })
    }


    add(JScrollPane(table))
    add(buttonsPanel, BorderLayout.SOUTH)

  }

  override fun getPreferredSize(): Dimension {
    return Dimension(super.getPreferredSize().width - 200, 250)
  }


  private class KeyStrokeCaptureDialog(owner: Dialog) :
    JDialog(owner, Localizer.localize("hotkey_panel_dialog_capture_title"), true) {
    private val inputField: JTextField = JTextField()
    private var keyStroke: KeyStroke? = null

    private var pos = GBHelper()

    init {
      setPadding(4)
      layout = BorderLayout()
      setSize(250, 150)

      inputField.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
          val keyCode: Int = e.keyCode
          val modifiers: Int = e.modifiersEx
          keyStroke = KeyStroke.getKeyStroke(keyCode, modifiers)
          inputField.text = keyStroke?.getReadableKeyStrokeText()
        }
      })

      val okButton = JButton(Localizer.localize("hotkey_panel_dialog_capture_button_text_ok")).apply {
        addActionListener {
          if (keyStroke != null && Hotkeys.isKeyStrokeInUse(keyStroke!!)) {
            JOptionPane.showMessageDialog(
              owner,
              Localizer.localize("hotkey_panel_message_body_in_use"),
              Localizer.localize("hotkey_panel_message_title_in_use"),
              JOptionPane.INFORMATION_MESSAGE
            )
          } else {
            dispose()
          }
        }
      }

      val cancelButton = JButton(Localizer.localize("hotkey_panel_dialog_capture_button_text_cancel")).apply {
        addActionListener {
          keyStroke = null; dispose()
        }
      }

      val mainPanel = JPanel(GridBagLayout()).apply {
        add(JLabel(Localizer.localize("hotkey_panel_dialog_capture_text_hotkey")), pos.expandW())
        add(inputField, pos.nextRow().expandW().padding(top = 4))
      }

      val buttonsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.LINE_AXIS)
        add(Box.createHorizontalGlue())
        add(cancelButton)
        add(okButton)
      }

      rootPane.defaultButton = okButton

      add(mainPanel, BorderLayout.CENTER)
      add(buttonsPanel, BorderLayout.SOUTH)

      applyComponentOrientation(owner.componentOrientation)
      setLocationRelativeTo(owner)
    }

    fun showDialog(): KeyStroke? {
      isVisible = true
      return keyStroke
    }
  }
}