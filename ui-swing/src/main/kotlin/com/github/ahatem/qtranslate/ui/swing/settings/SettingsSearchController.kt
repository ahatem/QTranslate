package com.github.ahatem.qtranslate.ui.swing.settings

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.icons.FlatSearchIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The settings search field and its results, in the sidebar's card space.
 *
 * Owns the query → results pipeline: typing filters the [SettingsSearchIndex], hits are listed
 * with enough path to tell identically-labelled rows apart, and choosing one is reported to
 * [onHitActivated]. With no query the section tree owns the space again, and a query that
 * matches nothing says so — naming the query, which is the difference between "nothing matched
 * what you typed" and a sidebar that looks broken.
 *
 * The section tree itself belongs to the navigation side; it is passed in to share the card
 * space, and the same row insets apply to both lists.
 */
internal class SettingsSearchController(
    private val searchIndex: SettingsSearchIndex,
    tree: JTree,
    private val localizationManager: LocalizationManager,
    private val onHitActivated: (SearchHit) -> Unit,
) {
    /** Which of the three sidebar cards is showing — the search mode. */
    internal var visibleCard: String = NAV_TREE
        private set

    val searchField = JTextField()
    val resultsList = JList<SearchHit>()
    val noResultsLabel = JLabel().apply {
        foreground = UIManager.getColor("Label.disabledForeground")
        verticalAlignment = SwingConstants.TOP
    }

    private val navCards = JPanel(CardLayout())

    init {
        // Breathing room under the search box's rule. Without it the first row sits against the
        // line and reads as attached to it rather than as the first of a list.
        // An EmptyBorder rather than null: the look and feel reinstalls its own default over a
        // null border on every theme change, which is what clearBorder() exists to avoid.
        val listInset = BorderFactory.createEmptyBorder(UIScale.scale(6), 0, UIScale.scale(6), 0)
        tree.border = listInset
        resultsList.border = listInset

        val treeScroll = JScrollPane(tree).apply {
            clearBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        val resultsScroll = JScrollPane(resultsList).apply {
            clearBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        navCards.add(treeScroll, NAV_TREE)
        navCards.add(resultsScroll, NAV_RESULTS)
        navCards.add(
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(16, 12, 12, 12)
                add(noResultsLabel, BorderLayout.NORTH)
            },
            NAV_EMPTY
        )

        resultsList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = ListCellRenderer<SearchHit> { list, hit, _, selected, _ ->
                JPanel(BorderLayout()).apply {
                    border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
                    isOpaque = true
                    background = if (selected) {
                        UIManager.getColor("List.selectionBackground")
                    } else {
                        list.background
                    }
                    add(JLabel(hit.entry.label).apply {
                        foreground = if (selected) {
                            UIManager.getColor("List.selectionForeground")
                        } else {
                            UIManager.getColor("Label.foreground")
                        }
                    }, BorderLayout.NORTH)
                    // The path is what tells two identically-labelled rows apart, so it is always
                    // shown rather than only on ambiguity.
                    add(JLabel("${hit.pageLabel} $PATH_SEPARATOR ${hit.entry.section}").apply {
                        font = font.deriveFont(font.size - 2f)
                        foreground = if (selected) {
                            UIManager.getColor("List.selectionForeground")
                        } else {
                            UIManager.getColor("Label.disabledForeground")
                        }
                    }, BorderLayout.SOUTH)
                }
            }
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) selectedValue?.let { onHitActivated(it) }
            }

            // Selection listeners only fire on a change, so clicking the row you are already on did
            // nothing — and that is exactly when you want the marker shown again, having lost track of
            // it. Only the already-selected row is handled here; anything else is a real selection
            // change and the listener above has it.
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index < 0 || index != selectedIndex) return
                    // locationToIndex answers with the nearest row even for a click in the empty space
                    // below the last one, so the click has to be inside the row it named.
                    val bounds = getCellBounds(index, index) ?: return
                    if (bounds.contains(e.point)) onHitActivated(model.getElementAt(index))
                }
            })
        }

        searchField.apply {
            putClientProperty(
                FlatClientProperties.PLACEHOLDER_TEXT,
                localizationManager.getString("settings_dialog.search_placeholder")
            )
            putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
            putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, FlatSearchIcon())
            // Border applied by the dialog's updateBorders() so the rule follows the theme.

            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = onQueryChanged()
                override fun removeUpdate(e: DocumentEvent) = onQueryChanged()
                override fun changedUpdate(e: DocumentEvent) = onQueryChanged()
            })

            // Down from the field moves into the results without reaching for the mouse, which is
            // the whole point of typing rather than clicking.
            registerKeyboardAction(
                { enterResults() },
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
                JComponent.WHEN_FOCUSED
            )
        }
    }

    /** The whole sidebar: search field on top, tree/results/empty cards below. */
    val component: JPanel = JPanel(BorderLayout()).apply {
        minimumSize = Dimension(UIScale.scale(200), 0)
        add(searchField, BorderLayout.NORTH)
        add(navCards, BorderLayout.CENTER)
    }

    /**
     * Reacts to a query change: no query shows the tree again, a matching query lists its hits,
     * and a query with no hits shows the empty card.
     */
    fun onQueryChanged() {
        val query = searchField.text.trim()
        if (query.isEmpty()) {
            // Drop the previous hits too: the tree is showing, and a later no-hit query must not
            // leave a stale row for the Down key to select.
            resultsList.setListData(emptyArray())
            showCard(NAV_TREE)
            return
        }

        val hits = searchIndex.search(query)
        if (hits.isEmpty()) {
            // Naming the query back is the difference between "nothing matched what you typed"
            // and a sidebar that looks broken.
            resultsList.setListData(emptyArray())
            noResultsLabel.text = "<html><body style='width:${UIScale.scale(140)}px'>" +
                localizationManager.getString("settings_dialog.search_no_results", query) +
                "</body></html>"
            showCard(NAV_EMPTY)
            return
        }
        resultsList.setListData(hits.toTypedArray())
        showCard(NAV_RESULTS)
    }

    /**
     * Moves into the results from the field: the first row becomes selected, which activates it.
     * With no results there is nowhere to go, and the field keeps the focus.
     */
    private fun enterResults() {
        if (resultsList.model.size > 0) {
            resultsList.requestFocusInWindow()
            resultsList.selectedIndex = 0
        }
    }

    private fun showCard(id: String) {
        (navCards.layout as CardLayout).show(navCards, id)
        visibleCard = id
    }

    internal companion object {
        const val NAV_TREE = "tree"
        const val NAV_RESULTS = "results"
        const val NAV_EMPTY = "empty"

        /** Separates the page from the section in a result's path line. */
        const val PATH_SEPARATOR = "›"
    }
}
