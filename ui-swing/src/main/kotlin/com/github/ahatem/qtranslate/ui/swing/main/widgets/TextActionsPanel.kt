package com.github.ahatem.qtranslate.ui.swing.main.widgets

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.components.FlatButton
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu


/**
 * Represents a single user action that can be displayed as an icon button.
 *
 * @param id A unique identifier for the action (e.g., "copy", "listen").
 * @param iconPath The path to the icon resource for this action.
 * @param tooltip The localized tooltip text for the action.
 * @param isEnabled Whether the action button should be enabled.
 * @param isVisible Whether the action button should be visible.
 * @param onClick The callback to be executed when the action is performed.
 * @param keepPriority How long the action stays on a strip that is too short for all of them. When
 * some do not fit, the lowest priority folds into the More menu first and equal priorities fold
 * from the end. See [ActionPriority].
 */
data class Action(
    val id: String,
    val iconPath: String,
    val tooltip: String,
    val isEnabled: Boolean,
    val isVisible: Boolean,
    val onClick: () -> Unit,
    val keepPriority: Int = ActionPriority.DEFAULT,
)

/**
 * The order in which the input strip gives up commands, lowest first. The strip keeps its visual
 * order whatever folds; this only says which commands go into the More menu when there is not room
 * for all of them: document, image, forward and back first, then Speak, and Copy last.
 */
object ActionPriority {
    const val DEFAULT = 0
    const val DOCUMENT = 1
    const val IMAGE = 2
    const val FORWARD = 3
    const val BACK = 4
    const val SPEAK = 5
    const val COPY = 6
}

/** The vertical gap between two buttons of the action strip. */
internal const val ACTION_GAP = 2

/**
 * The gap between a pane's text and its action strip, at 100%. A layout gap rather than a border, so
 * it follows the strip to whichever side the reading direction puts it on.
 */
internal const val STRIP_GAP = 4

/** The name of the More button, for the window's route checks. */
const val MORE_ACTIONS_NAME = "more_actions"

/**
 * @param actions The strip's commands, top to bottom.
 * @param moreLabel The tooltip of the More button that holds the commands that do not fit. A strip
 * that is always tall enough for its commands can leave it empty.
 */
data class TextActionsState(
    val actions: List<Action>,
    val moreLabel: String = "",
) : UiState

/**
 * A column of icon buttons that never clips one.
 *
 * When the column is too short for every button, the ones that do not fit are hidden and a trailing
 * More button stands in for them. Its popup lists each hidden command as a menu item with the same
 * text, icon, enabled state and action. The split is recomputed whenever the column is laid out, so
 * it follows a resize.
 */
class TextActionsPanel(
    private val iconManager: IconManager
) : JPanel(), Renderable<TextActionsState> {

    private val actionButtons = mutableMapOf<String, FlatButton>()
    private val priorities = mutableMapOf<String, Int>()
    private var lastActions: List<Action> = emptyList()

    /**
     * Part of the column only while some command does not fit; its popup holds those commands. It is
     * a plain icon button like the others, so it is never wider than they are. Its icon is loaded
     * the first time it is needed, so a strip that always fits never asks for it.
     */
    val moreButton: FlatButton = FlatButton().apply {
        name = MORE_ACTIONS_NAME
        addActionListener { showOverflowMenu() }
    }

    private val columnLayout = OverflowColumnLayout(moreButton, { priorities[it.name] ?: ActionPriority.DEFAULT }) {
        if (moreButton.icon == null) {
            moreButton.icon = (iconManager.getIcon(Icons.MORE, 16, 16) as FlatSVGIcon).applyForegroundColorFilter()
        }
    }

    init {
        layout = columnLayout
        isOpaque = false
    }

    /** The ids of the commands held in the More menu, in order. Empty while every command fits. */
    val overflowedActionIds: List<String>
        get() = columnLayout.overflowed.mapNotNull { it.name }.filter { it in actionButtons }

    /** The popup the More button opens: one item per command that does not fit, named by its id. */
    fun buildOverflowMenu(): JPopupMenu = JPopupMenu().apply {
        applyComponentOrientation(this@TextActionsPanel.componentOrientation)
        overflowedActionIds.forEach { id ->
            val button = actionButtons.getValue(id)
            add(JMenuItem(button.toolTipText.orEmpty(), button.icon).apply {
                name = id
                isEnabled = button.isEnabled
                addActionListener { button.doClick(0) }
            })
        }
    }

    private fun showOverflowMenu() {
        val menu = buildOverflowMenu()
        if (menu.componentCount == 0) return
        // The strip sits at the end of its pane, so the menu opens toward the text.
        val towardText = if (componentOrientation.isLeftToRight) -menu.preferredSize.width else moreButton.width
        menu.show(moreButton, towardText, 0)
    }

    override fun render(state: TextActionsState) {
        val newActions = state.actions.filter { it.isVisible }
        priorities.clear()
        newActions.forEach { priorities[it.id] = it.keepPriority }
        val currentIds = actionButtons.keys
        val newIds = newActions.map { it.id }.toSet()

        moreButton.toolTipText = state.moreLabel.ifBlank { null }

        val needsRebuild =
            currentIds != newIds || actionsChanged(newActions, lastActions)

        if (needsRebuild) rebuildButtons(newActions)
        else updateButtons(newActions)

        lastActions = newActions
    }

    private fun actionsChanged(newActions: List<Action>, oldActions: List<Action>): Boolean {
        if (newActions.size != oldActions.size) return true
        return newActions.zip(oldActions).any { (new, old) ->
            new.id != old.id ||
                    new.iconPath != old.iconPath ||
                    new.tooltip != old.tooltip ||
                    new.onClick !== old.onClick
        }
    }

    private fun rebuildButtons(actions: List<Action>) {
        removeAll()
        actionButtons.clear()
        columnLayout.reset()

        actions.forEach { action ->
            val button = createActionButton(action)
            actionButtons[action.id] = button
            add(button)
        }

        revalidate()
        repaint()
    }

    private fun updateButtons(actions: List<Action>) {
        actions.forEach { action ->
            actionButtons[action.id]?.apply {
                isEnabled = action.isEnabled
                toolTipText = action.tooltip
            }
        }
    }

    private fun createActionButton(action: Action): FlatButton {
        return createButtonWithIcon(iconManager, action.iconPath, 16).apply {
            // The action's id names the button, so the window's route checks can find a command
            // wherever it is mounted.
            name = action.id
            toolTipText = action.tooltip
            isEnabled = action.isEnabled
            addActionListener { action.onClick() }
        }
    }
}

/**
 * Lays the children of a column out top to bottom, each centred, [ACTION_GAP] apart. When they do
 * not all fit the available height, the lowest [priority] ones are hidden until the rest and the
 * [more] button fit, and the hidden ones are remembered in [overflowed]. The children keep their
 * order; only which of them show depends on priority.
 *
 * The column is as wide as its widest child. [more] counts only while it is a child, and it is an
 * icon button like the others, so it never widens the column.
 *
 * The preferred height is the whole column, so a tall parent shows everything. The minimum is one
 * button, so a short parent shrinks the column instead of clipping it.
 */
private class OverflowColumnLayout(
    private val more: Component,
    /** How long a child stays on a short column; higher stays longer. */
    private val priority: (Component) -> Int,
    /** Called before the More button is measured for the first time. */
    private val prepareMore: () -> Unit,
) : LayoutManager {

    /** The children this layout hid because they do not fit, in order. */
    var overflowed: List<Component> = emptyList()
        private set

    /** Forgets hidden children after the panel removed them. */
    fun reset() {
        overflowed = emptyList()
    }

    override fun addLayoutComponent(name: String?, comp: Component?) = Unit
    override fun removeLayoutComponent(comp: Component?) = Unit

    private fun items(parent: Container): List<Component> = parent.components.filter { it !== more }

    private fun widest(parent: Container, items: List<Component>): Int =
        (if (more.parent === parent) items + more else items).maxOfOrNull { it.preferredSize.width } ?: 0

    override fun preferredLayoutSize(parent: Container): Dimension {
        val items = items(parent)
        val insets = parent.insets
        val height = items.sumOf { it.preferredSize.height } + ACTION_GAP * (items.size - 1).coerceAtLeast(0)
        return Dimension(widest(parent, items) + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    override fun minimumLayoutSize(parent: Container): Dimension {
        val items = items(parent)
        val insets = parent.insets
        // One button tall: More is as tall as any of them.
        val oneButton = items.maxOfOrNull { it.preferredSize.height } ?: 0
        return Dimension(widest(parent, items) + insets.left + insets.right, oneButton + insets.top + insets.bottom)
    }

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val available = parent.height - insets.top - insets.bottom
        val items = items(parent)
        val heights = items.map { it.preferredSize.height }

        fun columnHeight(kept: List<Int>) = kept.sumOf { heights[it] } + ACTION_GAP * (kept.size - 1).coerceAtLeast(0)

        val kept = items.indices.toMutableList()
        if (columnHeight(kept) > available) {
            prepareMore()
            val moreHeight = more.preferredSize.height
            fun fits() = columnHeight(kept) + (if (kept.isEmpty()) 0 else ACTION_GAP) + moreHeight <= available
            // Fold the lowest priority first; of two equals, the later one.
            val foldOrder = items.indices.sortedWith(compareBy({ priority(items[it]) }, { -it }))
            for (index in foldOrder) {
                if (fits()) break
                kept.remove(index)
            }
        }

        var y = insets.top
        items.forEachIndexed { index, item ->
            val fits = index in kept
            if (item.isVisible != fits) item.isVisible = fits
            if (fits) {
                val size = item.preferredSize
                item.setBounds(centred(parent, size.width), y, size.width, size.height)
                y += size.height + ACTION_GAP
            }
        }
        overflowed = items.filterIndexed { index, _ -> index !in kept }
        val needsMore = overflowed.isNotEmpty()
        // The button is a child only while it is needed, so a column that fits holds nothing but
        // its own actions.
        if (needsMore && more.parent !== parent) parent.add(more)
        if (!needsMore && more.parent === parent) parent.remove(more)
        if (needsMore) {
            val size = more.preferredSize
            more.setBounds(centred(parent, size.width), y, size.width, size.height)
        }
    }

    private fun centred(parent: Container, width: Int): Int {
        val insets = parent.insets
        return insets.left + ((parent.width - insets.left - insets.right - width) / 2).coerceAtLeast(0)
    }
}
