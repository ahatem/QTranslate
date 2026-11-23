package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.effectiveTranslatorCount
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.testing.SilentLogger
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-empty-state").toFile(), LanguageTomlParser(), SilentLogger)
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

    /**
     * IconManager resolves the app's own icons without touching its plugin manager, so a manager
     * built around an unconstructed plugin manager is enough to make a real panel.
     */
    private fun iconManager(): IconManager {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return IconManager(allocate.invoke(unsafe, PluginManager::class.java) as PluginManager)
    }

    private fun extraPanel(): ExtraOutputPanel {
        var panel: ExtraOutputPanel? = null
        SwingUtilities.invokeAndWait { panel = ExtraOutputPanel(iconManager(), localizer, onListen = {}, onTranslateRequest = {}) }
        return panel!!
    }

    private fun extraState(text: String = "", isLoading: Boolean = false, placeholder: String? = "Available after translation") =
        ExtraOutputState(
            text = text,
            fontConfig = font,
            fallbackFontConfig = font,
            isLoading = isLoading,
            isVisible = true,
            actionsState = TextActionsState(emptyList()),
            activeType = ExtraOutputType.BackwardTranslate,
            placeholderText = placeholder
        )

    private fun renderExtra(panel: ExtraOutputPanel, state: ExtraOutputState, width: Int = 500, height: Int = 200) {
        SwingUtilities.invokeAndWait {
            panel.render(state)
            panel.setSize(width, height)
            repeat(3) { layoutTopDown(panel) }
        }
    }

    @Test
    fun `startup extra output shows the placeholder over the empty body`() {
        val panel = extraPanel()
        renderExtra(panel, extraState())
        assertTrue(panel.placeholderVisibleForTest())
        val stack = panel.bodyStackForTest()
        val view = stack.components.filterIsInstance<CenteredStateView>().single()
        val body = stack.components.first { it !is CenteredStateView }
        assertTrue(view.isVisible && body.isVisible, "the body stays mounted under the placeholder")
        assertTrue(stack.getComponentZOrder(view) < stack.getComponentZOrder(body), "the placeholder is painted over the body")
        assertFalse(view.contains(1, 1), "clicks fall through to the body")
        val message = view.messageForTest()
        assertEquals("Available after translation", message.text)
        assertTrue(message.width > 0 && message.height > 0, "the message has real bounds")
        assertEquals(UIManager.getColor("Label.disabledForeground"), message.foreground, "muted theme color")
    }

    @Test
    fun `a later render of the same startup state keeps the placeholder`() {
        val panel = extraPanel()
        repeat(3) { renderExtra(panel, extraState()) }
        assertTrue(panel.placeholderVisibleForTest())
        assertTrue(panel.bodyStackForTest().components.all { it.isVisible })
    }

    @Test
    fun `loading and real content take over from the placeholder`() {
        val panel = extraPanel()
        renderExtra(panel, extraState())
        renderExtra(panel, extraState(isLoading = true))
        assertFalse(panel.placeholderVisibleForTest(), "loading shows the body")
        renderExtra(panel, extraState())
        assertTrue(panel.placeholderVisibleForTest())
        renderExtra(panel, extraState(text = "backward result", placeholder = null))
        assertFalse(panel.placeholderVisibleForTest())
        renderExtra(panel, extraState(text = "backward result"))
        assertFalse(panel.placeholderVisibleForTest(), "text wins even if a placeholder is offered")
    }

    @Test
    fun `primary status separates initial loading success and failure`() {
        assertEquals(ProviderStatus.PLACEHOLDER, primaryProviderStatus("", isLoading = false, translationFailed = false))
        assertEquals(ProviderStatus.LOADING, primaryProviderStatus("", isLoading = true, translationFailed = false))
        assertEquals(ProviderStatus.SUCCESS, primaryProviderStatus("bonjour", isLoading = false, translationFailed = false))
        assertEquals(ProviderStatus.FAILURE, primaryProviderStatus("", isLoading = false, translationFailed = true))
    }

    @Test
    fun `startup translate failure and retry walk the board through the right states`() {
        val board = CompareBoard()
        fun show(text: String, loading: Boolean, failed: Boolean) = render(
            board,
            CompareBoardState(primary(primaryProviderStatus(text, loading, failed), text), emptyList(), emptyState)
        )

        show("", loading = false, failed = false)
        assertTrue(board.emptyStateForTest().isVisible, "startup")

        show("", loading = true, failed = false)
        assertFalse(board.emptyStateForTest().isVisible, "translation start")

        show("", loading = false, failed = true)
        assertFalse(board.emptyStateForTest().isVisible, "a failed primary never says Ready to compare")
        assertTrue(board.primaryProviderView.bodyVisibleForTest(), "the failure presentation is shown")
        assertEquals("Translation failed", board.primaryProviderView.statusLabelForTest().text)

        show("", loading = true, failed = false)
        assertFalse(board.emptyStateForTest().isVisible, "retry")
        assertNotEquals("Translation failed", board.primaryProviderView.statusLabelForTest().text)

        show("bonjour", loading = false, failed = false)
        assertFalse(board.emptyStateForTest().isVisible, "success")
    }
}
