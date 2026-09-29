package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JList
import javax.swing.UIManager

/**
 * A combo box renderer that shows [text] for each value and leaves everything else to
 * [DefaultListCellRenderer], so hover and keyboard selection, orientation and theme colors come
 * from the look and feel. [isDisabled] only dims the row; whether it can be chosen stays with the caller.
 */
class DisplayValueRenderer<T>(
    private val text: (T?) -> String,
    private val icon: (T?) -> Icon? = { null },
    private val isDisabled: (T?) -> Boolean = { false },
    private val tooltip: (T?) -> String? = { null }
) : DefaultListCellRenderer() {

    @Suppress("UNCHECKED_CAST")
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val item = value as T?
        setText(text(item))
        setIcon(icon(item))
        toolTipText = tooltip(item)
        if (isDisabled(item)) foreground = UIManager.getColor("Label.disabledForeground")
        return this
    }
}
