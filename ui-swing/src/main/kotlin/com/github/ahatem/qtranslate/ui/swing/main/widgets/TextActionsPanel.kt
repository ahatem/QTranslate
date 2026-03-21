package com.github.ahatem.qtranslate.ui.swing.main.widgets

import com.formdev.flatlaf.extras.components.FlatButton
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel


/**
 * Represents a single user action that can be displayed as an icon button.
 *
 * @param id A unique identifier for the action (e.g., "copy", "listen").
 * @param iconPath The path to the icon resource for this action.
 * @param tooltip The localized tooltip text for the action.
 * @param isEnabled Whether the action button should be enabled.
 * @param isVisible Whether the action button should be visible.
 * @param onClick The callback to be executed when the action is performed.
 */
data class Action(
    val id: String,
    val iconPath: String,
    val tooltip: String,
    val isEnabled: Boolean,
    val isVisible: Boolean,
    val onClick: () -> Unit
)

data class TextActionsState(
    val actions: List<Action>
) : UiState

class TextActionsPanel(
    private val iconManager: IconManager
) : JPanel(), Renderable<TextActionsState> {

    private val actionButtons = mutableMapOf<String, FlatButton>()
    private var lastActions: List<Action> = emptyList()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    override fun render(state: TextActionsState) {
        val newActions = state.actions.filter { it.isVisible }
        val currentIds = actionButtons.keys
        val newIds = newActions.map { it.id }.toSet()

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

        actions.forEach { action ->
            val button = createActionButton(action)
            actionButtons[action.id] = button
            add(button)
            add(Box.createVerticalStrut(2))
        }

        if (componentCount > 0) remove(componentCount - 1)

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
            toolTipText = action.tooltip
            isEnabled = action.isEnabled
            addActionListener { action.onClick() }
        }
    }
}
