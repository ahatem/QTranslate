package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createToolbarButton
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ServiceInfoRenderer

/**
 * The dictionary lookup: a search field, the results, and the dictionary and auto-lookup pickers.
 *
 * Content only. Its title, its close control and the boundary against the workspace belong to
 * whatever hosts it, which is the lookup dock, so it draws no header or edge of its own.
 */
class DictionaryPanel(
    private val iconManager: IconManager,
    private val onLookup: (word: String) -> Unit,
    private val onServiceSelected: (serviceId: String) -> Unit,
) : JPanel(BorderLayout()) {

    private var isInitialized = false

    private val searchField = JTextField()
    private val lookupButton = JButton()

    private val hintLabel = JLabel("", SwingConstants.CENTER)
    private val loadingLabel = JLabel("", SwingConstants.CENTER)
    private val resultView = DictionaryResultView(iconManager)
    private val cardPanel = JPanel(CardLayout())

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

    private val chips = DictionaryChipController { word ->
        searchField.text = word
        onLookup(word)
    }

    private var updatingFromState = false

    private val activeLinkIconBase: FlatSVGIcon =
        iconManager.getIcon(Icons.NETWORK, 13, 13) as FlatSVGIcon
    private val offUnlinkIconBase: FlatSVGIcon =
        iconManager.getIcon(Icons.UNPIN, 13, 13) as FlatSVGIcon

    private val autoSourceButton = createToolbarButton().apply {
        iconTextGap = 4
    }
    private var currentAutoSource: DictionaryAutoSource = DictionaryAutoSource.TRANSLATED

    init {
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, UIScale.scale(8), 0)
            add(autoSourceButton)
        }

        val searchPanel = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            add(searchField, BorderLayout.CENTER)
            add(lookupButton, BorderLayout.LINE_END)
        }

        cardPanel.add(hintLabel, "hint")
        cardPanel.add(loadingLabel, "loading")
        cardPanel.add(resultView, "results")

        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerPanel, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(serviceRow, BorderLayout.NORTH)
                add(searchPanel, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        val contentArea = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(chips.scrollPane, BorderLayout.NORTH)
            add(cardPanel, BorderLayout.CENTER)
        }

        add(topPanel, BorderLayout.NORTH)
        add(contentArea, BorderLayout.CENTER)

        searchField.addActionListener { triggerLookup() }
        lookupButton.addActionListener { triggerLookup() }

        serviceCombo.addActionListener {
            if (!updatingFromState) {
                val selected = serviceCombo.selectedItem as? ServiceInfo ?: return@addActionListener
                onServiceSelected(selected.id)
            }
        }

        autoSourceButton.addActionListener {
            val next = when (currentAutoSource) {
                DictionaryAutoSource.OFF -> DictionaryAutoSource.TRANSLATED
                DictionaryAutoSource.TRANSLATED -> DictionaryAutoSource.SOURCE
                DictionaryAutoSource.SOURCE -> DictionaryAutoSource.OFF
            }
            (getClientProperty("onAutoSourceChanged") as? (DictionaryAutoSource) -> Unit)?.invoke(next)
        }

        // Apply initial styling
        isInitialized = true
        refreshAllColors()
    }

    override fun updateUI() {
        super.updateUI()

        if (!isInitialized) return

        refreshAllColors()

    }

    private fun refreshAllColors() {
        refreshLabelColors()
        refreshIcons()
    }

    private fun refreshLabelColors() {
        val disabledColor = UIManager.getColor("Label.disabledForeground")
            ?: UIManager.getColor("Label.foreground")?.let {
                Color(it.red, it.green, it.blue, 128)
            }
            ?: Color.GRAY

        hintLabel.foreground = disabledColor
        loadingLabel.foreground = disabledColor
    }

    private fun refreshIcons() {
        val accentColor = UIManager.getColor("Component.accentColor")
            ?: UIManager.getColor("Actions.Blue")
            ?: Color(0x2675BF)

        val disabledColor = UIManager.getColor("Label.disabledForeground")
            ?: UIManager.getColor("Label.foreground")?.let {
                Color(it.red, it.green, it.blue, 128)
            }
            ?: Color.GRAY

        // Recreate icons with current theme colors
        activeLinkIconBase.colorFilter = FlatSVGIcon.ColorFilter { accentColor }
        offUnlinkIconBase.colorFilter = FlatSVGIcon.ColorFilter { disabledColor }
    }

    fun render(state: DictionaryPanelState) {
        // Sync auto-source button
        putClientProperty("onAutoSourceChanged", state.onAutoSourceChanged)
        if (currentAutoSource != state.autoSource) {
            currentAutoSource = state.autoSource
        }
        val (autoLabel, autoTip) = when (state.autoSource) {
            DictionaryAutoSource.OFF -> state.autoSourceOffLabel to state.autoSourceOffLabel
            DictionaryAutoSource.TRANSLATED -> state.autoSourceTranslatedLabel to state.autoSourceTranslatedLabel
            DictionaryAutoSource.SOURCE -> state.autoSourceSourceLabel to state.autoSourceSourceLabel
        }
        autoSourceButton.text = autoLabel
        autoSourceButton.toolTipText = autoTip

        val isActive = state.autoSource != DictionaryAutoSource.OFF
        val accentColor = UIManager.getColor("Component.accentColor")
            ?: UIManager.getColor("Actions.Blue")
            ?: Color(0x2675BF)
        val disabledColor = UIManager.getColor("Label.disabledForeground")
            ?: Color.GRAY

        autoSourceButton.icon = if (isActive) activeLinkIconBase else offUnlinkIconBase
        autoSourceButton.foreground = if (isActive) accentColor else disabledColor

        lookupButton.text = state.lookupButtonLabel
        loadingLabel.text = state.loadingMessage

        hintLabel.text = when {
            state.hasFailed -> state.errorMessage
            state.lookedUpWord.isNotBlank() && !state.isLoading && state.entries.isEmpty() ->
                state.notFoundMessage

            else -> state.hintMessage
        }

        // Chip management
        if (chips.hasChips && !state.isLoading && state.lookedUpWord.isNotBlank()) {
            if (state.entries.isEmpty() && !state.hasFailed) {
                chips.removeChipForWord(state.lookedUpWord)
            } else if (state.entries.isNotEmpty()) {
                chips.syncSelection(state.lookedUpWord)
            }
        }

        // Service picker
        updatingFromState = true
        try {
            val dicts = state.availableDictionaries
            serviceRow.isVisible = dicts.isNotEmpty()
            if (dicts.isNotEmpty()) {
                if (serviceCombo.itemCount != dicts.size ||
                    (0 until serviceCombo.itemCount).any { serviceCombo.getItemAt(it) != dicts[it] }
                ) {
                    serviceCombo.removeAllItems()
                    dicts.forEach { serviceCombo.addItem(it) }
                }
                val toSelect = dicts.find { it.id == state.selectedDictionaryId }
                if (toSelect != null && serviceCombo.selectedItem != toSelect) {
                    serviceCombo.selectedItem = toSelect
                }
            }
        } finally {
            updatingFromState = false
        }

        val card = when {
            state.isLoading -> "loading"
            state.entries.isNotEmpty() -> "results"
            else -> "hint"
        }
        (cardPanel.layout as CardLayout).show(cardPanel, card)

        if (state.entries.isNotEmpty()) {
            resultView.render(
                entries = state.entries,
                synonymsLabel = state.synonymsLabel,
                onSynonymClicked = { word ->
                    chips.clear()
                    searchField.text = word
                    onLookup(word)
                },
                listenTooltip = state.listenTooltip,
                stopTooltip = state.stopListeningTooltip,
                onListen = state.onListen,
                onStopListening = state.onStopListening
            )
        }
        resultView.setSpeaking(state.isTtsPlaying)
    }

    fun setSearchWord(word: String) {
        searchField.text = word
        if (!word.contains(Regex("[,\\s]"))) chips.clear()
    }

    private fun triggerLookup() {
        val input = searchField.text.trim()
        if (input.isBlank()) return
        val words = chips.parseWords(input)
        if (words.size > 1) {
            chips.setup(words)
            onLookup(words.first())
        } else {
            chips.clear()
            onLookup(input)
        }
    }
}
