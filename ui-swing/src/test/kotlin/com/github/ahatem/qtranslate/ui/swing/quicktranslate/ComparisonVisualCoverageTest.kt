package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonTranslationResult
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.output.ComparisonResultsPanel
import com.github.ahatem.qtranslate.ui.swing.main.output.ComparisonResultsState
import com.github.ahatem.qtranslate.ui.swing.main.output.ResultPresentationMode
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DefinitionStrip
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertTrue

class ComparisonVisualCoverageTest {
    private val font = FontConfig("Dialog", 14)

    @Test
    fun `comparison dark stack covers success loading failure mix`() {
        withTheme(Color(35, 37, 42), Color(82, 86, 96), Color(120, 150, 220)) {
            val panel = ComparisonResultsPanel(ResultPresentationMode.MAIN_WORKSPACE)
            SwingUtilities.invokeAndWait {
                panel.render(
                    state(
                        listOf(
                            ComparisonTranslationResult("one", "Provider One", ComparisonStatus.SUCCESS, "First result"),
                            ComparisonTranslationResult("two", "Provider Two", ComparisonStatus.LOADING),
                            ComparisonTranslationResult("three", "Provider Three", ComparisonStatus.FAILURE, errorMessage = "Provider timed out")
                        )
                    )
                )
                panel.setSize(760, 520)
                panel.doLayout()
            }
            assertScreenshot(panel, Color(35, 37, 42), "comparison-dark-mix")
        }
    }

    @Test
    fun `comparison light empty state has intentional configure prompt`() {
        withTheme(Color(248, 248, 250), Color(190, 190, 198), Color(70, 100, 180)) {
            val panel = ComparisonResultsPanel(ResultPresentationMode.MAIN_WORKSPACE)
            SwingUtilities.invokeAndWait {
                panel.render(
                    state(emptyList()).copy(
                        showEmptyState = true,
                        emptyText = "Translations will appear here",
                        configureLabel = "Configure"
                    )
                )
                panel.setSize(760, 520)
                panel.doLayout()
            }
            assertScreenshot(panel, Color(248, 248, 250), "comparison-light-empty")
        }
    }

    @Test
    fun `quick comparison popup uses one viewport for primary and two comparisons`() {
        withTheme(Color(248, 248, 250), Color(190, 190, 198), Color(70, 100, 180)) {
            val primary = AdvancedTextPane({}, {}, {}).apply { render("Primary translation", emptyList(), false) }
            val definition = DefinitionStrip()
            val comparisons = ComparisonResultsPanel(ResultPresentationMode.QUICK_POPUP)
            comparisons.render(
                state(
                    listOf(
                        ComparisonTranslationResult("one", "Provider One", ComparisonStatus.SUCCESS, "First result"),
                        ComparisonTranslationResult("two", "Provider Two", ComparisonStatus.SUCCESS, "Second result")
                    )
                )
            )
            val view = QuickTranslateResultsView(primary, definition, comparisons)
            view.setPrimaryLabel(null, "Primary provider", "PRIMARY")
            SwingUtilities.invokeAndWait {
                view.setSize(520, 620)
                view.doLayout()
                view.viewport.doLayout()
                view.viewport.viewport.view.doLayout()
            }
            assertScreenshot(view, Color(248, 248, 250), "quick-comparison")
        }
    }

    private fun state(results: List<ComparisonTranslationResult>) = ComparisonResultsState(
        results = results,
        loadingText = "Translating...",
        unavailableText = "Unavailable service",
        failureText = "Translation failed",
        copyLabel = "Copy",
        collapseLabel = "Collapse result",
        expandLabel = "Expand result",
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = {},
        providerInfos = results.associate { result ->
            result.serviceId to ServiceInfo(result.serviceId, result.serviceName ?: result.serviceId, null, ServiceRole.TRANSLATOR)
        }
    )

    private fun withTheme(background: Color, border: Color, focus: Color, block: () -> Unit) {
        val old = mapOf(
            "Panel.background" to UIManager.get("Panel.background"),
            "Component.borderColor" to UIManager.get("Component.borderColor"),
            "Component.focusedBorderColor" to UIManager.get("Component.focusedBorderColor")
        )
        try {
            UIManager.put("Panel.background", background)
            UIManager.put("Component.borderColor", border)
            UIManager.put("Component.focusedBorderColor", focus)
            block()
        } finally {
            old.forEach { (key, value) -> UIManager.put(key, value) }
        }
    }

    private fun assertScreenshot(component: java.awt.Component, background: Color, name: String) {
        val image = BufferedImage(component.width.coerceAtLeast(1), component.height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = background
            graphics.fillRect(0, 0, image.width, image.height)
            component.isVisible = true
            layoutRecursively(component)
            SwingUtilities.invokeAndWait { component.printAll(graphics) }
        } finally {
            graphics.dispose()
        }
        val distinct = (0 until image.width).sumOf { x ->
            (0 until image.height).count { y -> image.getRGB(x, y) != background.rgb }
        }
        assertTrue(distinct > 100, "$name did not paint a meaningful result stack")
        val output = File.createTempFile(name, ".png")
        ImageIO.write(image, "png", output)
        assertTrue(output.isFile && output.length() > 0, "$name screenshot was not written")
        output.delete()
    }

    private fun layoutRecursively(component: java.awt.Component) {
        component.doLayout()
        if (component is java.awt.Container) component.components.forEach(::layoutRecursively)
    }
}
