package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.api.rewriter.RewriteStyle
import com.github.ahatem.qtranslate.api.summarizer.SummaryLength
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
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
    val summaryLength: SummaryLength = SummaryLength.MEDIUM,
    val rewriteStyle: RewriteStyle = RewriteStyle.FORMAL,

    val labelBackward: String = "",
    val labelSummary: String = "",
    val labelRewrite: String = "",

    val labelConfigure: String = "",
    val summaryLengthLabels: List<String> = emptyList(),
    val rewriteStyleLabels: List<String> = emptyList(),

    val onTypeChanged: (ExtraOutputType) -> Unit = {},
    val onSummaryLengthChanged: (SummaryLength) -> Unit = {},
    val onRewriteStyleChanged: (RewriteStyle) -> Unit = {}
) : UiState