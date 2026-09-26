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
            descendants(panel).filterIsInstance<JButton>().first { it.actionCommand == "second text" }.doClick()
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

    @Test
    fun `collapsed card stays collapsed when a sibling changes`() {
        val panel = ComparisonResultsPanel()
        val first = ComparisonTranslationResult("first", "First", ComparisonStatus.SUCCESS, "first result")
        val second = ComparisonTranslationResult("second", "Second", ComparisonStatus.LOADING)
        lateinit var firstCard: ComparisonResultCard
        SwingUtilities.invokeAndWait {
            panel.render(state(listOf(first, second)))
            firstCard = descendants(panel).filterIsInstance<ComparisonResultCard>().first()
            firstCard.collapseForTest()
            panel.render(state(listOf(first, ComparisonTranslationResult("second", "Second", ComparisonStatus.SUCCESS, "second result"))))
        }
        assertFalse(firstCard.textPaneForTest().isVisible)
        assertSame(firstCard, descendants(panel).filterIsInstance<ComparisonResultCard>().first())
    }

    @Test
    fun `empty state is deliberate and does not render a comparison heading`() {
        val panel = ComparisonResultsPanel()
        SwingUtilities.invokeAndWait {
            panel.render(
                state(emptyList()).copy(
                    showEmptyState = true,
                    emptyText = "Translations will appear here",
                    configureLabel = "Configure"
                )
            )
        }
        assertTrue(panel.isVisible)
        assertTrue(descendants(panel).filterIsInstance<JLabel>().any { it.text == "Translations will appear here" })
        assertTrue(descendants(panel).filterIsInstance<JLabel>().none { it.text == "Compare translations" })
    }

    private fun state(
        results: List<ComparisonTranslationResult>,
        onCopy: (String) -> Unit = {}
    ) = ComparisonResultsState(
        results = results,
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
