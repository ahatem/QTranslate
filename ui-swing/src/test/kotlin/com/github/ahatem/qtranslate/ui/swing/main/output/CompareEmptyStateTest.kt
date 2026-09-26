package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.effectiveTranslatorCount
import java.awt.CardLayout
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import java.io.File
import java.nio.file.Files
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Comparison board's state before anything has been translated, and the Extra Output placeholder. */
class CompareEmptyStateTest {
    private val font = FontConfig("Dialog", 14)
    private val emptyState = CompareEmptyState("Ready to compare", "Enter text above. Results from 4 translation services will appear here.")

    private fun primary(status: ProviderStatus = ProviderStatus.PLACEHOLDER, text: String = "") = TranslationProviderState(
        serviceId = "google",
        serviceName = "Google Translate",
        iconPath = null,
        role = ProviderRole.PRIMARY,
        presentation = ProviderPresentation.MAIN,
        status = status,
        text = text,
        loadingText = "Translating...",
        failureText = "Translation failed",
        copyLabel = "Copy",
        listenLabel = "Listen",
        stopLabel = "Stop",
        primaryLabel = "Primary",
        fontConfig = font,
        fallbackFontConfig = font
    )

    private fun secondary(status: ProviderStatus, text: String = "") = TranslationProviderState(
        serviceId = "bing",
        serviceName = "Bing",
        iconPath = null,
        role = ProviderRole.SECONDARY,
        presentation = ProviderPresentation.MAIN,
        status = status,
        text = text,
        loadingText = "Translating...",
        failureText = "Translation failed",
        copyLabel = "Copy",
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = {}
    )

    private fun render(board: CompareBoard, state: CompareBoardState) {
        SwingUtilities.invokeAndWait { board.render(state) }
        SwingUtilities.invokeAndWait { }
    }

    private fun layout(board: CompareBoard, width: Int, height: Int = 700) {
        SwingUtilities.invokeAndWait {
            board.setSize(width, height)
            repeat(3) { layoutTopDown(board) }
        }
    }

    private fun layoutTopDown(component: Component) {
        component.doLayout()
        if (component is Container) component.components.forEach(::layoutTopDown)
    }

    private fun startup(board: CompareBoard) =
        render(board, CompareBoardState(primary(), emptyList(), emptyState))

    @Test
    fun `startup with nothing translated shows the dedicated empty state`() {
        val board = CompareBoard()
        startup(board)
        val view = board.emptyStateForTest()
        assertTrue(view.isVisible)
        assertEquals("Ready to compare", view.titleForTest().text)
        assertTrue(view.messageForTest().text.contains("4 translation services"))
    }

    @Test
    fun `the guidance is the board's own and never primary provider content`() {
        val board = CompareBoard()
        startup(board)
        val view = board.emptyStateForTest()
        assertSame(board, view.parent)
        assertFalse(SwingUtilities.isDescendingFrom(view, board.primaryProviderView))
        assertFalse(board.primaryProviderView.bodyVisibleForTest(), "the primary keeps its header only")
        assertTrue(board.primaryProviderView.textPaneForTest().text.isEmpty())
    }

    @Test
    fun `primary identity stays visible above the empty state`() {
        val board = CompareBoard()
        startup(board)
        layout(board, 600)
        assertTrue(board.primaryProviderView.isVisible)
        assertEquals("Google Translate", board.primaryProviderView.providerNameForTest().text)
        assertTrue(board.emptyStateForTest().y >= board.primaryProviderView.y + board.primaryProviderView.height)
    }

    @Test
    fun `loading replaces the empty state`() {
        val board = CompareBoard()
        startup(board)
        render(board, CompareBoardState(primary(ProviderStatus.LOADING), listOf(secondary(ProviderStatus.LOADING)), emptyState))
        assertFalse(board.emptyStateForTest().isVisible)
        assertTrue(board.primaryProviderView.bodyVisibleForTest())
    }

    @Test
    fun `a primary that is loading hides it even before comparisons appear`() {
        val board = CompareBoard()
        startup(board)
        render(board, CompareBoardState(primary(ProviderStatus.LOADING), emptyList(), emptyState))
        assertFalse(board.emptyStateForTest().isVisible)
    }

    @Test
    fun `results replace the empty state`() {
        val board = CompareBoard()
        startup(board)
        render(
            board,
            CompareBoardState(primary(ProviderStatus.SUCCESS, "bonjour"), listOf(secondary(ProviderStatus.SUCCESS, "salut")), emptyState)
        )
        assertFalse(board.emptyStateForTest().isVisible)
    }

    @Test
    fun `provider failure shows the failure and not startup guidance`() {
        val board = CompareBoard()
        startup(board)
        render(
            board,
            CompareBoardState(primary(ProviderStatus.SUCCESS, "bonjour"), listOf(secondary(ProviderStatus.FAILURE)), emptyState)
        )
        assertFalse(board.emptyStateForTest().isVisible)
        assertTrue(board.secondaryViewForTest("bing")!!.textPaneForTest().text.isNotEmpty())
    }

    @Test
    fun `returns when the primary goes back to a placeholder without results`() {
        val board = CompareBoard()
        render(board, CompareBoardState(primary(ProviderStatus.SUCCESS, "bonjour"), emptyList(), emptyState))
        assertFalse(board.emptyStateForTest().isVisible)
        startup(board)
        assertTrue(board.emptyStateForTest().isVisible)
    }

    @Test
    fun `quick presentation never shows the board empty state`() {
        val board = CompareBoard()
        val quick = primary().copy(presentation = ProviderPresentation.QUICK, placeholderTitle = "Translation failed")
        render(board, CompareBoardState(quick, emptyList(), emptyState))
        assertFalse(board.emptyStateForTest().isVisible)
        assertTrue(board.primaryProviderView.bodyVisibleForTest(), "quick keeps the provider's own placeholder")
    }

    @Test
    fun `the empty state is centered in the space below the primary header`() {
        val board = CompareBoard()
        startup(board)
        layout(board, 600, height = 700)
        val view = board.emptyStateForTest()
        assertEquals(board.height - board.insets.bottom, view.y + view.height, "it fills the viewport below the header")
        val stack = listOf(view.titleForTest(), view.messageForTest())
        val top = stack.minOf { it.y }
        val bottom = stack.maxOf { it.y + it.height }
        val above = top
        val below = view.height - bottom
        assertTrue(kotlin.math.abs(above - below) <= 2, "stack is vertically centered (above=$above below=$below)")
        val center = (view.titleForTest().x + view.titleForTest().width / 2)
        assertTrue(kotlin.math.abs(center - view.width / 2) <= 1, "stack is horizontally centered")
    }

    @Test
    fun `narrow widths wrap inside the board without clipping or forcing width`() {
        val board = CompareBoard()
        startup(board)
        layout(board, 220, height = 600)
        val view = board.emptyStateForTest()
        listOf(view.titleForTest(), view.messageForTest()).forEach {
            assertTrue(it.x >= 0 && it.x + it.width <= view.width, "text must stay inside the board")
        }
        val message = view.messageForTest()
        val oneLine = message.getFontMetrics(message.font).stringWidth(message.text)
        assertTrue(message.height > message.getFontMetrics(message.font).height, "the message wraps to several lines")
        assertTrue(oneLine > message.width, "the message is wider than one line of the narrow board")
        SwingUtilities.invokeAndWait { }
        assertTrue(board.minimumSize.width == 0, "the board never asks for more width")
    }

    @Test
    fun `text wraps to a readable measure on wide boards`() {
        val board = CompareBoard()
        startup(board)
        layout(board, 1400)
        assertTrue(board.emptyStateForTest().messageForTest().width <= 400, "the stack stays compact")
    }

    @Test
    fun `follows the interface orientation`() {
        val board = CompareBoard()
        render(board, CompareBoardState(primary(ProviderStatus.PLACEHOLDER), emptyList(), emptyState))
        SwingUtilities.invokeAndWait { board.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT) }
        val view = board.emptyStateForTest()
        assertEquals(ComponentOrientation.RIGHT_TO_LEFT, view.titleForTest().componentOrientation)
        assertEquals(ComponentOrientation.RIGHT_TO_LEFT, view.messageForTest().componentOrientation)
        layout(board, 500)
        val title = view.titleForTest()
        assertTrue(kotlin.math.abs(title.x + title.width / 2 - view.width / 2) <= 1)
    }

    @Test
    fun `fills the viewport while it fits and scrolls normally when it does not`() {
        val board = CompareBoard()
        startup(board)
        val pane = JScrollPane(board)
        SwingUtilities.invokeAndWait {
            pane.setSize(600, 600)
            layoutTopDown(pane)
        }
        assertTrue(board.scrollableTracksViewportHeight)
        SwingUtilities.invokeAndWait {
            pane.setSize(600, 40)
            layoutTopDown(pane)
        }
        assertFalse(board.scrollableTracksViewportHeight)
        render(board, CompareBoardState(primary(ProviderStatus.SUCCESS, "bonjour"), emptyList(), emptyState))
        SwingUtilities.invokeAndWait {
            pane.setSize(600, 600)
            layoutTopDown(pane)
        }
        assertFalse(board.scrollableTracksViewportHeight, "real results scroll as before")
    }

    // ---- effective count and wording ----

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-empty-state").toFile(), LanguageTomlParser(), logger)
    }

    private fun config(primary: String, comparisons: List<String>, disabled: Set<String> = emptySet()) =
        Configuration.DEFAULT.copy(
            servicePresets = listOf(
                ServicePreset(
                    id = "preset",
                    name = "preset",
                    selectedServices = mapOf(ServiceRole.TRANSLATOR to primary),
                    comparisonTranslatorIds = comparisons
                )
            ),
            activeServicePresetId = "preset",
            disabledServices = disabled
        )

    @Test
    fun `the message counts effective translators only`() {
        val available = listOf("google", "bing", "deepl", "off")
        val cfg = config("google", listOf("bing", "gone", "bing", "off", "google"), disabled = setOf("off"))
        val count = cfg.effectiveTranslatorCount(available)
        assertEquals(2, count, "stale, duplicate, disabled and primary ids do not count")
        val message = localizer.getString("main_window.comparison_empty_subtitle", count)
        assertTrue(message.contains("2 translation services"), message)

        val four = config("google", listOf("bing", "deepl", "yandex")).effectiveTranslatorCount(listOf("google", "bing", "deepl", "yandex"))
        assertTrue(localizer.getString("main_window.comparison_empty_subtitle", four).contains("4 translation services"))
    }

    @Test
    fun `the main window feeds the board from the effective count and localization keys`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/MainContentView.kt").readText()
        assertTrue(source.contains("config.effectiveTranslatorCount(mainState.availableTranslatorIds)"))
        assertTrue(source.contains("localizer.getString(\"main_window.comparison_empty_subtitle\", readyCount)"))
        assertTrue(source.contains("localizer.getString(\"main_window.comparison_empty\")"))
        assertFalse(source.contains("placeholderTitle = localizer"), "the primary provider no longer carries the startup text")
    }

    // ---- no hard-coded colors ----

    @Test
    fun `the empty state uses theme colors only`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/output/CenteredStateView.kt").readText()
        assertFalse(Regex("""\bColor\s*\(""").containsMatchIn(source))
        assertFalse(Regex("""Color\.[A-Za-z]+""").containsMatchIn(source))
        assertFalse(Regex("""0x[0-9A-Fa-f]{6}""").containsMatchIn(source))
        assertTrue(source.contains("Label.disabledForeground") && source.contains("Label.foreground"))
        assertFalse(source.contains("paintComponent"), "no card or custom surface painting")
        assertFalse(source.contains("RoundRect") || source.contains("setBorder(BorderFactory.createLine") || source.contains("createLineBorder"))
    }

    // ---- Extra Output placeholder ----

    @Test
    fun `extra output placeholder is a stack card that keeps the panel size`() {
        val body = JPanel().apply { preferredSize = java.awt.Dimension(400, 150) }
        val placeholder = CenteredStateView().apply { render(null, "Available after translation") }
        val stack = JPanel(CardLayout()).apply {
            add(body, "body")
            add(placeholder, "placeholder")
        }
        val bodySize = stack.preferredSize
        (stack.layout as CardLayout).show(stack, "placeholder")
        assertEquals(bodySize, stack.preferredSize, "switching cards never resizes the panel")
        assertFalse(placeholder.titleForTest().isVisible, "the placeholder has no title")
        assertNull(placeholder.iconForTest().icon)
        assertEquals("Available after translation", placeholder.messageForTest().text)
    }

    @Test
    fun `extra output shows the placeholder only until a translation lands`() {
        val main = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/MainContentView.kt").readText()
        assertTrue(main.contains("localizer.getString(\"extra_output.placeholder\")"))
        assertTrue(main.contains(".takeIf { mainState.translatedText.isBlank() }"))
        val panel = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/output/ExtraOutputPanel.kt").readText()
        assertTrue(panel.contains("state.placeholderText != null && state.text.isBlank() && !state.isLoading"))
        assertTrue(panel.contains("(bodyStack.layout as CardLayout).show(bodyStack, if (showPlaceholder) PLACEHOLDER_CARD else BODY_CARD)"))
        assertFalse(panel.contains("setDividerLocation") || panel.contains("resizeWeight"), "no split behavior in the panel")
    }
}
