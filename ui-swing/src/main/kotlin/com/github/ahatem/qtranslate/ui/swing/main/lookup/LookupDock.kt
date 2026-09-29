package com.github.ahatem.qtranslate.ui.swing.main.lookup

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.main.mvi.LookupTool
import com.github.ahatem.qtranslate.ui.swing.imagesearch.ImageSearchPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.createToolbarButton
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * The lookup region beside the translation workspace: the dictionary and the pictures, one at a
 * time, on tabs, with a single close control at the trailing end of the tab strip.
 *
 * It holds the two tools' content and nothing else, and moves between them without rebuilding
 * either, so a search, a scroll position or a half-typed word is where it was left. The tabs are
 * the look and feel's own, which brings keyboard navigation, mirroring and theming for free.
 */
class LookupDock(
    dictionary: JComponent,
    images: JComponent,
    iconManager: IconManager,
    private val onToolSelected: (LookupTool) -> Unit,
    private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {

    private val tabs = JTabbedPane()
    private val closeButton = createToolbarButton(iconManager, Icons.CLOSE, 14) { onClose() }

    /** Set while the tab is changed by [showTool], so the change is not reported back as a click. */
    private var isSelectingProgrammatically = false

    private val tools = listOf(LookupTool.DICTIONARY to dictionary, LookupTool.IMAGES to images)

    init {
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED)
        // The look and feel stretches a trailing component across the rest of the tab strip, so the
        // button sits in a holder that keeps it at the trailing edge and its click area its own size.
        val closeHolder = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(closeButton, BorderLayout.LINE_END)
        }
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, closeHolder)

        tools.forEach { (_, content) ->
            // Images already owns a margin equal to this wrapper's, as it must to also look right
            // floating on its own; wrapping it again would double it. Dictionary owns none of its
            // own, so it needs the wrapper as the only source of its outer breathing room.
            tabs.addTab("", if (content is ImageSearchPanel) content else padded(content))
        }

        tabs.addChangeListener {
            if (!isSelectingProgrammatically) onToolSelected(selectedTool)
        }
        add(tabs, BorderLayout.CENTER)
    }

    /** The tool the dock is showing. */
    val selectedTool: LookupTool get() = tools[tabs.selectedIndex].first

    /** Shows [tool] without reporting a selection. */
    fun showTool(tool: LookupTool) {
        val index = tools.indexOfFirst { it.first == tool }
        if (index < 0 || tabs.selectedIndex == index) return
        isSelectingProgrammatically = true
        try {
            tabs.selectedIndex = index
        } finally {
            isSelectingProgrammatically = false
        }
    }

    fun setLabels(dictionary: String, images: String, close: String) {
        tabs.setTitleAt(indexOf(LookupTool.DICTIONARY), dictionary)
        tabs.setTitleAt(indexOf(LookupTool.IMAGES), images)
        closeButton.toolTipText = close
        closeButton.accessibleContext.accessibleName = close
    }

    private fun indexOf(tool: LookupTool) = tools.indexOfFirst { it.first == tool }

    /** Equal breathing room around whichever tool is showing, so both read as one region. */
    private fun padded(content: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(
            UIScale.scale(12), UIScale.scale(12), UIScale.scale(12), UIScale.scale(12)
        )
        add(content, BorderLayout.CENTER)
    }

    internal fun tabsForTest(): JTabbedPane = tabs
    internal fun closeButtonForTest() = closeButton
}
