package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import com.formdev.flatlaf.util.UIScale
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Dimension
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicComboPopup

/**
 * @param compact shows only the language code on the closed control, with full names still in the
 *   list. For places with no room for "Chinese (Simplified)" on screen at all — the quick
 *   translate popup, which can be narrower than one such name. The same shape as the translator
 *   picker beside it, which shows an icon closed and full names open.
 */
class LanguageComboBox(
    private val onLanguageSelected: (language: LanguageCode) -> Unit,
    private val localizer: LocalizationManager,
    private val compact: Boolean = false
) : JComboBox<LanguageCode>() {

    private var isRendering = false
    private var currentLanguages: List<LanguageCode> = emptyList()

    private val actionListener = ActionListener {
        if (!isRendering) {
            (selectedItem as? LanguageCode)?.let { onLanguageSelected(it) }
        }
    }

    private val searchStringBuilder = StringBuilder()
    private val searchResetTimer = Timer(500) {
        searchStringBuilder.clear()
    }.apply {
        isRepeats = false
    }

    init {
        renderer = LanguageRenderer(this, localizer, compact)
        addActionListener(actionListener)

        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (e.keyChar.isLetterOrDigit()) {
                    handleKeyTyped(e.keyChar)
                }
            }
        })

        if (compact) {
            // A combo sizes itself to its widest item, and the items are full language names, so
            // it would still take the width of "Chinese (Simplified)" while displaying "ZH-CN".
            // The prototype is the widest code rather than the widest name.
            prototypeDisplayValue = LanguageCode(WIDEST_CODE)

            // The list, however, still has to fit the names. Swing sizes the drop-down to the
            // control, which after the line above is far too narrow to read, so it is widened
            // back out as it opens.
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) =
                    widenPopupToFitNames()

                override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = Unit
                override fun popupMenuCanceled(event: PopupMenuEvent) = Unit
            })
        }
    }

    /**
     * Gives the open list enough width for the longest name in it.
     *
     * Measured rather than guessed, because the names are localized and translated: whatever is
     * longest in English is not what is longest in Arabic or Japanese.
     */
    private fun widenPopupToFitNames() {
        val popup = getAccessibleContext()?.getAccessibleChild(0) as? BasicComboPopup ?: return
        val scrollPane = popup.getComponent(0) as? JScrollPane ?: return

        val autoDetectLabel = localizer.getString("common.auto_detect")
        val metrics = getFontMetrics(font)
        val widest = (0 until model.size).maxOfOrNull { index ->
            metrics.stringWidth(model.getElementAt(index).getDisplayName(autoDetectLabel = autoDetectLabel))
        } ?: return

        val padding = UIScale.scale(POPUP_PADDING)
        scrollPane.preferredSize = Dimension(
            maxOf(widest + padding, width),
            scrollPane.preferredSize.height
        )
    }

    private fun handleKeyTyped(key: Char) {
        searchResetTimer.stop()
        searchStringBuilder.append(key)
        val searchString = searchStringBuilder.toString()

        val model = this.model
        for (i in 0 until model.size) {
            val item = model.getElementAt(i)
            val displayString = item?.getDisplayName(
                autoDetectLabel = localizer.getString("common.auto_detect")
            ) ?: item.toString()

            if (displayString.startsWith(searchString, ignoreCase = true)) {
                this.selectedIndex = i
                searchResetTimer.start()
                return
            }
        }

        searchStringBuilder.clear()
    }

    fun render(
        availableLanguages: List<LanguageCode>,
        selectedLanguage: LanguageCode?,
        autoDetectedLanguage: LanguageCode?,
        isEnabled: Boolean
    ) {
        SwingUtilities.invokeLater {
            isRendering = true
            putClientProperty("autoDetectedLanguage", autoDetectedLanguage)

            if (currentLanguages != availableLanguages) {
                model = DefaultComboBoxModel(availableLanguages.toTypedArray())
                currentLanguages = availableLanguages
            }

            // Only update the selection when the language is actually present in the current model.
            // If the model is still empty (plugins not yet loaded) or the language was filtered out
            // by pinnedLanguages, leave the current selection untouched so we don't silently
            // display the wrong language or trigger a spurious onLanguageSelected callback.
            if (selectedLanguage != null && selectedLanguage in availableLanguages) {
                this.selectedItem = selectedLanguage
            }
            this.isEnabled = isEnabled
            isRendering = false
        }
    }

    private class LanguageRenderer(
        private val comboBox: JComboBox<LanguageCode>,
        private val localizer: LocalizationManager,
        private val compact: Boolean
    ) : DefaultListCellRenderer() {

        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is LanguageCode) {
                val autoDetectLabel = localizer.getString("common.auto_detect")
                val autoDetectedLanguage = comboBox.getClientProperty("autoDetectedLanguage") as? LanguageCode

                // index == -1 is the closed control; anything else is a row in the open list.
                val isClosedControl = index == -1

                val fullName: String =
                    if (isClosedControl && autoDetectedLanguage != null && value == LanguageCode.AUTO) {
                        "${autoDetectedLanguage.getDisplayName()} ($autoDetectLabel)"
                    } else {
                        value.getDisplayName(autoDetectLabel = autoDetectLabel)
                    }

                if (compact && isClosedControl) {
                    // The code of whatever is actually in use. With Auto resolved, that is the
                    // detected language rather than the word "auto", which is the more useful of
                    // the two in four characters.
                    val effective = if (value == LanguageCode.AUTO) autoDetectedLanguage ?: value else value
                    text = effective.tag.uppercase()
                    // The name the code stands for, since the code alone is not always obvious.
                    toolTipText = fullName
                    componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
                } else {
                    text = fullName
                    toolTipText = null
                    componentOrientation = if (fullName.isRTL()) ComponentOrientation.RIGHT_TO_LEFT
                    else ComponentOrientation.LEFT_TO_RIGHT
                }
            }
            return this
        }
    }

    private companion object {
        /** Sized against the longest code in use rather than the longest name. */
        const val WIDEST_CODE = "zh-CN"

        /** Room for the list's own borders and scrollbar beside the widest name. */
        const val POPUP_PADDING = 40
    }
}