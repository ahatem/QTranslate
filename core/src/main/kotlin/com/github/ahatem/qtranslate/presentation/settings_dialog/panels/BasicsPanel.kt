package com.github.ahatem.qtranslate.presentation.settings_dialog.panels

import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Configuration
import com.github.ahatem.qtranslate.models.Language
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.utils.GBHelper
import com.github.ahatem.qtranslate.utils.addSeparator
import java.awt.GraphicsEnvironment
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel


class BasicsPanel(configuration: Configuration) : JPanel() {

    private val pos = GBHelper().apply { insets = Insets(2, 2, 2, 2) }

    init {
        layout = GridBagLayout()
        addSeparator(pos, Localizer.localize("basics_panel_text_general"))
        add(
            JCheckBox(
                Localizer.localize("basics_panel_checkbox_text_start_with_windows"),
                configuration.startWithWindows
            ).apply {
                isEnabled = false
                addActionListener {
                    configuration.startWithWindows = isSelected
                }
            },
            pos.nextRow().expandW()
        )

        add(
            JLabel(Localizer.localize("basics_panel_text_interface_language")),
            pos.nextRow()
        )
        add(
            JComboBox(Localizer.supportedLanguages).apply {
                selectedItem = Language(configuration.interfaceLanguage)
                addActionListener {
                    configuration.interfaceLanguage = (selectedItem as Language).alpha2
                }
            },
            pos.nextCol().expandW().width(2)
        )

        add(
            JLabel(Localizer.localize("basics_panel_text_font_name")),
            pos.nextRow()
        )
        add(
            JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames).apply {
                selectedItem = configuration.inputsFontName
                addActionListener {
                    configuration.inputsFontName = selectedItem as String
                }
            },
            pos.nextCol().expandW().width(2)
        )

        add(
            JLabel(Localizer.localize("basics_panel_text_font_size")),
            pos.nextRow()
        )
        add(
            JComboBox(generateFontSizes(8, 72).toList().toTypedArray()).apply {
                selectedItem = configuration.inputsFontSize
                addActionListener {
                    configuration.inputsFontSize = selectedItem as Int
                }
            },
            pos.nextCol().expandW().width(2)
        )

        addSeparator(pos, Localizer.localize("basics_panel_text_auto_detect_languages"))
        add(
            JLabel(Localizer.localize("basics_panel_text_first_language")),
            pos.nextRow()
        )
        add(
            JComboBox(QTranslateViewModel.supportedLanguages).apply {
                selectedItem = Language(configuration.autoDetectFirstLanguage)
                addActionListener {
                    configuration.autoDetectFirstLanguage = (selectedItem as Language).alpha3
                }
            },
            pos.nextCol().expandW().width(2)
        )

        add(
            JLabel(Localizer.localize("basics_panel_text_second_language")),
            pos.nextRow()
        )
        add(
            JComboBox(QTranslateViewModel.supportedLanguages).apply {
                selectedItem = Language(configuration.autoDetectSecondLanguage)
                addActionListener {
                    configuration.autoDetectSecondLanguage = (selectedItem as Language).alpha3
                }
            },
            pos.nextCol().expandW().width(2)
        )

        addSeparator(pos, Localizer.localize("basics_panel_text_history"))
        add(
            JCheckBox(
                Localizer.localize("basics_panel_checkbox_text_enable_history"),
                configuration.enableHistory
            ).apply {
                addActionListener {
                    configuration.enableHistory = isSelected
                }
            },
            pos.nextRow().expandW()
        )

        add(
            JCheckBox(
                Localizer.localize("basics_panel_checkbox_text_clear_history_on_exit"),
                configuration.clearHistoryOnExist
            ).apply {
                isEnabled = false
                addActionListener {
                    configuration.clearHistoryOnExist = isSelected
                }
            },
            pos.nextRow().expandW()
        )

        add(
            JCheckBox(
                Localizer.localize("basics_panel_checkbox_text_expand_items"),
                configuration.expandHistoryItems
            ).apply {
                addActionListener {
                    configuration.expandHistoryItems = isSelected
                }
            },
            pos.nextRow().expandW()
        )

        add(JPanel(), pos.nextRow().expandH())
    }

    private fun generateFontSizes(startSize: Int, endSize: Int): Sequence<Int> {
        val step = if (startSize <= 18) 2 else if (startSize <= 36) 4 else 8
        return generateSequence(startSize) { it + step }.takeWhile { it <= endSize }
    }
}