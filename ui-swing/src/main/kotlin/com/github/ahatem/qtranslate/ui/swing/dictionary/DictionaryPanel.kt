package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.*

class DictionaryPanel(
    private val iconManager: IconManager,
    private val onLookup: (word: String) -> Unit,
    private val onServiceSelected: (serviceId: String) -> Unit,
    private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {

    private var isInitialized = false

    private val searchField = JTextField()
    private val lookupButton = JButton()

    private val hintLabel = JLabel("", SwingConstants.CENTER)
    private val loadingLabel = JLabel("", SwingConstants.CENTER)
    private val resultView = DictionaryResultView()
    private val cardPanel = JPanel(CardLayout())

    private val serviceCombo = JComboBox<ServiceInfo>().apply {
        putClientProperty("JComboBox.isTableCellEditor", true)
        setRenderer { _, value, _, _, _ -> JLabel(value?.name ?: "") }
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
        iconManager.getIcon("icons/lucide/link-2.svg", 13, 13) as FlatSVGIcon
    private val offUnlinkIconBase: FlatSVGIcon =
        iconManager.getIcon("icons/lucide/unlink.svg", 13, 13) as FlatSVGIcon

    private val autoSourceButton = JButton().apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        iconTextGap = 4
    }
    private var currentAutoSource: DictionaryAutoSource = DictionaryAutoSource.TRANSLATED

    init {
        val titleLabel = JLabel().apply { putClientProperty("FlatLaf.styleClass", "h4") }
        val closeButton = JButton().apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            addActionListener { onClose() }
        }

        val rightButtons = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(autoSourceButton)
            add(Box.createRigidArea(Dimension(4, 0)))
            add(closeButton)
        }

        val headerPanel = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            add(titleLabel, BorderLayout.CENTER)
            add(rightButtons, BorderLayout.LINE_END)
            putClientProperty("titleLabel", titleLabel)
            putClientProperty("closeButton", closeButton)
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

        // Build border — defer to refreshBorder() to avoid duplication
        add(topPanel, BorderLayout.NORTH)
        add(contentArea, BorderLayout.CENTER)

        putClientProperty("headerPanel", headerPanel)

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
        refreshBorder()
        refreshLabelColors()
        refreshIcons()
    }

    private fun refreshBorder() {
        val borderColor = UIManager.getColor("Component.borderColor")
            ?: UIManager.getColor("Panel.background")?.darker()
            ?: Color.GRAY

        // The rule separates this panel from the content it is docked beside, so it belongs on
        // whichever edge faces that content — the left in a left-to-right interface, the right in
        // a right-to-left one, where the panel sits on the other side of the divider.
        val facingContent = if (componentOrientation.isLeftToRight) 1 else 0
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, facingContent, 0, 1 - facingContent, borderColor),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)
        )
    }

    override fun setComponentOrientation(orientation: java.awt.ComponentOrientation) {
        super.setComponentOrientation(orientation)
        refreshBorder()
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
        val headerPanel = getClientProperty("headerPanel") as? JPanel
        (headerPanel?.getClientProperty("titleLabel") as? JLabel)?.text = state.title
        (headerPanel?.getClientProperty("closeButton") as? JButton)?.apply {
            text = state.closeLabel
            toolTipText = state.closeLabel
        }

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
                }
            )
        }
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
