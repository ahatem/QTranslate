package com.github.ahatem.qtranslate.ui.swing.ocr

import javax.swing.ButtonGroup
import javax.swing.JComboBox
import javax.swing.JMenu
import javax.swing.JRadioButtonMenuItem

/** Menu counterpart of a workspace selector, retaining its existing model and action listener. */
internal fun <T> ocrChoiceMenu(title: String, selector: JComboBox<T>, label: (T) -> String): JMenu =
    JMenu(title).apply {
        isEnabled = selector.isEnabled && selector.itemCount > 0
        val group = ButtonGroup()
        for (index in 0 until selector.itemCount) {
            val item = selector.getItemAt(index)
            val choice = JRadioButtonMenuItem(label(item), selector.selectedIndex == index)
            choice.addActionListener { selector.selectedIndex = index }
            group.add(choice)
            add(choice)
        }
    }
