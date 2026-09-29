package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.TranslatorMove
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.min

/** Theme-aware 14x14 icon for [role], recoloured to the muted foreground at paint time. */
internal fun serviceRoleIcon(role: ServiceRole): Icon? {
    val path = when (role) {
        ServiceRole.TRANSLATOR -> Icons.TRANSLATE
        ServiceRole.TTS -> Icons.SPEAK
        ServiceRole.OCR -> Icons.OCR
        ServiceRole.SPELL_CHECKER -> Icons.CHECK
        ServiceRole.DICTIONARY -> Icons.DICTIONARY
        ServiceRole.SUMMARIZER -> Icons.SUMMARIZE
        ServiceRole.REWRITER -> Icons.EDIT
        ServiceRole.IMAGE_SEARCH -> Icons.SEARCH
    }
    return runCatching {
        val icon = IconSet.load(path, 14, 14)
        icon.colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Label.disabledForeground") ?: Color.GRAY }
        icon as Icon
    }.getOrNull()
}

/**
 * The translator set as one ordered list: the Primary first, then the comparison translators.
 *
 * Purely a view. Every change is dispatched as a settings intent; the rows are rebuilt from the
 * model the next render supplies, so no membership is kept here.
 */
internal class TranslatorSetSection(
    private val localization: LocalizationManager,
    private val newAction: (iconPath: String, tooltip: String, onClick: () -> Unit) -> JButton,
    private val dispatch: (SettingsIntent) -> Unit
) : JPanel(GridBagLayout()) {

    private val rowsPanel = JPanel(GridBagLayout()).apply { isOpaque = false; name = "translator-rows" }

    private val addButton = JButton(
        localization.getString("settings_services.translator_add"),
        IconSet.load(Icons.ADD, 14, 14).applyForegroundColorFilter()
    ).apply {
        name = "translator-add"
        addActionListener { showAddPopup() }
    }

    private val hintLabel = JLabel().apply {
        name = "translator-set-hint"
        foreground = UIManager.getColor("Label.disabledForeground")
        font = font.deriveFont(font.size - 1f)
    }

    private var model = TranslatorSetModel(emptyList(), emptyList(), null)
    private var roleEnabled = true

    init {
        isOpaque = false
        val fullRow = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.LINE_START
        }
        add(rowsPanel, (fullRow.clone() as GridBagConstraints).apply { gridy = 0 })
        add(addButton, GridBagConstraints().apply {
            gridx = 0; gridy = 1
            anchor = GridBagConstraints.LINE_START
            insets = Insets(UIScale.scale(6), 0, 0, 0)
        })
        add(hintLabel, (fullRow.clone() as GridBagConstraints).apply {
            gridy = 2
            insets = Insets(UIScale.scale(6), UIScale.scale(2), 0, 0)
        })
    }

    val addAnchor: JComponent get() = addButton

    fun render(newModel: TranslatorSetModel, roleEnabled: Boolean) {
        model = newModel
        this.roleEnabled = roleEnabled
        rebuildRows()
        addButton.isEnabled = roleEnabled
        hintLabel.isVisible = newModel.hint != null
        hintLabel.text = when (newModel.hint) {
            TranslatorSetHint.COMPARISON_AVAILABLE -> localization.getString("settings_services.translator_set_ready")
            TranslatorSetHint.NEEDS_ANOTHER -> localization.getString("settings_services.translator_set_needs_more")
            TranslatorSetHint.ROLE_DISABLED -> localization.getString("settings_services.translator_set_disabled")
            null -> ""
        }
    }

    private fun rebuildRows() {
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val restoreName = focused?.takeIf { rowsPanel.isAncestorOf(it) }?.name

        rowsPanel.removeAll()
        if (model.rows.isEmpty()) {
            rowsPanel.add(JLabel(localization.getString("settings_services.translator_none")).apply {
                name = "translator-empty"
                foreground = UIManager.getColor("Label.disabledForeground")
            }, cell(x = 0, y = 0, width = COLUMNS, weightX = 1.0))
        } else {
            model.rows.forEachIndexed { y, row -> addRow(row, y) }
        }
        rowsPanel.applyComponentOrientation(componentOrientation)
        rowsPanel.revalidate()
        rowsPanel.repaint()

        // Rebuilding drops the focused action; without this a keyboard user stepping an entry
        // down the list would lose their place after every press.
        if (restoreName != null) {
            val target = findByName(rowsPanel, restoreName)?.takeIf { it.isEnabled } ?: addButton
            SwingUtilities.invokeLater { target.requestFocusInWindow() }
        }
    }

    private fun addRow(row: TranslatorRow, y: Int) {
        val dim = UIManager.getColor("Label.disabledForeground")
        val tooltip = row.id.takeIf { !row.available }

        rowsPanel.add(JLabel(serviceRoleIcon(ServiceRole.TRANSLATOR)), cell(0, y, insets = ICON_INSETS))
        rowsPanel.add(JLabel(row.name).apply {
            name = "translator-name:${row.id}"
            toolTipText = tooltip ?: row.name
            if (!row.available) foreground = dim
            minimumSize = Dimension(0, preferredSize.height)
        }, cell(1, y, weightX = 1.0))

        val status = statusText(row)
        if (row.canMakePrimary) {
            rowsPanel.add(JButton(localization.getString("settings_services.translator_make_primary")).apply {
                name = "translator-make-primary:${row.id}"
                putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS)
                font = font.deriveFont(font.size - 1f)
                isEnabled = roleEnabled
                addActionListener { dispatch(SettingsIntent.PromoteTranslatorToPrimary(row.id)) }
            }, cell(2, y))
        } else if (status != null) {
            rowsPanel.add(JLabel(status).apply {
                name = "translator-status:${row.id}"
                foreground = dim
                font = font.deriveFont(font.size - 1f)
            }, cell(2, y, insets = STATUS_INSETS))
        }

        if (!row.isPrimary) {
            rowsPanel.add(
                action(Icons.MOVE_UP, "settings_services.translator_move_up", "translator-move-up:${row.id}", row.canMoveUp) {
                    dispatch(SettingsIntent.MoveTranslatorInActivePreset(row.id, TranslatorMove.UP))
                }, cell(3, y)
            )
            rowsPanel.add(
                action(Icons.MOVE_DOWN, "settings_services.translator_move_down", "translator-move-down:${row.id}", row.canMoveDown) {
                    dispatch(SettingsIntent.MoveTranslatorInActivePreset(row.id, TranslatorMove.DOWN))
                }, cell(4, y)
            )
        }
        rowsPanel.add(
            action(Icons.DELETE, "settings_services.translator_remove", "translator-remove:${row.id}", true) {
                dispatch(SettingsIntent.RemoveTranslatorFromActivePreset(row.id))
            }, cell(5, y)
        )
    }

    private fun statusText(row: TranslatorRow): String? {
        val primary = localization.getString("settings_services.translator_primary")
        val unavailable = localization.getString("settings_services.unavailable_suffix")
        return when {
            row.isPrimary && !row.available -> "$primary · $unavailable"
            row.isPrimary -> primary
            !row.available -> unavailable
            else -> null
        }
    }

    private fun action(iconPath: String, tooltipKey: String, name: String, enabled: Boolean, onClick: () -> Unit) =
        newAction(iconPath, localization.getString(tooltipKey), onClick).also {
            it.name = name
            it.isEnabled = enabled && roleEnabled
        }

    private fun cell(
        x: Int,
        y: Int,
        width: Int = 1,
        weightX: Double = 0.0,
        insets: Insets = NO_INSETS
    ) = GridBagConstraints().apply {
        gridx = x
        gridy = y
        gridwidth = width
        weightx = weightX
        fill = if (weightX > 0) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
        anchor = GridBagConstraints.LINE_START
        this.insets = insets
    }

    private fun findByName(root: Component, name: String): Component? {
        if (root.name == name) return root
        return (root as? java.awt.Container)?.components?.firstNotNullOfOrNull { findByName(it, name) }
    }

    // ── Add popup ─────────────────────────────────────────────────────────────

    private fun showAddPopup() {
        if (!addButton.isEnabled) return

        val menu = JPopupMenu().apply { name = "translator-add-popup" }
        val content = JPanel(BorderLayout(0, UIScale.scale(5))).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)
        }
        val addable = model.addable
        if (addable.isEmpty()) {
            content.add(JLabel(localization.getString("settings_services.no_comparison_services")).apply {
                name = "translator-add-empty"
                foreground = UIManager.getColor("Label.disabledForeground")
            }, BorderLayout.CENTER)
        } else {
            val usableHeight = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height
            content.preferredSize = Dimension(
                UIScale.scale(260),
                min(UIScale.scale(220), (usableHeight - UIScale.scale(20)).coerceAtLeast(UIScale.scale(120)))
            )

            val listModel = DefaultListModel<ServiceOption>()
            val list = JList(listModel).apply {
                name = "translator-add-list"
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                cellRenderer = DisplayValueRenderer<ServiceOption>(text = { it?.name.orEmpty() })
            }
            val search = JTextField().apply {
                name = "translator-add-search"
                toolTipText = localization.getString("common.search")
            }

            fun choose(option: ServiceOption?) {
                if (option == null) return
                menu.isVisible = false
                dispatch(SettingsIntent.AddTranslatorToActivePreset(option.id))
            }

            fun refill() {
                val query = search.text.trim().lowercase()
                listModel.clear()
                addable.filter { it.name.lowercase().contains(query) || it.id.lowercase().contains(query) }
                    .forEach(listModel::addElement)
                if (!listModel.isEmpty) list.selectedIndex = 0
            }
            search.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = refill()
                override fun removeUpdate(e: DocumentEvent) = refill()
                override fun changedUpdate(e: DocumentEvent) = refill()
            })
            search.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ENTER -> choose(list.selectedValue)
                        KeyEvent.VK_DOWN -> {
                            list.selectedIndex = (list.selectedIndex + 1).coerceAtMost(listModel.size - 1)
                            e.consume()
                        }
                        KeyEvent.VK_UP -> {
                            list.selectedIndex = (list.selectedIndex - 1).coerceAtLeast(0)
                            e.consume()
                        }
                    }
                }
            })
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    val index = list.locationToIndex(e.point)
                    if (index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) {
                        choose(listModel.getElementAt(index))
                    }
                }
            })
            list.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) choose(list.selectedValue)
                }
            })

            refill()
            content.add(search, BorderLayout.NORTH)
            content.add(JScrollPane(list).apply {
                border = null
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
            SwingUtilities.invokeLater { search.requestFocusInWindow() }
        }

        menu.add(content)
        menu.applyComponentOrientation(componentOrientation)
        menu.pack()
        menu.show(
            addButton,
            popupStartX(addButton.width, menu.preferredSize.width, componentOrientation.isLeftToRight),
            addButton.height
        )
    }

    private companion object {
        const val COLUMNS = 6
        val NO_INSETS = Insets(0, 0, 0, 0)
        val ICON_INSETS get() = Insets(UIScale.scale(2), 0, UIScale.scale(2), UIScale.scale(6))
        val STATUS_INSETS get() = Insets(0, UIScale.scale(8), 0, UIScale.scale(8))
    }
}
