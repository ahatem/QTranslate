package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import java.awt.Dimension
import javax.swing.*

/**
 * Manages the horizontal word-chip strip shared by [DictionaryPanel] and [QuickDictionaryDialog].
 *
 * Attach [scrollPane] into your layout at the appropriate position.  Call [setup], [clear], and
 * [removeChipForWord] in response to user actions, and supply [onWordSelected] to receive
 * clicks.
 */
class DictionaryChipController(
    private val onWordSelected: (word: String) -> Unit
) {
    private val chipsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    }

    val scrollPane: JScrollPane = JScrollPane(chipsPanel).apply {
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        clearBorder()
        isOpaque = false
        viewport.isOpaque = false
        isVisible = false
    }

    private var chipsGroup = ButtonGroup()
    private var chipButtons: List<JToggleButton> = emptyList()

    val hasChips: Boolean get() = chipButtons.isNotEmpty()

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Parse [input] and return the individual words (splits on whitespace/comma). */
    fun parseWords(input: String): List<String> =
        input.split(Regex("[,\\s]+"))
            .map { it.trim() }
            .filter { word -> word.length >= 2 && word.all { it.isLetter() || it == '\'' || it == '-' } }
            .distinct()
            .take(20)

    /** Build the chip strip for [words] and make it visible. Selects the first chip. */
    fun setup(words: List<String>) {
        chipsPanel.removeAll()
        chipsGroup = ButtonGroup()
        chipButtons = words.mapIndexed { index, word ->
            JToggleButton(word).apply {
                putClientProperty("JButton.buttonType", "toolBarButton")
                isFocusable = false
                isSelected = index == 0
                addActionListener {
                    if (isSelected) onWordSelected(word)
                }
            }.also { btn ->
                chipsGroup.add(btn)
                chipsPanel.add(btn)
                if (index < words.size - 1) chipsPanel.add(Box.createRigidArea(Dimension(4, 0)))
            }
        }
        scrollPane.isVisible = true
        chipsPanel.revalidate()
        chipsPanel.repaint()
    }

    /** Clear all chips and hide the strip. */
    fun clear() {
        if (chipButtons.isEmpty()) return
        chipsPanel.removeAll()
        chipsGroup = ButtonGroup()
        chipButtons = emptyList()
        scrollPane.isVisible = false
        chipsPanel.revalidate()
        chipsPanel.repaint()
    }

    /**
     * Highlight the chip for [word] if it exists and is not already selected.
     * Used to keep the strip in sync when the lookup result arrives.
     */
    fun syncSelection(word: String) {
        chipButtons.firstOrNull { it.text == word }
            ?.takeIf { !it.isSelected }
            ?.isSelected = true
    }

    /**
     * Remove the chip for [word] (called when a word yields no result).
     * Clears the entire strip when no chips remain.
     */
    fun removeChipForWord(word: String) {
        val index = chipButtons.indexOfFirst { it.text == word }
        if (index < 0) return
        val chip = chipButtons[index]
        chipsGroup.remove(chip)
        val compIndex = chipsPanel.components.indexOf(chip)
        if (compIndex >= 0) {
            val nextComp = chipsPanel.components.getOrNull(compIndex + 1)
            if (nextComp is Box.Filler) chipsPanel.remove(nextComp)
            chipsPanel.remove(chip)
        }
        chipButtons = chipButtons - chip
        if (chipButtons.isEmpty()) clear() else {
            chipsPanel.revalidate()
            chipsPanel.repaint()
        }
    }
}
