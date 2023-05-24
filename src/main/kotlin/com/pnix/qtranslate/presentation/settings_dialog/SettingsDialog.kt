package com.pnix.qtranslate.presentation.settings_dialog

import com.pnix.qtranslate.models.Configuration
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.settings_dialog.panels.*
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.setPadding
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.*


class SettingsDialog(frame: JFrame) : JDialog(frame, "Settings", false) {

  private val configuration = Configuration()

  init {
    setPadding(4)

//    val list = JList(arrayOf("Basics", "Internet", "Services", "Languages", "Appearance", "Exceptions", "Advanced", "Updates"))
    val list = JList(
      arrayOf(
        "%-35s".format("Basics"),
        "%-35s".format("Services"),
        "%-35s".format("Languages"),
        "%-35s".format("Appearance"),
        "%-35s".format("Contact US"),
      )
    )
    list.setSelectedValue("%-35s".format(Configurations.lastOptionOpened), false)
    list.border = BorderFactory.createTitledBorder("")

    val cardPanel = JPanel(CardLayout())
    cardPanel.border = BorderFactory.createEmptyBorder(0, 2, 0, 2)

    cardPanel.add(BasicsPanel(configuration), "Basics")
    cardPanel.add(ServicesPanel(configuration), "Services")
    cardPanel.add(LanguagesPanel(configuration), "Languages")
    cardPanel.add(AppearancePanel(configuration), "Appearance")
    cardPanel.add(ContactUsPanel(), "Contact US")


    val cardLayout = cardPanel.layout as CardLayout
    cardLayout.show(cardPanel, list.selectedValue.toString().trim())

    list.addListSelectionListener {
      cardLayout.show(cardPanel, list.selectedValue.toString().trim())
      Configurations.lastOptionOpened = list.selectedValue.toString().trim()
    }

    add(list, BorderLayout.WEST)
    add(cardPanel, BorderLayout.CENTER)
    add(getBottomPanel(), BorderLayout.SOUTH)

    pack()
    minimumSize = preferredSize
    setLocationRelativeTo(frame)
    isVisible = true
  }

  private fun getBottomPanel(): JPanel {
    fun apply() {
      Configurations.use(configuration)
      QTranslateViewModel.triggerConfigurationChanged()
      repaint()
    }

    val okButton = JButton("Ok").apply { addActionListener { apply(); dispose() } }
    val cancelButton = JButton("Cancel").apply { addActionListener { dispose() } }
    val applyButton = JButton("Apply").apply { addActionListener { apply() } }


    val bottomPanel = JPanel()
    bottomPanel.border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
    bottomPanel.layout = BoxLayout(bottomPanel, BoxLayout.X_AXIS)
    bottomPanel.add(Box.createHorizontalGlue())
    bottomPanel.add(okButton)
    bottomPanel.add(cancelButton)
    bottomPanel.add(applyButton)

    val wrapper = JPanel()
    wrapper.border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
    wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
    wrapper.add(JSeparator())
    wrapper.add(bottomPanel)


    return wrapper
  }


}