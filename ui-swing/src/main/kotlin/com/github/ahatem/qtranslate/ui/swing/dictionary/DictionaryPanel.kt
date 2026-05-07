package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class DictionaryPanel(
    private val onLookup: (word: String) -> Unit,
    private val onServiceSelected: (serviceId: String) -> Unit,
    private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {

    private val searchField = JTextField()
    private val lookupButton = JButton()

    private val hintLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val loadingLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val resultView = DictionaryResultView()
    private val cardPanel = JPanel(java.awt.CardLayout())

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

    // Single-row horizontal chip strip with scroll.
    private val wordChipsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    }
    private val wordChipsScroll = JScrollPane(wordChipsPanel).apply {
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        border    = null
        isOpaque  = false
        viewport.isOpaque = false
        isVisible = false
    }
    private var chipsGroup  = ButtonGroup()
    private var chipButtons: List<JToggleButton> = emptyList()

    private var updatingFromState = false

    init {
        val titleLabel = JLabel().apply { putClientProperty("FlatLaf.styleClass", "h4") }
        val closeButton = JButton().apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            addActionListener { onClose() }
        }

        val headerPanel = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            add(titleLabel, BorderLayout.CENTER)
            add(closeButton, BorderLayout.LINE_END)
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
            add(wordChipsScroll, BorderLayout.NORTH)
            add(cardPanel, BorderLayout.CENTER)
        }

        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(
                0, 1, 0, 0,
                UIManager.getColor("Component.borderColor")
                    ?: UIManager.getColor("Separator.foreground")
            ),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)
        )

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
    }

    fun render(state: DictionaryPanelState) {
        val headerPanel = getClientProperty("headerPanel") as? JPanel
        (headerPanel?.getClientProperty("titleLabel") as? JLabel)?.text = state.title
        (headerPanel?.getClientProperty("closeButton") as? JButton)?.apply {
            text = state.closeLabel
            toolTipText = state.closeLabel
        }

        lookupButton.text = state.lookupButtonLabel
        loadingLabel.text = state.loadingMessage

        hintLabel.text = when {
            state.hasFailed -> state.errorMessage
            state.lookedUpWord.isNotBlank() && !state.isLoading && state.entries.isEmpty() ->
                state.notFoundMessage
            else -> state.hintMessage
        }

        // Chip management — only act once a lookup has completed (not loading).
        if (chipButtons.isNotEmpty() && !state.isLoading && state.lookedUpWord.isNotBlank()) {
            if (state.entries.isEmpty() && !state.hasFailed) {
                // Word genuinely not found — drop its chip so it won't be revisited.
                removeChipForWord(state.lookedUpWord)
            } else if (state.entries.isNotEmpty()) {
                // Sync highlighted chip with whatever word is currently showing.
                chipButtons.firstOrNull { it.text == state.lookedUpWord }
                    ?.takeIf { !it.isSelected }
                    ?.isSelected = true
            }
        }

        // Service picker.
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
        (cardPanel.layout as java.awt.CardLayout).show(cardPanel, card)

        if (state.entries.isNotEmpty()) {
            resultView.render(
                entries = state.entries,
                synonymsLabel = state.synonymsLabel,
                onSynonymClicked = { word ->
                    clearChips()
                    searchField.text = word
                    onLookup(word)
                }
            )
        }
    }

    fun setSearchWord(word: String) {
        searchField.text = word
        if (!word.contains(Regex("[,\\s]"))) clearChips()
    }

    // --- Multi-word chip logic ---

    private fun triggerLookup() {
        val input = searchField.text.trim()
        if (input.isBlank()) return
        val words = parseWords(input)
        if (words.size > 1) {
            setupChips(words)
            onLookup(words.first())
        } else {
            clearChips()
            onLookup(input)
        }
    }

    private fun parseWords(input: String): List<String> =
        input.split(Regex("[,\\s]+"))
            .map { it.trim() }
            .filter { word ->
                word.length >= 2 && word.all { it.isLetter() || it == '\'' || it == '-' }
            }
            .distinct()
            .take(20)

    private fun setupChips(words: List<String>) {
        wordChipsPanel.removeAll()
        chipsGroup  = ButtonGroup()
        chipButtons = words.mapIndexed { index, word ->
            JToggleButton(word).apply {
                putClientProperty("JButton.buttonType", "toolBarButton")
                isFocusable = false
                isSelected   = index == 0
                addActionListener {
                    if (isSelected) {
                        searchField.text = word
                        onLookup(word)
                    }
                }
            }.also { btn ->
                chipsGroup.add(btn)
                wordChipsPanel.add(btn)
                // Gap between chips.
                if (index < words.size - 1) wordChipsPanel.add(Box.createRigidArea(Dimension(4, 0)))
            }
        }
        wordChipsScroll.isVisible = true
        wordChipsPanel.revalidate()
        wordChipsPanel.repaint()
    }

    private fun clearChips() {
        if (chipButtons.isEmpty()) return
        wordChipsPanel.removeAll()
        chipsGroup  = ButtonGroup()
        chipButtons = emptyList()
        wordChipsScroll.isVisible = false
        wordChipsPanel.revalidate()
        wordChipsPanel.repaint()
    }

    private fun removeChipForWord(word: String) {
        val index = chipButtons.indexOfFirst { it.text == word }
        if (index < 0) return

        val chip = chipButtons[index]
        chipsGroup.remove(chip)

        // Remove chip and its trailing gap if present (gap is the component after the button).
        val compIndex = wordChipsPanel.components.indexOf(chip)
        if (compIndex >= 0) {
            // Remove gap first (if it follows this chip) so indices stay valid.
            val nextComp = wordChipsPanel.components.getOrNull(compIndex + 1)
            if (nextComp is Box.Filler) wordChipsPanel.remove(nextComp)
            wordChipsPanel.remove(chip)
        }

        chipButtons = chipButtons - chip

        when {
            chipButtons.isEmpty() -> clearChips()
            else -> {
                wordChipsPanel.revalidate()
                wordChipsPanel.repaint()
            }
        }
    }
}
