package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoard
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoardState
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderPresentation
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderRole
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderStatus
import com.github.ahatem.qtranslate.ui.swing.main.output.TranslationProviderState
import com.github.ahatem.qtranslate.ui.swing.main.output.TranslationProviderView
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComparisonVisualCoverageTest {
    private val font = FontConfig("Dialog", 14)

    private fun provider(
        id: String,
        name: String,
        role: ProviderRole,
        presentation: ProviderPresentation,
        text: String = "$id result",
        status: ProviderStatus = ProviderStatus.SUCCESS,
        error: String? = null,
        definition: String = ""
    ) = TranslationProviderState(
        serviceId = id,
        serviceName = name,
        iconPath = null,
        role = role,
        presentation = presentation,
        status = status,
        text = text,
        errorMessage = error,
        loadingText = "Translating...",
        failureText = "Translation failed",
        copyLabel = "Copy",
        listenLabel = "Listen",
        stopLabel = "Stop",
        primaryLabel = "Primary",
        placeholderTitle = "Translate to compare results",
        placeholderSubtitle = "4 translation services ready",
        definition = definition,
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = {}
    )

    @Test
    fun `narrow dark comparison board shows primary and three arabic providers`() {
        withTheme(Color(35, 37, 42), Color(82, 86, 96), Color(120, 150, 220)) {
            val board = CompareBoard(iconManager = null)
            SwingUtilities.invokeAndWait {
                board.render(
                    CompareBoardState(
                        primary = provider(
                            "primary", "Google", ProviderRole.PRIMARY, ProviderPresentation.MAIN,
                            text = "تعمل الحركة الدودية على دفع الطعام عبر الجهاز الهضمي"
                        ),
                        secondaries = listOf(
                            provider("one", "Provider One", ProviderRole.SECONDARY, ProviderPresentation.MAIN, text = "نتيجة أولى"),
                            provider("two", "Provider Two", ProviderRole.SECONDARY, ProviderPresentation.MAIN, status = ProviderStatus.LOADING, text = ""),
                            provider("three", "Provider Three", ProviderRole.SECONDARY, ProviderPresentation.MAIN, status = ProviderStatus.FAILURE, text = "", error = "Provider timed out")
                        )
                    )
                )
                board.setSize(480, 700)
                board.doLayout()
            }
            SwingUtilities.invokeAndWait { }
            board.components.filterIsInstance<TranslationProviderView>().forEach { view ->
                assertTrue(view.x + view.width <= board.width, "provider clipped at the board edge")
            }
            assertScreenshot(board, Color(35, 37, 42), "comparison-dark-narrow")
        }
    }

    @Test
    fun `wide comparison board arranges secondaries in two columns`() {
        withTheme(Color(248, 248, 250), Color(190, 190, 198), Color(70, 100, 180)) {
            val board = CompareBoard(iconManager = null)
            SwingUtilities.invokeAndWait {
                board.render(
                    CompareBoardState(
                        primary = provider("primary", "Google", ProviderRole.PRIMARY, ProviderPresentation.MAIN, text = "First result"),
                        secondaries = listOf(
                            provider("one", "Provider One", ProviderRole.SECONDARY, ProviderPresentation.MAIN, text = "First result"),
                            provider("two", "Provider Two", ProviderRole.SECONDARY, ProviderPresentation.MAIN, text = "Second result"),
                            provider("three", "Provider Three", ProviderRole.SECONDARY, ProviderPresentation.MAIN, text = "Third result")
                        )
                    )
                )
                board.setSize(1000, 700)
                board.doLayout()
            }
            SwingUtilities.invokeAndWait { }
            val xs = board.components.filterIsInstance<TranslationProviderView>()
                .drop(1).map { it.x }.toSet()
            assertEquals(2, xs.size, "wide board must use two secondary columns")
            assertScreenshot(board, Color(248, 248, 250), "comparison-wide")
        }
    }

    @Test
    fun `empty comparison shows the integrated primary placeholder`() {
        withTheme(Color(248, 248, 250), Color(190, 190, 198), Color(70, 100, 180)) {
            val board = CompareBoard(iconManager = null)
            SwingUtilities.invokeAndWait {
                board.render(
                    CompareBoardState(
                        primary = provider(
                            "primary", "Google", ProviderRole.PRIMARY, ProviderPresentation.MAIN,
                            status = ProviderStatus.PLACEHOLDER, text = ""
                        ),
                        secondaries = emptyList()
                    )
                )
                board.setSize(760, 520)
                board.doLayout()
            }
            SwingUtilities.invokeAndWait { }
            val labels = descendants(board).filterIsInstance<JLabel>().mapNotNull { it.text }
            assertTrue(labels.contains("Translate to compare results"))
            assertScreenshot(board, Color(248, 248, 250), "comparison-empty")
        }
    }

    @Test
    fun `quick comparison popup uses one viewport in a single column`() {
        withTheme(Color(248, 248, 250), Color(190, 190, 198), Color(70, 100, 180)) {
            lateinit var view: QuickTranslateResultsView
            lateinit var board: CompareBoard
            SwingUtilities.invokeAndWait {
                board = CompareBoard(iconManager = null)
                board.render(
                    CompareBoardState(
                        primary = provider(
                            "primary", "Primary provider", ProviderRole.PRIMARY, ProviderPresentation.QUICK,
                            text = "Primary translation", definition = "a definition"
                        ),
                        secondaries = listOf(
                            provider("one", "Provider One", ProviderRole.SECONDARY, ProviderPresentation.QUICK, text = "First result"),
                            provider("two", "Provider Two", ProviderRole.SECONDARY, ProviderPresentation.QUICK, text = "Second result")
                        )
                    )
                )
                view = QuickTranslateResultsView(board)
                view.setSize(520, 620)
                view.doLayout()
                view.viewport.doLayout()
            }
            SwingUtilities.invokeAndWait { }
            assertEquals(
                1,
                board.components.filterIsInstance<TranslationProviderView>().map { it.x }.toSet().size
            )
            assertScreenshot(view, Color(248, 248, 250), "quick-comparison")
        }
    }

    private fun descendants(component: java.awt.Component): List<java.awt.Component> =
        listOf(component) + if (component is java.awt.Container) {
            component.components.flatMap(::descendants)
        } else emptyList()

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
            // On the EDT: measuring a text pane takes its document read lock,
            // which deadlocks against an async font rescan when done elsewhere.
            SwingUtilities.invokeAndWait { layoutRecursively(component) }
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
