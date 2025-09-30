package com.github.ahatem.qtranslate.presentation.settings_dialog

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Configuration
import com.github.ahatem.qtranslate.models.Configurations
import com.github.ahatem.qtranslate.presentation.settings_dialog.panels.*
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.utils.setPadding
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.ComponentOrientation
import javax.swing.*


class SettingsDialog(val frame: JFrame) : JDialog(frame, Localizer.localize("settings_panel_title"), false) {

  private val configuration = Configuration()

  private val listItems = mapOf(
    formattedListItemText("settings_panel_list_item_basics") to "basics",
    formattedListItemText("settings_panel_list_item_hotkeys") to "hotkeys",
    formattedListItemText("settings_panel_list_item_services") to "services",
    formattedListItemText("settings_panel_list_item_languages") to "languages",
    formattedListItemText("settings_panel_list_item_appearance") to "appearance",
    formattedListItemText("settings_panel_list_item_contact_us") to "contact_us",
  )

  init {
    setPadding(4)

    val list = JList(listItems.keys.toTypedArray())
    list.setSelectedValue(
      formattedListItemText("settings_panel_list_item_${Configurations.lastOptionOpened}"), false
    )
    list.border = BorderFactory.createMatteBorder(1, 1, 1, 1, UIManager.getColor("Component.borderColor"))
    UIManager.addPropertyChangeListener { evt ->
      if ("lookAndFeel" == evt.propertyName) list.border =
        BorderFactory.createMatteBorder(1, 1, 1, 1, UIManager.getColor("Component.borderColor"))
    }


    val cardPanel = JPanel(CardLayout())
    cardPanel.border = BorderFactory.createEmptyBorder(0, 2, 0, 2)

    cardPanel.add(BasicsPanel(configuration), "basics")
    cardPanel.add(HotkeysPanel(this), "hotkeys")
    cardPanel.add(ServicesPanel(configuration), "services")
    cardPanel.add(LanguagesPanel(configuration), "languages")
    cardPanel.add(AppearancePanel(configuration), "appearance")
    cardPanel.add(ContactUsPanel(), "contact_us")


    val cardLayout = cardPanel.layout as CardLayout
    cardLayout.show(cardPanel, Configurations.lastOptionOpened)

    list.addListSelectionListener {
      Configurations.lastOptionOpened = listItems[list.selectedValue.toString()] ?: "basics"
      cardLayout.show(cardPanel, Configurations.lastOptionOpened)
    }

    add(list, BorderLayout.LINE_START)
    add(cardPanel)
    add(getBottomPanel(), BorderLayout.SOUTH)

    pack()
    minimumSize = preferredSize
    setLocationRelativeTo(frame)

    applyComponentOrientation(frame.componentOrientation)
    isVisible = true
  }

  private fun getBottomPanel(): JPanel {
    fun apply() {
      Configurations.use(configuration)
      QTranslateViewModel.triggerConfigurationChanged()
      repaint()
    }

    val okButton =
      JButton(Localizer.localize("settings_panel_button_text_ok")).apply { addActionListener { apply(); dispose() } }
    val cancelButton =
      JButton(Localizer.localize("settings_panel_button_text_cancel")).apply { addActionListener { dispose() } }
    val applyButton =
      JButton(Localizer.localize("settings_panel_button_text_apply")).apply { addActionListener { apply() } }


    val bottomPanel = JPanel()
    bottomPanel.border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
    bottomPanel.layout = BoxLayout(bottomPanel, BoxLayout.LINE_AXIS)
    bottomPanel.add(Box.createHorizontalGlue())
    bottomPanel.add(okButton)
    bottomPanel.add(cancelButton)
    bottomPanel.add(applyButton)

    val wrapper = JPanel()
    wrapper.border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
    wrapper.layout = BoxLayout(wrapper, BoxLayout.PAGE_AXIS)
    wrapper.add(JSeparator())
    wrapper.add(bottomPanel)

    return wrapper
  }


  private fun formattedListItemText(key: String): String {
    val dir = if (frame.componentOrientation == ComponentOrientation.RIGHT_TO_LEFT) "-" else "-"
    return "%${dir}35s".format(Localizer.localize(key))
  }

}