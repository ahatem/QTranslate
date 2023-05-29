package com.pnix.qtranslate.presentation.history_dialog

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.icons.FlatSearchIcon
import com.pnix.qtranslate.models.TranslationHistory
import com.pnix.qtranslate.models.TranslationHistorySnapshot
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.SimpleDocumentListener
import com.pnix.qtranslate.utils.createButtonWithIcon
import com.pnix.qtranslate.utils.setPadding
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


class HistoryDialog(frame: Frame) : JDialog(frame, "History", false) {

  data class TreeHistorySubNodeItem(val snapshot: TranslationHistorySnapshot) {
    companion object {
      val translators = QTranslateViewModel.translators
      val longestLength = translators.maxBy { t -> t.serviceName.length }.serviceName.length
    }

    override fun toString(): String {
      val translatorName = "%-${longestLength}s".format(translators[snapshot.selectedTranslatorIndex].serviceName)
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
      putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search")
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
      layout = BoxLayout(this, BoxLayout.X_AXIS)

      add(JButton("Clear").apply {
        addActionListener {
          TranslationHistory.clear()
          rootNode.removeAllChildren()
          (historyTree.model as DefaultTreeModel).reload()
        }
      })
      add(JButton("Save As").apply { isEnabled = false })
      add(
        createButtonWithIcon("app-icons/star.svg", 13, "Toggle favourite (Not supported yet.)").apply {
          isEnabled = false
        }
      )
      add(Box.createHorizontalGlue())
      add(JButton("Ok").apply { addActionListener { dispose() } })
    }

    add(searchField, BorderLayout.NORTH)
    add(JScrollPane(historyTree))
    add(buttons, BorderLayout.SOUTH)

    pack()
    setLocationRelativeTo(frame)
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
  }
}