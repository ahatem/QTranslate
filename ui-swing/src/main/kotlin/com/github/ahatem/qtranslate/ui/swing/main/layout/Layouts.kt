package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.*
import javax.swing.*

data class ComponentRegistry(
    val historyBar: JComponent,
    val inputPanel: JComponent,
    val languageBar: JComponent,
    val outputPanel: JComponent,
    val extraOutputPanel: JComponent,
    val translatorSelector: JComponent,
    val statusBar: JComponent
)

sealed interface LayoutComponentRefs {
    fun updateExtraOutputVisibility(visible: Boolean, extraPanel: JComponent)
    fun syncExtraOutputState(extraPanel: JComponent)

    data class WithSplitPanes(
        val mainSplit: JSplitPane,
        val extraSplit: JSplitPane
    ) : LayoutComponentRefs {
        override fun updateExtraOutputVisibility(visible: Boolean, extraPanel: JComponent) {
            SwingUtilities.invokeLater {
                val wasContinuous = extraSplit.isContinuousLayout
                extraSplit.isContinuousLayout = false
                extraPanel.isVisible = visible
                if (visible) {
                    extraSplit.dividerSize = UISpacing.DIVIDER_SIZE
                    extraSplit.resetToPreferredSizes()
                } else {
                    extraSplit.dividerSize = 0
                    extraSplit.setDividerLocation(1.0)
                }
                extraSplit.isContinuousLayout = wasContinuous
                extraSplit.revalidate()
                extraSplit.repaint()
            }
        }

        override fun syncExtraOutputState(extraPanel: JComponent) {
            updateExtraOutputVisibility(extraPanel.isVisible, extraPanel)
        }
    }

    data class WithTabs(
        val tabbedPane: JTabbedPane
    ) : LayoutComponentRefs {
        override fun updateExtraOutputVisibility(visible: Boolean, extraPanel: JComponent) {
            SwingUtilities.invokeLater {
                val extraIndex = tabbedPane.indexOfComponent(extraPanel.parent) // Find by component
                if (visible && extraIndex == -1) {
                    tabbedPane.addTab("Extra", LayoutBuilders.wrapContent(extraPanel))
                } else if (!visible && extraIndex != -1) {
                    tabbedPane.removeTabAt(extraIndex)
                }
            }
        }

        override fun syncExtraOutputState(extraPanel: JComponent) {
            updateExtraOutputVisibility(extraPanel.isVisible, extraPanel)
        }
    }
}

data class ArrangedLayout(
    val rootComponent: JComponent,
    val componentRefs: LayoutComponentRefs
)

enum class LayoutType(val localizeId: String) {
    CLASSIC("layout_preset_classic"),
    SIDE_BY_SIDE("layout_preset_side_by_side"),
    COMPACT("layout_preset_compact");

    val id: String = name.lowercase()
}

object LayoutBuilders {
    fun createSimpleTopBar(historyBar: JComponent): JComponent {
        return JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            }
            add(historyBar, gbc)
        }
    }

    fun createStackedTopBar(historyBar: JComponent, languageBar: JComponent): JComponent {
        return JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                anchor = GridBagConstraints.LINE_START
                fill = GridBagConstraints.HORIZONTAL
            }
            gbc.gridy = 0
            add(historyBar, gbc)

            gbc.gridy = 1
            gbc.insets = Insets(UISpacing.V_GAP, 0, 0, 0)
            add(languageBar, gbc)
        }
    }

    fun wrapWithPadding(component: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, UISpacing.PADDING, 0, UISpacing.PADDING)
            add(component, BorderLayout.CENTER)
        }
    }

    fun createBottomBar(translatorSelector: JComponent, statusBar: JComponent): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            val paddedTranslatorSelector = wrapWithPadding(translatorSelector)

            add(paddedTranslatorSelector)
            add(Box.createRigidArea(Dimension(0, UISpacing.V_GAP)))
            add(statusBar)
        }
    }

    fun wrapLanguageBar(languageBar: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(UISpacing.V_GAP, 0, UISpacing.V_GAP, 0)
            add(languageBar, BorderLayout.CENTER)
        }
    }

    fun wrapContent(content: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, UISpacing.H_GAP, 0, UISpacing.H_GAP)
            add(content, BorderLayout.CENTER)
        }
    }

    fun createVerticalSplit(
        top: JComponent,
        bottom: JComponent,
        resizeWeight: Double = 0.5,
        topMinHeight: Int = UISpacing.MIN_PANEL_HEIGHT,
        bottomMinHeight: Int = UISpacing.MIN_PANEL_HEIGHT
    ): JSplitPane {
        top.minimumSize = Dimension(0, topMinHeight)
        bottom.minimumSize = Dimension(0, bottomMinHeight)
        return JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom).apply {
            isContinuousLayout = true
            this.resizeWeight = resizeWeight
            dividerSize = UISpacing.DIVIDER_SIZE
            border = null
        }
    }

    fun createHorizontalSplit(
        left: JComponent,
        right: JComponent,
        resizeWeight: Double = 0.5,
        leftMinWidth: Int = UISpacing.MIN_PANEL_WIDTH,
        rightMinWidth: Int = UISpacing.MIN_PANEL_WIDTH,
        rtl: Boolean = false
    ): JSplitPane {
        val first = if (rtl) right else left
        val second = if (rtl) left else right
        val weight = if (rtl) 1.0 - resizeWeight else resizeWeight
        first.minimumSize = Dimension(leftMinWidth, 0)
        second.minimumSize = Dimension(rightMinWidth, 0)
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, first, second).apply {
            isContinuousLayout = true
            this.resizeWeight = weight
            dividerSize = UISpacing.DIVIDER_SIZE
            border = null
        }
    }
}

object ClassicLayout : LayoutStrategy {
    override val type = LayoutType.CLASSIC
    override fun arrange(components: ComponentRegistry, isRtl: Boolean): ArrangedLayout {
        val topBar = LayoutBuilders.createSimpleTopBar(components.historyBar)
        val bottomBar = LayoutBuilders.createBottomBar(components.translatorSelector, components.statusBar)

        val outputSection = JPanel(BorderLayout()).apply {
            add(LayoutBuilders.wrapLanguageBar(components.languageBar), BorderLayout.NORTH)
            add(components.outputPanel, BorderLayout.CENTER)
        }
        val mainSplit = LayoutBuilders.createVerticalSplit(
            top = components.inputPanel, bottom = outputSection, resizeWeight = 0.5
        )
        val extraSplit = LayoutBuilders.createVerticalSplit(
            top = mainSplit,
            bottom = components.extraOutputPanel,
            resizeWeight = 0.8,
            bottomMinHeight = UISpacing.MIN_EXTRA_HEIGHT
        )

        val contentPanel = JPanel(BorderLayout(0, UISpacing.V_GAP)).apply {
            border = BorderFactory.createEmptyBorder(
                UISpacing.PADDING,
                UISpacing.PADDING,
                UISpacing.V_GAP,
                UISpacing.PADDING
            )
            add(topBar, BorderLayout.NORTH)
            add(extraSplit, BorderLayout.CENTER)
        }

        val root = JPanel(BorderLayout()).apply {
            add(contentPanel, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        val refs = LayoutComponentRefs.WithSplitPanes(mainSplit, extraSplit)
        refs.syncExtraOutputState(components.extraOutputPanel)
        return ArrangedLayout(root, refs)
    }
}

object SideBySideLayout : LayoutStrategy {
    override val type = LayoutType.SIDE_BY_SIDE
    override fun arrange(components: ComponentRegistry, isRtl: Boolean): ArrangedLayout {
        val topBar = LayoutBuilders.createStackedTopBar(components.historyBar, components.languageBar)
        val bottomBar = LayoutBuilders.createBottomBar(components.translatorSelector, components.statusBar)

        val mainSplit = LayoutBuilders.createHorizontalSplit(
            left = components.inputPanel, right = components.outputPanel,
            resizeWeight = 0.5, rtl = isRtl
        )
        val extraSplit = LayoutBuilders.createVerticalSplit(
            top = mainSplit,
            bottom = components.extraOutputPanel,
            resizeWeight = 0.8,
            bottomMinHeight = UISpacing.MIN_EXTRA_HEIGHT
        )

        val contentPanel = JPanel(BorderLayout(0, UISpacing.V_GAP)).apply {
            border = BorderFactory.createEmptyBorder(
                UISpacing.PADDING,
                UISpacing.PADDING,
                UISpacing.V_GAP,
                UISpacing.PADDING
            )
            add(topBar, BorderLayout.NORTH)
            add(extraSplit, BorderLayout.CENTER)
        }

        val root = JPanel(BorderLayout()).apply {
            add(contentPanel, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        val refs = LayoutComponentRefs.WithSplitPanes(mainSplit, extraSplit)
        refs.syncExtraOutputState(components.extraOutputPanel)
        return ArrangedLayout(root, refs)
    }
}

object CompactLayout : LayoutStrategy {
    override val type = LayoutType.COMPACT
    override fun arrange(components: ComponentRegistry, isRtl: Boolean): ArrangedLayout {
        val topBar = LayoutBuilders.createStackedTopBar(components.historyBar, components.languageBar)
        val bottomBar = LayoutBuilders.createBottomBar(components.translatorSelector, components.statusBar)

        val tabs = JTabbedPane().apply {
            addTab("Input", components.inputPanel)
            addTab("Output", components.outputPanel)
            setupKeyboardShortcuts(this)
        }

        val contentPanel = JPanel(BorderLayout(0, UISpacing.V_GAP)).apply {
            border = BorderFactory.createEmptyBorder(
                UISpacing.PADDING,
                UISpacing.PADDING,
                UISpacing.V_GAP,
                UISpacing.PADDING
            )
            add(topBar, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        val root = JPanel(BorderLayout()).apply {
            add(contentPanel, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        val refs = LayoutComponentRefs.WithTabs(tabs)
        refs.syncExtraOutputState(components.extraOutputPanel)
        return ArrangedLayout(root, refs)
    }

    private fun setupKeyboardShortcuts(tabs: JTabbedPane) {
        tabs.actionMap.put("tab1", createTabSwitchAction(tabs, 0))
        tabs.actionMap.put("tab2", createTabSwitchAction(tabs, 1))
        tabs.inputMap.put(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_1, java.awt.event.InputEvent.ALT_DOWN_MASK),
            "tab1"
        )
        tabs.inputMap.put(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_2, java.awt.event.InputEvent.ALT_DOWN_MASK),
            "tab2"
        )
    }

    private fun createTabSwitchAction(tabs: JTabbedPane, index: Int) = object : AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent) {
            if (index < tabs.tabCount && tabs.isEnabledAt(index)) tabs.selectedIndex = index
        }
    }
}

