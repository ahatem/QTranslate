package com.pnix.qtranslate.presentation.history_dialog

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.icons.FlatSearchIcon
import com.pnix.qtranslate.models.TranslationHistory
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.SimpleDocumentListener
import com.pnix.qtranslate.utils.createButtonWithIcon
import com.pnix.qtranslate.utils.setPadding
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Frame
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel


class HistoryDialog(frame: Frame) : JDialog(frame, "History", true) {

  init {
    layout = BorderLayout()
    preferredSize = Dimension(395, (0.75 * frame.height).toInt())
    setPadding(4)


    val searchField = JTextField().apply {
      putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search")
      putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, FlatSearchIcon())
      putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
    }


    var rootNode = DefaultMutableTreeNode("Root")
    TranslationHistory.snapshots.forEach {
      val node = DefaultMutableTreeNode("${it.first}")
      it.second.forEach { snapShot ->
        val translators = QTranslateViewModel.translators
        val longestLength = translators.maxBy { t -> t.serviceName.length }.serviceName.length
        val translatorName = "%-${longestLength}s".format(translators[snapShot.selectedTranslatorIndex].serviceName)
        val subNode = DefaultMutableTreeNode("${translatorName}: ${snapShot.translatedText}")
        val subNodeCopy = DefaultMutableTreeNode("${translatorName}: ${snapShot.translatedText}")
        node.add(subNode)
      }
      rootNode.add(node)
    }

    val tree = JTree(rootNode)
    tree.cellRenderer = object : DefaultTreeCellRenderer() {
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
    tree.isRootVisible = false


    val buttons = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.X_AXIS)

      add(JButton("Clear").apply {
        addActionListener {
          TranslationHistory.clear()
          rootNode.removeAllChildren()
          (tree.model as DefaultTreeModel).reload()
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


    searchField.document.addDocumentListener(SimpleDocumentListener {

    })

    add(searchField, BorderLayout.NORTH)
    add(JScrollPane(tree))
    add(buttons, BorderLayout.SOUTH)


    pack()
    setLocationRelativeTo(frame)
    isVisible = true
  }

}