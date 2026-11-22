package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.shared.arch.UiState

/**
 * What the language pair chip draws.
 *
 * [bar] is the very state the language bar renders, not a copy of it: the chip stands in for the
 * bar when it is hidden, so both are fed from one value and cannot disagree about the pair,
 * the detected language, or whether a translation is running.
 */
data class LanguagePairChipState(
    val bar: LanguageSelectionBarState,
    val strings: LanguagePairChipStrings = LanguagePairChipStrings.DEFAULT
) : UiState

/**
 * The words the chip and its popup draw. Carried with each render so the interface language can
 * change while the window is open.
 */
data class LanguagePairChipStrings(
    /** `%1$s` is the pair in full, for example "English → Arabic". */
    val tooltipFormat: String,
    val sourceLabel: String,
    val targetLabel: String,
    /** The name of the Auto source, for a pair whose source has not been detected yet. */
    val autoDetectLabel: String
) {
    companion object {
        val DEFAULT = LanguagePairChipStrings(
            tooltipFormat = "Languages: %1\$s",
            sourceLabel = "From",
            targetLabel = "To",
            autoDetectLabel = "Auto-Detect"
        )
    }
}

/** The chip's words in the interface language. */
fun LocalizationManager.languagePairChipStrings() = LanguagePairChipStrings(
    tooltipFormat = getString("main_window_language_bar.pair_chip_tooltip"),
    sourceLabel = getString("main_window_language_bar.pair_source_label"),
    targetLabel = getString("main_window_language_bar.pair_target_label"),
    autoDetectLabel = getString("common.auto_detect")
)

private const val ARROW_RIGHT = "→"
private const val ARROW_LEFT = "←"

/**
 * "Source to Target" in the order it is drawn.
 *
 * Button text is laid out on a left-to-right base whatever the interface direction, so a
 * right-to-left reader's pair has to be written target first with the arrow reversed: drawn that
 * way the source is at the right, where their eye starts, and the arrow points from it to the
 * target. Writing the left-to-right order and only mirroring the button would put the source on
 * the wrong side and the arrow pointing the wrong way.
 */
internal fun formatLanguagePair(source: String, target: String, rightToLeft: Boolean): String =
    if (rightToLeft) "$target $ARROW_LEFT $source" else "$source $ARROW_RIGHT $target"
