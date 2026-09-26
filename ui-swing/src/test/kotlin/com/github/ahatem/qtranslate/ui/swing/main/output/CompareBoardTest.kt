package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompareBoardTest {
    private val font = FontConfig("Dialog", 14)

    private fun primary(
        text: String = "primary result",
        status: ProviderStatus = ProviderStatus.SUCCESS,
        serviceId: String = "google",
        serviceName: String = "Google",
        definition: String = "",
        selectorState: TranslatorSelectorState? = null
    ) = TranslationProviderState(
        serviceId = serviceId,
        serviceName = serviceName,
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
        placeholderTitle = "Translate to compare results",
        placeholderSubtitle = "4 translation services ready",
        definition = definition,
        fontConfig = font,
        fallbackFontConfig = font,
        selectorState = selectorState
    )

    private fun secondary(
        id: String,
        name: String? = null,
        status: ProviderStatus = ProviderStatus.SUCCESS,
        text: String = "$id result",
        error: String? = null
    ) = TranslationProviderState(
        serviceId = id,
        serviceName = name,
        iconPath = null,
        role = ProviderRole.SECONDARY,
        presentation = ProviderPresentation.MAIN,
        status = status,
        text = text,
        errorMessage = error,
        loadingText = "Translating...",
        failureText = "Translation failed",
        copyLabel = "Copy",
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = {}
    )

    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, IconManager::class.java) as IconManager
    }

    private fun render(board: CompareBoard, state: CompareBoardState) {
        SwingUtilities.invokeAndWait { board.render(state) }
        SwingUtilities.invokeAndWait { }
    }

    private fun layout(board: CompareBoard, width: Int, height: Int = 800) {
        SwingUtilities.invokeAndWait {
            board.setSize(width, height)
            // Explicit passes, not validity-driven: the first assigns every
            // bound top-down, the next ones read the fresh widths, which is
            // what width-dependent decisions such as the readable cap need.
            repeat(3) { layoutTopDown(board) }
        }
        SwingUtilities.invokeAndWait { }
    }

    private fun layoutTopDown(component: Component) {
        component.doLayout()
        if (component is Container) component.components.forEach(::layoutTopDown)
    }

    private fun descendants(component: Component): List<Component> =
        listOf(component) + if (component is Container) {
            component.components.flatMap(::descendants)
        } else emptyList()

    @Test
    fun `empty board shows one integrated primary placeholder with live header`() {
        val board = CompareBoard(iconManager = null)
        render(board, CompareBoardState(primary = primary(status = ProviderStatus.PLACEHOLDER, text = ""), secondaries = emptyList()))

        assertTrue(board.isVisible)
        val labels = descendants(board).filterIsInstance<JLabel>().mapNotNull { it.text }
        assertTrue(labels.contains("Translate to compare results"))
        assertTrue(labels.contains("4 translation services ready"))
        assertTrue(labels.none { it == "Compare translations" })
        // Primary header stays live: provider name plus the small Primary tag.
        assertTrue(labels.contains("Google"))
        assertTrue(labels.contains("Primary"))
        assertEquals(listOf("google"), board.orderedServiceIdsForTest())
    }

    @Test
    fun `mixed provider states keep configuration order with copy only on success`() {
        var copied = ""
        val board = CompareBoard(iconManager = null)
        val state = CompareBoardState(
            primary = primary(),
            secondaries = listOf(
                secondary("first", "First", ProviderStatus.LOADING, ""),
                secondary("second", "Second", ProviderStatus.SUCCESS, "second text"),
                secondary("third", "Third", ProviderStatus.FAILURE, "", "Timed out")
            )
        ).let {
            it.copy(secondaries = it.secondaries.map { s ->
                if (s.serviceId == "second") s.copy(onCopy = { copied = it }) else s
            })
        }
        render(board, state)

        assertEquals(listOf("google", "first", "second", "third"), board.orderedServiceIdsForTest())
        val labels = descendants(board).filterIsInstance<JLabel>().mapNotNull { it.text }
        assertTrue(labels.indexOf("First") < labels.indexOf("Second"))
        assertTrue(labels.indexOf("Second") < labels.indexOf("Third"))
        assertTrue(labels.contains("Translating..."))
        assertEquals("Timed out", board.secondaryViewForTest("third")?.textPaneForTest()?.text)

        val panes = descendants(board).filterIsInstance<AdvancedTextPane>()
        assertEquals(4, panes.size)
        assertEquals("second text", panes.single { it.text == "second text" }.text)

        val copyButtons = descendants(board).filterIsInstance<JButton>()
            .filter { it.actionCommand == "second text" }
        assertEquals(1, copyButtons.size)
        SwingUtilities.invokeAndWait { copyButtons.single().doClick() }
        assertEquals("second text", copied)

        // Secondaries expose Copy only: no Listen, no collapse.
        val secondaryButtons = listOf("first", "second", "third").flatMap { id ->
            descendants(board.secondaryViewForTest(id)!!).filterIsInstance<JButton>()
        }
        assertTrue(secondaryButtons.none { it.toolTipText == "Listen" || it.toolTipText == "Stop" })
        assertTrue(descendants(board).filterIsInstance<JLabel>().none {
            it.text == "Collapse result" || it.text == "Expand result"
        })
    }

    @Test
    fun `sibling completion preserves provider component pane and selection`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(),
                secondaries = listOf(
                    secondary("deepl", "DeepL", ProviderStatus.SUCCESS, "deep result"),
                    secondary("bing", "Bing", ProviderStatus.LOADING, "")
                )
            )
        )
        lateinit var deepView: TranslationProviderView
        lateinit var deepPane: AdvancedTextPane
        SwingUtilities.invokeAndWait {
            deepView = board.secondaryViewForTest("deepl")!!
            deepPane = deepView.textPaneForTest()
            deepPane.select(0, 4)
        }
        render(
            board, CompareBoardState(
                primary = primary(),
                secondaries = listOf(
                    secondary("deepl", "DeepL", ProviderStatus.SUCCESS, "deep result"),
                    secondary("bing", "Bing", ProviderStatus.SUCCESS, "bing result")
                )
            )
        )

        assertSame(deepView, board.secondaryViewForTest("deepl"))
        assertSame(deepPane, board.secondaryViewForTest("deepl")?.textPaneForTest())
        assertEquals(0, deepPane.selectionStart)
        assertEquals(4, deepPane.selectionEnd)
        assertEquals("bing result", board.secondaryViewForTest("bing")?.textPaneForTest()?.text)
    }

    @Test
    fun `narrow board stacks a single column without clipping`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(text = "نص عربي طويل للاختبار"),
                secondaries = listOf(
                    secondary("one", "One", ProviderStatus.SUCCESS, "نتيجة أولى طويلة بما يكفي للالتفاف"),
                    secondary("two", "Two", ProviderStatus.SUCCESS, "second result"),
                    secondary("three", "Three", ProviderStatus.SUCCESS, "third result")
                )
            )
        )
        layout(board, 480)

        assertFalse(board.isWideForTest())
        val views = board.components.filterIsInstance<TranslationProviderView>()
        assertEquals(4, views.size)
        val xs = views.map { it.x }.toSet()
        assertEquals(1, xs.size, "narrow board must stack every provider in one column")
        views.forEach { view ->
            assertTrue(view.x >= 0)
            assertTrue(view.x + view.width <= board.width, "provider exceeds the board width")
        }
    }

    @Test
    fun `wide board uses two reading order columns`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(),
                secondaries = listOf(
                    secondary("one", "One"),
                    secondary("two", "Two"),
                    secondary("three", "Three")
                )
            )
        )
        layout(board, 1000)

        assertTrue(board.isWideForTest())
        val primaryView = board.primaryProviderView
        assertEquals(board.components.filterIsInstance<TranslationProviderView>().first(), primaryView)
        val secondaries = listOf("one", "two", "three").map { board.secondaryViewForTest(it)!! }
        val xs = secondaries.map { it.x }.toSet()
        assertEquals(2, xs.size, "wide board must arrange secondaries in two columns")
        // Reading order: first provider on the leading side.
        assertTrue(secondaries[0].x < secondaries[1].x)
        assertEquals(secondaries[0].x, secondaries[2].x)
        secondaries.forEach { view ->
            assertTrue(view.x + view.width <= board.width, "provider exceeds the board width")
        }
    }

    @Test
    fun `rtl board mirrors the visual column order but keeps configuration order`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(),
                secondaries = listOf(secondary("one", "One"), secondary("two", "Two"))
            )
        )
        SwingUtilities.invokeAndWait {
            board.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
            board.setSize(1000, 800)
            board.doLayout()
        }
        SwingUtilities.invokeAndWait { }

        assertTrue(board.isWideForTest())
        val first = board.secondaryViewForTest("one")!!
        val second = board.secondaryViewForTest("two")!!
        assertTrue(first.x > second.x, "first provider belongs on the reading-order right side")
        assertEquals(listOf("google", "one", "two"), board.orderedServiceIdsForTest())
    }

    @Test
    fun `board tracks the viewport width`() {
        val board = CompareBoard(iconManager = null)
        assertTrue(board.getScrollableTracksViewportWidth())
        assertFalse(board.getScrollableTracksViewportHeight())
        render(
            board, CompareBoardState(
                primary = primary(text = "a".repeat(500)),
                secondaries = listOf(secondary("one", "One", ProviderStatus.SUCCESS, "b".repeat(500)))
            )
        )
        layout(board, 400)
        board.components.filterIsInstance<TranslationProviderView>().forEach { view ->
            assertTrue(view.x + view.width <= board.width)
        }
        var preferredWidth = -1
        SwingUtilities.invokeAndWait { preferredWidth = board.preferredSize.width }
        assertEquals(400, preferredWidth)
    }

    @Test
    fun `no providers affordance is compact and fires configure`() {
        var configured = false
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(),
                secondaries = emptyList(),
                showNoProviders = true,
                noProvidersText = "No comparison translators selected.",
                configureLabel = "Configure",
                onConfigure = { configured = true }
            )
        )

        assertTrue(board.noProvidersVisibleForTest())
        val labels = descendants(board).filterIsInstance<JLabel>().mapNotNull { it.text }
        assertTrue(labels.contains("No comparison translators selected."))
        val button = descendants(board).filterIsInstance<JButton>().single { it.text == "Configure" }
        SwingUtilities.invokeAndWait { button.doClick() }
        assertTrue(configured)
    }

    @Test
    fun `failure keeps provider identity with selectable error summary`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(),
                secondaries = listOf(secondary("bad", "Bad", ProviderStatus.FAILURE, "", "Timed out after 10s"))
            )
        )
        val view = board.secondaryViewForTest("bad")!!
        assertEquals("Timed out after 10s", view.textPaneForTest().text)
        assertTrue(view.textPaneForTest().isVisible)
        val labels = descendants(view).filterIsInstance<JLabel>().mapNotNull { it.text }
        assertTrue(labels.contains("Bad"))
        assertTrue(labels.contains("Translation failed"))
        assertTrue(descendants(view).filterIsInstance<JButton>().none { it.isVisible })
    }

    @Test
    fun `loading keeps identity visible without copy`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(status = ProviderStatus.LOADING, text = ""),
                secondaries = listOf(secondary("slow", "Slow", ProviderStatus.LOADING, ""))
            )
        )
        val labels = descendants(board).filterIsInstance<JLabel>().mapNotNull { it.text }
        assertTrue(labels.contains("Slow"))
        assertTrue(labels.contains("Translating..."))
        assertTrue(descendants(board.secondaryViewForTest("slow")!!).filterIsInstance<JButton>().none { it.isVisible })
    }

    @Test
    fun `primary selector presents translator name as one control`() {
        val selector = TranslatorPopupButton(blindIconManager(), {}).also {
            SwingUtilities.invokeAndWait {
                it.render(
                    TranslatorSelectorState(
                        availableTranslators = listOf(
                            ServiceInfo("google", "Google Translate", null, ServiceRole.TRANSLATOR)
                        ),
                        selectedTranslatorId = "google",
                        isLoading = false
                    )
                )
            }
        }
        val board = CompareBoard(primarySelector = selector, iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(
                    selectorState = TranslatorSelectorState(
                        availableTranslators = listOf(
                            ServiceInfo("google", "Google Translate", null, ServiceRole.TRANSLATOR)
                        ),
                        selectedTranslatorId = "google",
                        isLoading = false
                    )
                ),
                secondaries = emptyList()
            )
        )
        assertTrue(selector.isVisible)
        assertEquals("Google Translate", selector.buttonForTest().text)
    }

    @Test
    fun `provider without resolvable icon shows name without a fake logo`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(),
                secondaries = listOf(secondary("mystery", "Mystery", ProviderStatus.SUCCESS, "text"))
            )
        )
        val view = board.secondaryViewForTest("mystery")!!
        val labels = descendants(view).filterIsInstance<JLabel>()
        assertTrue(labels.mapNotNull { it.text }.contains("Mystery"))
        assertTrue(labels.none { it.icon != null })
        assertEquals("text", view.textPaneForTest().text)
    }

    @Test
    fun `wide primary body is capped for readable line length`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(text = "word ".repeat(200)),
                secondaries = emptyList()
            )
        )
        // Scale-aware: the cap is in scaled pixels, so the board must exceed it
        // whatever display scale the test JVM reports.
        val wideEnough = UIScale.scale(TranslationProviderView.READABLE_BODY_MAX) + 600
        layout(board, wideEnough)
        assertTrue(
            board.primaryProviderView.readableCapActiveForTest(),
            "readable cap inactive at board width $wideEnough"
        )
    }

    @Test
    fun `definition belongs to the primary provider`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(definition = "a short definition"),
                secondaries = listOf(secondary("one", "One"))
            )
        )
        val primaryLabels = descendants(board.primaryProviderView)
            .filterIsInstance<javax.swing.JTextArea>().map { it.text }
        assertTrue(primaryLabels.contains("a short definition"))
        val secondaryText = descendants(board.secondaryViewForTest("one")!!)
            .filterIsInstance<javax.swing.JTextArea>().map { it.text }
        assertTrue(secondaryText.none { it == "a short definition" })
    }

    @Test
    fun `zero secondary configuration still works with primary only`() {
        val board = CompareBoard(iconManager = null)
        render(
            board, CompareBoardState(
                primary = primary(),
                secondaries = emptyList()
            )
        )
        assertFalse(board.noProvidersVisibleForTest())
        assertEquals("primary result", board.primaryProviderView.textPaneForTest().text)
    }
}
