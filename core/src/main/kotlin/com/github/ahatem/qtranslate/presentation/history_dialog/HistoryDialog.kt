package com.github.ahatem.qtranslate.presentation.history_dialog

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.icons.FlatSearchIcon
import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.models.Configurations
import com.github.ahatem.qtranslate.models.TranslationHistory
import com.github.ahatem.qtranslate.models.TranslationHistorySnapshot
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.utils.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Frame
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel


class HistoryDialog(frame: Frame) : JDialog(frame, Localizer.localize("history_panel_title"), false) {

    data class TreeHistorySubNodeItem(val snapshot: TranslationHistorySnapshot) {
        companion object {
            val translators = QTranslateViewModel.translators
            val longestLength = translators.maxBy { t -> t.localizedName.length }.localizedName.length
        }

        override fun toString(): String {
            val translatorName =
                "%-${longestLength}s".format(translators[snapshot.selectedTranslatorIndex].localizedName)
            return "${translatorName}: ${snapshot.translatedText}"
        }
    }

    private val rootNode = DefaultMutableTreeNode("History")
    private val historyTree = JTree(rootNode)

    init {
        layout = BorderLayout()
        preferredSize = Dimension(395, (0.75 * frame.height).toInt())
        setPadding(4)

        val searchField = JTextField().apply {
            putClientProperty(
                FlatClientProperties.PLACEHOLDER_TEXT,
                Localizer.localize("history_panel_input_placeholder_search")
            )
            putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, FlatSearchIcon())
            putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
            document.addDocumentListener(SimpleDocumentListener { loadHistory(text) })
        }
        historyTree.cellRenderer = object : DefaultTreeCellRenderer() {
            private val border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            override fun getTreeCellRendererComponent(
                tree: JTree, value: Any, sel: Boolean,
                expanded: Boolean, leaf: Boolean, row: Int,
                hasFocus: Boolean
            ): Component {
                val label = super
                    .getTreeCellRendererComponent(
                        tree, value, sel, expanded, leaf, row,
                        hasFocus
                    ) as JLabel
                label.border = border
                return label
            }
        }
        historyTree.isRootVisible = false
        historyTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val selectedNode = historyTree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    if (selectedNode != null && selectedNode.childCount == 0) {
                        val treeHistorySubNodeItem = selectedNode.userObject as TreeHistorySubNodeItem
                        QTranslateViewModel.updateState(treeHistorySubNodeItem.snapshot)
                    }
                }
            }
        })
        loadHistory()

        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.LINE_AXIS)

            add(JButton(Localizer.localize("history_panel_button_text_clear")).apply {
                addActionListener {
                    TranslationHistory.clear()
                    rootNode.removeAllChildren()
                    (historyTree.model as DefaultTreeModel).reload()
                }
            })
            add(JButton(Localizer.localize("history_panel_button_text_save_as")).apply { isEnabled = false })
            add(
                createButtonWithIcon("app-icons/star.svg", 13, "Toggle favourite (Not supported yet.)").apply {
                    isEnabled = false
                }
            )
            add(Box.createHorizontalGlue())

            add(JButton(Localizer.localize("history_panel_button_text_ok")).apply {
                this@HistoryDialog.rootPane.defaultButton = this
                addActionListener { dispose() }
            })
        }

        add(searchField, BorderLayout.NORTH)
        add(JScrollPane(historyTree).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(2, 0, 2, 0), border
            )
        })
        add(buttons, BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(frame)
        applyComponentOrientation(frame.componentOrientation)
        isVisible = true
    }

    private fun loadHistory(searchText: String = "") {
        rootNode.removeAllChildren()
        TranslationHistory.snapshots.forEach { (originalText, translations) ->
            if (originalText.contains(searchText, ignoreCase = true)) {
                val node = DefaultMutableTreeNode(originalText)
                translations.forEach { snapShot ->
                    node.add(DefaultMutableTreeNode(TreeHistorySubNodeItem(snapShot)))
                }
                rootNode.add(node)
            }
        }
        (historyTree.model as DefaultTreeModel).reload()
        if (Configurations.expandHistoryItems) historyTree.expandAllNodes()
    }
}