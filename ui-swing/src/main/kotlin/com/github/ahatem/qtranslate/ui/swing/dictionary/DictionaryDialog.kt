package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import javax.swing.*
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ServiceInfoRenderer

class DictionaryDialog(owner: Frame, iconManager: IconManager) : JDialog(null as Frame?, false) {

    private var state: DictionaryDialogState? = null
    private var updatingFromState = false

    private val searchField = JTextField()
    private val lookupButton = JButton()
    private val closeButton = JButton()

    private val hintLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val loadingLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val resultView = DictionaryResultView(iconManager)
    private val cardPanel = JPanel(java.awt.CardLayout())

    private val serviceCombo = JComboBox<ServiceInfo>().apply {
        putClientProperty("JComboBox.isTableCellEditor", true)
        renderer = ServiceInfoRenderer(iconManager)
    }
    private val serviceRow = JPanel(BorderLayout(6, 0)).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        add(serviceCombo, BorderLayout.CENTER)
        isVisible = false
    }

    init {
        com.github.ahatem.qtranslate.ui.swing.shared.util.AppIcons.applyTo(this)
        defaultCloseOperation = HIDE_ON_CLOSE
        isResizable = true

        val searchPanel = JPanel(BorderLayout(8, 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(lookupButton, BorderLayout.LINE_END)
        }

        val topPanel = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            add(serviceRow, BorderLayout.NORTH)
            add(searchPanel, BorderLayout.CENTER)
        }

        cardPanel.add(hintLabel, "hint")
        cardPanel.add(loadingLabel, "loading")
        cardPanel.add(resultView, "results")

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            add(closeButton)
        }
        closeButton.putClientProperty("JButton.buttonType", "default")

        contentPane = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            add(topPanel, BorderLayout.NORTH)
            add(cardPanel, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }

        searchField.addActionListener { triggerLookup() }
        lookupButton.addActionListener { triggerLookup() }
        closeButton.addActionListener { isVisible = false }

        serviceCombo.addActionListener {
            if (!updatingFromState) {
                val selected = serviceCombo.selectedItem as? ServiceInfo ?: return@addActionListener
                state?.onDictionarySelected?.invoke(selected.id)
            }
        }
    }

    fun setSearchWord(word: String) {
        searchField.text = word
    }

    fun render(newState: DictionaryDialogState) {
        state = newState

        title = newState.title
        lookupButton.text = newState.lookupButtonLabel
        closeButton.text = newState.closeLabel
        loadingLabel.text = newState.loadingMessage

        hintLabel.text = when {
            newState.hasFailed -> newState.errorMessage
            newState.lookedUpWord.isNotBlank() && !newState.isLoading && newState.entries.isEmpty() ->
                newState.notFoundMessage
            else -> newState.hintMessage
        }

        // Service picker — visible whenever at least one dictionary is available.
        updatingFromState = true
        try {
            val dicts = newState.availableDictionaries
            serviceRow.isVisible = dicts.isNotEmpty()
            if (dicts.isNotEmpty()) {
                if (serviceCombo.itemCount != dicts.size ||
                    (0 until serviceCombo.itemCount).any { serviceCombo.getItemAt(it) != dicts[it] }
                ) {
                    serviceCombo.removeAllItems()
                    dicts.forEach { serviceCombo.addItem(it) }
                }
                val toSelect = dicts.find { it.id == newState.selectedDictionaryId }
                if (toSelect != null && serviceCombo.selectedItem != toSelect) {
                    serviceCombo.selectedItem = toSelect
                }
            }
        } finally {
            updatingFromState = false
        }

        val card = when {
            newState.isLoading -> "loading"
            newState.entries.isNotEmpty() -> "results"
            else -> "hint"
        }
        (cardPanel.layout as java.awt.CardLayout).show(cardPanel, card)

        if (newState.entries.isNotEmpty()) {
            resultView.render(
                entries = newState.entries,
                synonymsLabel = newState.synonymsLabel,
                onSynonymClicked = { word ->
                    searchField.text = word
                    state?.onLookup?.invoke(word)
                },
                listenTooltip = newState.listenTooltip,
                stopTooltip = newState.stopListeningTooltip,
                onListen = newState.onListen,
                onStopListening = newState.onStopListening
            )
        }
        resultView.setSpeaking(newState.isTtsPlaying)

        if (!isVisible) {
            pack()
            minimumSize = Dimension(UIScale.scale(480), UIScale.scale(400))
            preferredSize = Dimension(UIScale.scale(560), UIScale.scale(520))
            pack()
            setLocationRelativeTo(owner)
        }
    }

    private fun triggerLookup() {
        val word = searchField.text.trim()
        if (word.isNotBlank()) state?.onLookup?.invoke(word)
    }
}
