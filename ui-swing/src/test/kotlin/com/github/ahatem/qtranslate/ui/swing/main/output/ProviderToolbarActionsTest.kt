package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Copy and Listen/Stop behavior on the shared toolbar button, headless and structural. */
class ProviderToolbarActionsTest {
    private val font = FontConfig("Dialog", 14)

    private fun primary(
        text: String = "hello",
        isTtsPlaying: Boolean = false,
        onCopy: (String) -> Unit = {},
        onListen: (String) -> Unit = {},
        onStop: () -> Unit = {}
    ) = TranslationProviderState(
        serviceId = "google",
        serviceName = "Google Translate",
        iconPath = null,
        role = ProviderRole.PRIMARY,
        presentation = ProviderPresentation.MAIN,
        status = ProviderStatus.SUCCESS,
        text = text,
        copyLabel = "Copy",
        listenLabel = "Listen",
        stopLabel = "Stop",
        isTtsPlaying = isTtsPlaying,
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = onCopy,
        onListen = onListen,
        onStop = onStop
    )

    private fun render(view: TranslationProviderView, state: TranslationProviderState) {
        SwingUtilities.invokeAndWait { view.render(state) }
        SwingUtilities.invokeAndWait { }
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    @Test
    fun `copy and listen are shared toolbar buttons`() {
        val view = TranslationProviderView(null)
        render(view, primary())
        onEdt {
            listOf(view.copyButtonForTest(), view.listenButtonForTest()).forEach {
                assertEquals(
                    FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON,
                    it.getClientProperty(FlatClientProperties.BUTTON_TYPE)
                )
                assertTrue(it.isFocusable)
                assertTrue(it.isContentAreaFilled)
            }
        }
    }

    @Test
    fun `copy sends the body text`() {
        var copied: String? = null
        val view = TranslationProviderView(null)
        render(view, primary(text = "bonjour", onCopy = { copied = it }))
        onEdt { view.copyButtonForTest().doClick(0) }
        assertEquals("bonjour", copied)
    }

    @Test
    fun `listen becomes stop while speech plays and back again`() {
        var listened: String? = null
        var stops = 0
        val view = TranslationProviderView(null)

        render(view, primary(text = "bonjour", onListen = { listened = it }, onStop = { stops++ }))
        onEdt {
            assertEquals("Listen", view.listenButtonForTest().toolTipText)
            view.listenButtonForTest().doClick(0)
        }
        assertEquals("bonjour", listened)
        assertEquals(0, stops)

        render(view, primary(text = "bonjour", isTtsPlaying = true, onListen = { listened = "again" }, onStop = { stops++ }))
        onEdt {
            assertEquals("Stop", view.listenButtonForTest().toolTipText)
            view.listenButtonForTest().doClick(0)
        }
        assertEquals(1, stops)
        assertEquals("bonjour", listened)

        render(view, primary(text = "bonjour"))
        onEdt { assertEquals("Listen", view.listenButtonForTest().toolTipText) }
    }

    @Test
    fun `listen stays disabled when there is nothing to read`() {
        var listened = 0
        val view = TranslationProviderView(null)
        render(view, primary(text = "", onListen = { listened++ }))
        onEdt {
            assertFalse(view.listenButtonForTest().isEnabled)
            view.listenButtonForTest().doClick(0)
        }
        assertEquals(0, listened)
    }
}
