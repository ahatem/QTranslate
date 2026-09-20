package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.WavyUnderlineHighlighter
import java.awt.Color
import javax.swing.UIManager
import javax.swing.text.Highlighter

// Only the tags added here are removed on update, so highlights from other layers survive.
internal class CorrectionHighlighter(private val pane: AdvancedTextPane) {

    private val tags = ArrayList<Any>()

    // Resolve the color at paint time so theme changes are picked up.
    private val painter: Highlighter.HighlightPainter =
        WavyUnderlineHighlighter.WavyUnderlinePainter { UIManager.getColor("Actions.Red") ?: Color.RED }

    /**
     * Ranges are validated against the document first: a correction can describe text the user has
     * since shortened, and a stale range would throw on the paint path.
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
