package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.WavyUnderlineHighlighter
import java.awt.Color
import javax.swing.UIManager
import javax.swing.text.Highlighter

/**
 * Owns the spell-check underline layer: the tags this pane added and the painter that draws them.
 *
 * Kept apart from the component so a correction update cannot reach any other highlight the pane
 * may carry.
 */
internal class CorrectionHighlighter(private val pane: AdvancedTextPane) {

    private val tags = ArrayList<Any>()

    // Color supplier so the painter always reads the current theme color — no stale color after theme switch.
    private val painter: Highlighter.HighlightPainter =
        WavyUnderlineHighlighter.WavyUnderlinePainter { UIManager.getColor("Actions.Red") ?: Color.RED }

    /**
     * Replaces the spell-check underline layer.
     *
     * Ranges are validated against the document first: a correction can describe text the user has
     * since shortened, and stale ranges are dropped rather than allowed to reach a paint path that
     * would throw.
     */
    fun update(corrections: List<Correction>) {
        tags.forEach { tag -> runCatching { pane.highlighter.removeHighlight(tag) } }
        tags.clear()

        val docLength = pane.document.length
        for (correction in corrections) {
            val start = correction.startIndex
            val end = correction.endIndex
            if (start < 0 || end > docLength || start >= end) continue
            runCatching { pane.highlighter.addHighlight(start, end, painter) }
                .onSuccess { tags.add(it) }
        }
    }
}
