package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonTranslationResult
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import java.awt.Component
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComparisonResultsPanelTest {
    private val font = FontConfig("Dialog", 14)

    @Test
    fun `empty results hide the secondary section`() {
        val panel = ComparisonResultsPanel()
        panel.render(state(emptyList()))
        assertFalse(panel.isVisible)
    }

    @Test
    fun `mixed provider states remain visible in core order and copy only succeeds`() {
        var copied = ""
        val panel = ComparisonResultsPanel()
        SwingUtilities.invokeAndWait {
            panel.render(
                state(
                    listOf(
                        ComparisonTranslationResult("first", "First", ComparisonStatus.LOADING),
                        ComparisonTranslationResult("second", "Second", ComparisonStatus.SUCCESS, "second text"),
                        ComparisonTranslationResult("third", "Third", ComparisonStatus.FAILURE, errorMessage = "Timed out")
                    ),
                    onCopy = { copied = it }
                )
            )
        }
        SwingUtilities.invokeAndWait { }

        assertTrue(panel.isVisible)
        val labels = descendants(panel).filterIsInstance<JLabel>().mapNotNull { it.text }
        assertTrue(labels.indexOf("First") < labels.indexOf("Second"))
        assertTrue(labels.indexOf("Second") < labels.indexOf("Third"))
        assertTrue(labels.contains("Translating..."))
        assertTrue(descendants(panel).filterIsInstance<javax.swing.JTextArea>().any { it.text == "Timed out" })
        assertEquals("second text", descendants(panel).filterIsInstance<AdvancedTextPane>().single().text)

        SwingUtilities.invokeAndWait {
            descendants(panel).filterIsInstance<JButton>().single { it.text == "Copy" }.doClick()
        }
        assertEquals("second text", copied)
    }

    @Test
    fun `unchanged sibling keeps selected text when another provider completes`() {
        val panel = ComparisonResultsPanel()
        val first = ComparisonTranslationResult("deepl", "DeepL", ComparisonStatus.SUCCESS, "deep result")
        val loading = ComparisonTranslationResult("bing", "Bing", ComparisonStatus.LOADING)
        lateinit var deepPane: AdvancedTextPane
        SwingUtilities.invokeAndWait {
            panel.render(state(listOf(first, loading)))
            deepPane = descendants(panel).filterIsInstance<AdvancedTextPane>().single()
            deepPane.select(0, 4)
            panel.render(state(listOf(first, ComparisonTranslationResult("bing", "Bing", ComparisonStatus.SUCCESS, "bing result"))))
        }

        val panes = descendants(panel).filterIsInstance<AdvancedTextPane>()
        assertSame(deepPane, panes.first())
        assertEquals(0, deepPane.selectionStart)
        assertEquals(4, deepPane.selectionEnd)
        assertEquals("bing result", panes.last().text)
    }

    private fun state(
        results: List<ComparisonTranslationResult>,
        onCopy: (String) -> Unit = {}
    ) = ComparisonResultsState(
        results = results,
        title = "Compare translations",
        loadingText = "Translating...",
        unavailableText = "Unavailable service",
        failureText = "Translation failed",
        copyLabel = "Copy",
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = onCopy
    )

    private fun descendants(component: Component): List<Component> =
        listOf(component) + if (component is java.awt.Container) {
            component.components.flatMap(::descendants)
        } else emptyList()
}
