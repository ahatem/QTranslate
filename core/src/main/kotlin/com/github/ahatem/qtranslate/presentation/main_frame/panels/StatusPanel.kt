package com.github.ahatem.qtranslate.presentation.main_frame.panels

import com.formdev.flatlaf.extras.components.FlatButton
import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.utils.createButtonWithIcon
import javax.swing.*

class StatusPanel : JPanel() {

    private val status = JLabel()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
        val wrapper = JPanel()
        wrapper.layout = BoxLayout(wrapper, BoxLayout.LINE_AXIS)
        wrapper.border = BorderFactory.createEmptyBorder(1, 2, 0, 2)
        wrapper.add(status)
        wrapper.add(Box.createHorizontalGlue())
        wrapper.add(
            createButtonWithIcon(
                "app-icons/notification_empty.svg", 12,
                Localizer.localize("status_panel_button_tooltip_notification_empty")
            ).apply {
                isEnabled = true
                buttonType = FlatButton.ButtonType.toolBarButton
                addActionListener {
//        DictionaryDialog(QTranslateViewModel.mainFrame)
//        VirtualKeyboardDialog(QTranslateViewModel.mainFrame)
                }
            })
        add(JSeparator())
        add(wrapper)
    }


    fun showError(message: String) {
        if (message.isEmpty()) {
            resetStatus()
            return
        }
        status.text = message
        status.foreground = UIManager.getColor("Actions.Red")
        status.putClientProperty("FlatLaf.styleClass", "semibold")
    }

    fun resetStatus() {
        status.text = Localizer.localize("status_panel_text_default_text")
        status.foreground = UIManager.getColor("Label.foreground")
        status.putClientProperty("FlatLaf.styleClass", "defaultFont")
    }

}