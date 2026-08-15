package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.ui.swing.shared.util.ServiceOptionChoice
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState

/**
 * Shown above the output when no translator is configured, so a new user is told what to do
 * instead of facing a window that silently does nothing.
 *
 * @property message     What is wrong, in one line.
 * @property actionLabel Label for the button that opens Services settings.
 * @property onAction    Opens the settings dialog.
 */
data class NoServiceState(
    val message: String,
    val actionLabel: String,
    val onAction: () -> Unit
)

data class OutputTextState(
    /** Short definition for a single-word result; empty for anything longer. */
    val definition: String = "",
    val text: String,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val isLoading: Boolean,
    val actionsState: TextActionsState,
    val isEditable: Boolean = false,
    /** Non-null only when there is no translator to translate with. */
    val noService: NoServiceState? = null
) : UiState

data class ExtraOutputState(
    val text: String,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val isLoading: Boolean,
    val isVisible: Boolean,
    val actionsState: TextActionsState,
    val isEditable: Boolean = false,

    val activeType: ExtraOutputType = ExtraOutputType.None,

    val labelBackward: String = "",
    val labelSummary: String = "",
    val labelRewrite: String = "",
    val labelConfigure: String = "",

    /**
     * The choices offered by whichever service backs [activeType], already resolved for display,
     * with [selectedOptionId] among them.
     *
     * One list rather than a pair of enum-specific ones: the panel shows a menu and reports an id
     * back, and does not need to know whether it is offering lengths, styles, or something a
     * plugin invented. Empty when the active type has no options — backward translation, or a
     * service that declares none — and the configure button is hidden.
     */
    val optionChoices: List<ServiceOptionChoice> = emptyList(),
    val selectedOptionId: String? = null,

    val onTypeChanged: (ExtraOutputType) -> Unit = {},
    val onOptionSelected: (String) -> Unit = {}
) : UiState