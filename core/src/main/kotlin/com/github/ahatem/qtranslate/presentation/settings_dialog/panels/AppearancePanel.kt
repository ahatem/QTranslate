package com.github.ahatem.qtranslate.presentation.settings_dialog.panels

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Configuration
import com.github.ahatem.qtranslate.models.Theme
import com.github.ahatem.qtranslate.utils.GBHelper
import com.github.ahatem.qtranslate.utils.SimpleDocumentListener
import com.github.ahatem.qtranslate.utils.addSeparator
import java.awt.GridBagLayout
import java.awt.Insets
import java.text.NumberFormat
import javax.swing.*
import javax.swing.text.NumberFormatter

class AppearancePanel(configuration: Configuration) : JPanel() {

    private val pos = GBHelper().apply { insets = Insets(2, 2, 2, 2) }

    init {
        layout = GridBagLayout()

        addSeparator(pos, Localizer.localize("appearance_panel_text_general"))
        add(
            JCheckBox(
                Localizer.localize("appearance_panel_checkbox_text_enable_window_styling"),
                configuration.enableWindowStyle
            ).apply {
                addActionListener {
                    configuration.enableWindowStyle = isSelected
                }
            },
            pos.nextRow().expandW()
        )

        add(
            JCheckBox(
                Localizer.localize("appearance_panel_checkbox_text_unify_window_title_bar"),
                configuration.unifyTitleBar
            ).apply {
                isEnabled = configuration.enableWindowStyle
                addActionListener {
                    configuration.unifyTitleBar = isSelected
                }
            },
            pos.nextRow().expandW()
        )

        add(
            JLabel(Localizer.localize("appearance_panel_text_theme")),
            pos.nextRow()
        )

        add(
            JComboBox(Theme.entries.map { it.readableName }.toTypedArray()).apply {
                selectedItem = configuration.theme.readableName
                addActionListener {
                    val theme = Theme.getThemeByReadableName(selectedItem as String)
                    configuration.theme = theme
                }
            },
            pos.nextCol().expandW().width(2)
        )


        addSeparator(pos, Localizer.localize("appearance_panel_text_popup_window"))

        add(
            JCheckBox(
                Localizer.localize("appearance_panel_checkbox_text_enable_auto_size"),
                configuration.popupEnableAutoSize
            ).apply {
                addActionListener {
                    configuration.popupEnableAutoSize = isSelected
                }
            },
            pos.nextRow().expandW()
        )

        add(
            JCheckBox(
                Localizer.localize("appearance_panel_checkbox_text_enable_auto_position"),
                configuration.popupEnableAutoPosition
            ).apply {
                addActionListener {
                    configuration.popupEnableAutoPosition = isSelected
                }
            },
            pos.nextRow().expandW()
        )

        add(
            JCheckBox(
                Localizer.localize("appearance_panel_checkbox_text_pin_when_dragging"),
                configuration.popupEnablePinWhenDragging
            ).apply {
                addActionListener {
                    configuration.popupEnablePinWhenDragging = isSelected
                }
            },
            pos.nextRow().expandW()
        )

        add(
            JLabel(Localizer.localize("appearance_panel_checkbox_text_auto_hide_delay")),
            pos.nextRow()
        )
        add(
            JComboBox(IntArray(12) { it + 1 }.toTypedArray()).apply {
                selectedItem = configuration.popupAutoHideDelay
                addActionListener {
                    configuration.popupAutoHideDelay = selectedItem as Int
                }
            },
            pos.nextCol().expandW()
        )
        add(
            JLabel(Localizer.localize("appearance_panel_text_seconds")),
            pos.nextCol().padding(right = 2, left = 2)
        )

        add(
            JLabel(Localizer.localize("appearance_panel_text_transparency")),
            pos.nextRow()
        )
        add(
            JFormattedTextField(
                NumberFormatter(NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }).apply {
                    valueClass = Integer::class.java
                    minimum = 0
                    maximum = 90
                }
            ).apply {
                value = configuration.popupTransparency
                document.addDocumentListener(SimpleDocumentListener { _ ->
                    configuration.popupTransparency = value as Int
                })
            },
            pos.nextCol().expandW().width(2)
        )

        add(JPanel(), pos.nextRow().expandH())
    }

}