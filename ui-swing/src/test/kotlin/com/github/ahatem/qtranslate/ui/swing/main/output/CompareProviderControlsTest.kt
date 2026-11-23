package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import java.awt.FlowLayout
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Per-provider header actions: Quick hides the duplicates of the top bar, Main keeps them. */
class CompareProviderControlsTest {
    private val font = FontConfig("Dialog", 14)

    private fun primary(
        presentation: ProviderPresentation = ProviderPresentation.MAIN,
        status: ProviderStatus = ProviderStatus.SUCCESS,
        text: String = "primary result",
        isTtsPlaying: Boolean = false,
    ) = TranslationProviderState(
        serviceId = "google",
        serviceName = "Google Translate",
        iconPath = null,
        role = ProviderRole.PRIMARY,
        presentation = presentation,
        status = status,
        text = text,
        loadingText = "Translating...",
        failureText = "Translation failed",
        copyLabel = "Copy",
        listenLabel = "Listen",
        stopLabel = "Stop",
        isTtsPlaying = isTtsPlaying,
        primaryLabel = "Primary",
        placeholderTitle = "Translate to compare results",
        placeholderSubtitle = "4 translation services ready",
        fontConfig = font,
        fallbackFontConfig = font,
    )

    private fun secondary(
        presentation: ProviderPresentation = ProviderPresentation.MAIN,
        status: ProviderStatus = ProviderStatus.SUCCESS,
        text: String = "second result"
    ) = TranslationProviderState(
        serviceId = "second",
        serviceName = "Second",
        iconPath = null,
        role = ProviderRole.SECONDARY,
        presentation = presentation,
        status = status,
        text = text,
        loadingText = "Translating...",
        failureText = "Translation failed",
        copyLabel = "Copy",
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = {}
    )

    private fun renderOnEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait(block)
        SwingUtilities.invokeAndWait { }
    }

    private fun descendants(component: Component): List<Component> =
        listOf(component) + if (component is Container) {
            component.components.flatMap(::descendants)
        } else emptyList()

    @Test
    fun `quick primary header exposes neither copy nor listen`() {
        val view = TranslationProviderView(null)
        renderOnEdt {
            view.render(primary(presentation = ProviderPresentation.QUICK, status = ProviderStatus.SUCCESS, text = "hello"))
        }
        var copyVisible = true
        var listenVisible = true
        SwingUtilities.invokeAndWait {
            copyVisible = view.copyButtonForTest().isVisible
            listenVisible = view.listenButtonForTest().isVisible
        }
        assertFalse(copyVisible, "QUICK primary must not duplicate the top-bar Copy")
        assertFalse(listenVisible, "QUICK primary must not duplicate the top-bar Listen")
    }

    @Test
    fun `quick primary header stays quiet even while speech plays`() {
        val view = TranslationProviderView(null)
        renderOnEdt {
            view.render(
                primary(
                    presentation = ProviderPresentation.QUICK,
                    status = ProviderStatus.SUCCESS,
                    text = "hello",
                    isTtsPlaying = true
                )
            )
        }
        var listenVisible = true
        SwingUtilities.invokeAndWait { listenVisible = view.listenButtonForTest().isVisible }
        assertFalse(listenVisible, "QUICK Stop lives on the top bar, not in the provider header")
    }

    @Test
    fun `quick secondary keeps its copy action`() {
        val board = CompareBoard(iconManager = null)
        renderOnEdt {
            board.render(
                CompareBoardState(
                    primary = primary(presentation = ProviderPresentation.QUICK),
                    secondaries = listOf(secondary(presentation = ProviderPresentation.QUICK))
                )
            )
        }
        var copyVisible = false
        SwingUtilities.invokeAndWait {
            copyVisible = board.secondaryViewForTest("second")!!.copyButtonForTest().isVisible
        }
        assertTrue(copyVisible, "secondary Quick results keep their per-provider Copy")
    }

    @Test
    fun `main primary retains copy and listen`() {
        val view = TranslationProviderView(null)
        renderOnEdt { view.render(primary(presentation = ProviderPresentation.MAIN)) }
        var copyVisible = false
        var listenVisible = false
        SwingUtilities.invokeAndWait {
            copyVisible = view.copyButtonForTest().isVisible
            listenVisible = view.listenButtonForTest().isVisible
        }
        assertTrue(copyVisible, "MAIN primary keeps its Copy")
        assertTrue(listenVisible, "MAIN primary keeps its Listen")
    }

    @Test
    fun `main primary action order keeps copy on the trailing column`() {
        val view = TranslationProviderView(null)
        renderOnEdt { view.render(primary(presentation = ProviderPresentation.MAIN)) }
        var flowAlignment = -1
        var listenIndex = -1
        var copyIndex = -1
        SwingUtilities.invokeAndWait {
            val actions = view.headerActionsForTest()
            flowAlignment = (actions.layout as FlowLayout).alignment
            val order = actions.components.toList()
            listenIndex = order.indexOf(view.listenButtonForTest())
            copyIndex = order.indexOf(view.copyButtonForTest())
        }
        assertEquals(FlowLayout.TRAILING, flowAlignment, "actions must use logical trailing alignment, not hard-coded sides")
        assertTrue(listenIndex >= 0 && copyIndex >= 0, "both Listen and Copy must be present")
        assertTrue(listenIndex < copyIndex, "Listen/Stop precedes Copy so Copy stays on the outer column")

        // RTL mirrors through the logical layout, so the relative order never changes.
        renderOnEdt { view.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT }
        var rtlListen = -1
        var rtlCopy = -1
        SwingUtilities.invokeAndWait {
            val order = view.headerActionsForTest().components.toList()
            rtlListen = order.indexOf(view.listenButtonForTest())
            rtlCopy = order.indexOf(view.copyButtonForTest())
        }
        assertEquals(listenIndex, rtlListen)
        assertEquals(copyIndex, rtlCopy)
        renderOnEdt { view.componentOrientation = ComponentOrientation.LEFT_TO_RIGHT }
    }

    @Test
    fun `provider copy and listen are keyboard focusable`() {
        val view = TranslationProviderView(null)
        renderOnEdt { view.render(primary(presentation = ProviderPresentation.MAIN)) }
        SwingUtilities.invokeAndWait {
            assertTrue(view.copyButtonForTest().isFocusable, "Copy must accept keyboard focus")
            assertTrue(view.listenButtonForTest().isFocusable, "Listen must accept keyboard focus")
        }
    }

    @Test
    fun `failure header height stays stable against loading`() {
        val view = TranslationProviderView(null)
        renderOnEdt { view.render(primary(status = ProviderStatus.LOADING, text = "")) }
        var loadingHeight = 0
        SwingUtilities.invokeAndWait {
            view.doLayout()
            loadingHeight = view.headerActionsForTest().preferredSize.height
        }
        renderOnEdt { view.render(primary(status = ProviderStatus.FAILURE, text = "")) }
        var failureHeight = 0
        var iconHeight = 0
        SwingUtilities.invokeAndWait {
            view.doLayout()
            failureHeight = view.headerActionsForTest().preferredSize.height
            iconHeight = view.statusLabelForTest().icon?.iconHeight ?: 0
        }
        assertTrue(iconHeight <= UIScale.scale(16), "failure glyph must not exceed the standard action size")
        assertTrue(
            failureHeight <= loadingHeight + UIScale.scale(4),
            "failure header must not grow beyond loading (loading=$loadingHeight failure=$failureHeight)"
        )
    }
}
