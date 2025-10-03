package com.github.ahatem.qtranslate.presentation.main_frame.panels

import com.github.ahatem.qtranslate.presentation.components.QtTextPane
import com.github.ahatem.qtranslate.presentation.components.QtTextPaneListeners
import com.github.ahatem.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.utils.copyToClipboard
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

class TranslationOutputPanel : JPanel(BorderLayout()) {

    val outputTextArea = QtTextPane().apply {
        isEditable = false
        listener = object : QtTextPaneListeners {
            override fun onMenuItemTranslateClicked(selectedText: String) {
                GlobalScope.launch {
                    QTranslateViewModel.setInputText(selectedText)
                    QTranslateViewModel.translate()
                }
            }

            override fun onMenuItemListenClicked(selectedText: String) {
                GlobalScope.launch { QTranslateViewModel.listenToTranslation(selectedText) }
            }
        }
    }

    init {
        layout = BorderLayout()
        val scrollPane = JScrollPane(outputTextArea)

        val buttonsPanel = TranslationActionsPanel().apply {
            copyButton.addActionListener { outputTextArea.text.copyToClipboard() }
            listenButton.addActionListener(WindowKeyListeners.ListenToTranslation.action)
        }

        val overlayPanel = JPanel(BorderLayout()).apply {
            add(buttonsPanel, BorderLayout.LINE_END)
            add(scrollPane, BorderLayout.CENTER)
        }

        add(overlayPanel, BorderLayout.CENTER)
    }

}