package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonTranslationResult
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.output.ComparisonResultsPanel
import com.github.ahatem.qtranslate.ui.swing.main.output.ComparisonResultsState
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DefinitionStrip
import java.awt.Component
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuickTranslateResultsViewTest {
    @Test
    fun `primary definition and comparisons share the popup result viewport`() {
        val primary = AdvancedTextPane({}, {}, {})
        val definition = DefinitionStrip()
        val comparisons = ComparisonResultsPanel()
        comparisons.render(
            ComparisonResultsState(
                results = listOf(ComparisonTranslationResult("secondary", "Secondary", ComparisonStatus.SUCCESS, "secondary")),
                loadingText = "Translating...",
                unavailableText = "Unavailable service",
                failureText = "Translation failed",
                copyLabel = "Copy",
                fontConfig = FontConfig("Dialog", 14),
                fallbackFontConfig = FontConfig("Dialog", 14),
                onCopy = {}
            )
        )
        val view = QuickTranslateResultsView(primary, definition, comparisons)

        assertEquals(1, descendants(view).filterIsInstance<JScrollPane>().size)
        assertSame(view.viewport, descendants(view).filterIsInstance<JScrollPane>().single())
        assertTrue(isDescendant(view.viewport.viewport.view, primary))
        assertTrue(isDescendant(view.viewport.viewport.view, definition))
        assertTrue(isDescendant(view.viewport.viewport.view, comparisons))
    }

    private fun isDescendant(root: Component?, target: Component): Boolean =
        root === target || (root is java.awt.Container && root.components.any { isDescendant(it, target) })

    private fun descendants(component: Component): List<Component> =
        listOf(component) + if (component is java.awt.Container) component.components.flatMap(::descendants) else emptyList()
}
