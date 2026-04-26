package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

class LanguageComboBox(
    private val onLanguageSelected: (language: LanguageCode) -> Unit,
    private val localizer: LocalizationManager
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
        renderer = LanguageRenderer(this, localizer)
        addActionListener(actionListener)

        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (e.keyChar.isLetterOrDigit()) {
                    handleKeyTyped(e.keyChar)
                }
            }
        })
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

            this.selectedItem = selectedLanguage
            this.isEnabled = isEnabled
            isRendering = false
        }
    }

    private class LanguageRenderer(
        private val comboBox: JComboBox<LanguageCode>,
        private val localizer: LocalizationManager
    ) : DefaultListCellRenderer() {

        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is LanguageCode) {
                val autoDetectLabel = localizer.getString("common.auto_detect")
                val autoDetectedLanguage = comboBox.getClientProperty("autoDetectedLanguage") as? LanguageCode

                val displayName: String =
                    if (index == -1 && autoDetectedLanguage != null && value == LanguageCode.AUTO) {
                        "${autoDetectedLanguage.getDisplayName()} ($autoDetectLabel)"
                    } else {
                        value.getDisplayName(autoDetectLabel = autoDetectLabel)
                    }

                text = displayName
                componentOrientation = if (displayName.isRTL()) ComponentOrientation.RIGHT_TO_LEFT
                else ComponentOrientation.LEFT_TO_RIGHT
            }
            return this
        }
    }
}