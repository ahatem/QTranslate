package com.github.ahatem.qtranslate.ui.swing.history

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class HistoryDialog(owner: Frame) : JDialog(owner, false) {

    private var state: HistoryDialogState? = null

    private val tableModel = HistoryTableModel()
    private val table = JTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = 28
        showHorizontalLines = true
        showVerticalLines = false
        tableHeader.reorderingAllowed = false
        fillsViewportHeight = true
        intercellSpacing = java.awt.Dimension(12, 0)
    }

    private val scrollPane = JScrollPane(table)
    private val emptyLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
    }

    private val cardPanel = JPanel(java.awt.CardLayout())

    private val clearAllButton = JButton()
    private val closeButton = JButton()

    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        isResizable = true

        val centerRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.CENTER
        }

        cardPanel.add(emptyLabel, "empty")
        cardPanel.add(scrollPane, "table")

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(clearAllButton)
            add(Box.createHorizontalGlue())
            add(closeButton)
        }
        closeButton.putClientProperty("JButton.buttonType", "default")

        contentPane = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            add(cardPanel, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0) restoreRow(row)
                }
            }
        })

        clearAllButton.addActionListener {
            state?.onClearAll?.invoke()
        }
        closeButton.addActionListener {
            isVisible = false
        }

        table.model.addTableModelListener {
            applyColumnRenderers(centerRenderer)
        }
    }

    fun render(newState: HistoryDialogState) {
        state = newState

        title = newState.title
        tableModel.setColumns(
            newState.columnDate,
            newState.columnSource,
            newState.columnTranslation,
            newState.columnLanguages,
            newState.columnService
        )
        tableModel.setEntries(newState.entries)

        emptyLabel.text = newState.emptyMessage
        clearAllButton.text = newState.clearAllLabel
        closeButton.text = newState.closeLabel
        table.toolTipText = newState.restoreTooltip

        val card = if (newState.entries.isEmpty()) "empty" else "table"
        (cardPanel.layout as java.awt.CardLayout).show(cardPanel, card)

        if (!isVisible) {
            pack()
            minimumSize = Dimension(640, 400)
            preferredSize = Dimension(760, 480)
            pack()
            setLocationRelativeTo(owner)
        }
    }

    private fun restoreRow(row: Int) {
        val entry = state?.entries?.getOrNull(row) ?: return
        state?.onEntrySelected?.invoke(entry.snapshot)
    }

    private fun applyColumnRenderers(centerRenderer: DefaultTableCellRenderer) {
        if (table.columnCount < 5) return
        table.columnModel.getColumn(0).apply { preferredWidth = 110; maxWidth = 140; cellRenderer = centerRenderer }
        table.columnModel.getColumn(1).preferredWidth = 220
        table.columnModel.getColumn(2).preferredWidth = 220
        table.columnModel.getColumn(3).apply { preferredWidth = 90; maxWidth = 120; cellRenderer = centerRenderer }
        table.columnModel.getColumn(4).apply { preferredWidth = 100; maxWidth = 140; cellRenderer = centerRenderer }
    }

    private class HistoryTableModel : AbstractTableModel() {

        private var columns = arrayOf("", "", "", "", "")
        private var entries: List<HistoryEntryState> = emptyList()

        fun setColumns(date: String, source: String, translation: String, languages: String, service: String) {
            columns = arrayOf(date, source, translation, languages, service)
            fireTableStructureChanged()
        }

        fun setEntries(newEntries: List<HistoryEntryState>) {
            entries = newEntries
            fireTableDataChanged()
        }

        fun getEntry(row: Int): HistoryEntryState = entries[row]

        override fun getRowCount() = entries.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(col: Int) = columns[col]
        override fun isCellEditable(row: Int, col: Int) = false

        override fun getValueAt(row: Int, col: Int): Any {
            val e = entries[row]
            return when (col) {
                0 -> e.date
                1 -> e.sourceText
                2 -> e.translatedText
                3 -> e.languages
                4 -> e.service
                else -> ""
            }
        }
    }
}
