package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import java.awt.ComponentOrientation
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A failed secondary is compact until its error message is asked for; nothing else collapses. */
class SecondaryFailureCompactTest {
    private val font = FontConfig("Dialog", 14)

    private fun provider(
        id: String = "yandex",
        role: ProviderRole = ProviderRole.SECONDARY,
        status: ProviderStatus,
        text: String = "",
        error: String? = "HTTP 429: too many requests",
        details: String = "Details",
        presentation: ProviderPresentation = ProviderPresentation.MAIN
    ) = TranslationProviderState(
        serviceId = id,
        serviceName = id.replaceFirstChar { it.uppercase() },
        iconPath = null,
        role = role,
        presentation = presentation,
        status = status,
        text = text,
        errorMessage = error,
        loadingText = "Translating...",
        failureText = "Translation failed",
        copyLabel = "Copy",
        detailsLabel = details,
        primaryLabel = "Primary",
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = {}
    )

    private fun render(view: TranslationProviderView, state: TranslationProviderState) {
        onEdt { view.render(state) }
        onEdt { }
    }

    private fun click(view: TranslationProviderView) = onEdt { view.detailsButtonForTest().doClick(0) }

    @Test
    fun `a successful or loading secondary keeps its body and offers no details`() {
        listOf(ProviderStatus.SUCCESS to "salut", ProviderStatus.LOADING to "").forEach { (status, text) ->
            val view = TranslationProviderView(null)
            render(view, provider(status = status, text = text))
            assertTrue(view.bodyVisibleForTest(), "$status")
            assertFalse(view.detailsButtonForTest().isVisible, "$status")
        }
    }

    @Test
    fun `a failed secondary is compact with its failure in the header`() {
        // Quick uses the same compact failure as Main.
        ProviderPresentation.entries.forEach { presentation ->
            val view = TranslationProviderView(null)
            render(view, provider(status = ProviderStatus.FAILURE, presentation = presentation))
            assertFalse(view.bodyVisibleForTest(), "$presentation: no result body by default")
            assertEquals("Translation failed", view.statusLabelForTest().text)
            assertTrue(view.detailsButtonForTest().isVisible)
            assertEquals("Details", view.detailsButtonForTest().text)
        }
    }

    @Test
    fun `a failed primary is never compacted`() {
        ProviderPresentation.entries.forEach { presentation ->
            val view = TranslationProviderView(null)
            render(
                view,
                provider(id = "google", role = ProviderRole.PRIMARY, status = ProviderStatus.FAILURE, presentation = presentation),
            )
            assertTrue(view.bodyVisibleForTest(), "$presentation")
            assertFalse(view.detailsButtonForTest().isVisible, "$presentation")
        }
    }

    @Test
    fun `details offer only a message that adds something`() {
        val view = TranslationProviderView(null)
        render(view, provider(status = ProviderStatus.FAILURE, error = null))
        assertFalse(view.detailsButtonForTest().isVisible)
        assertFalse(view.bodyVisibleForTest(), "still compact, nothing more to say")

        render(view, provider(status = ProviderStatus.FAILURE, error = "Translation failed"))
        assertFalse(view.detailsButtonForTest().isVisible, "the header already says this")

        render(view, provider(status = ProviderStatus.FAILURE, details = ""))
        assertFalse(view.detailsButtonForTest().isVisible, "no localized label, no control")
    }

    @Test
    fun `details expand to the error message and collapse again`() {
        val view = TranslationProviderView(null)
        render(view, provider(status = ProviderStatus.FAILURE))
        val button = view.detailsButtonForTest()
        assertFalse(button.isSelected)

        click(view)
        assertTrue(view.bodyVisibleForTest())
        assertEquals("HTTP 429: too many requests", view.textPaneForTest().text)
        assertTrue(button.isSelected, "expansion shows as the button's own selected state")

        click(view)
        assertFalse(view.bodyVisibleForTest())
        assertFalse(button.isSelected)
    }

    @Test
    fun `an unrelated re-render keeps the expanded details`() {
        val view = TranslationProviderView(null)
        render(view, provider(status = ProviderStatus.FAILURE))
        click(view)
        render(view, provider(status = ProviderStatus.FAILURE))
        assertTrue(view.bodyVisibleForTest())
    }

    @Test
    fun `sibling updates on the board do not reset an expanded failure`() {
        val board = CompareBoard()
        fun show(sibling: ProviderStatus) = onEdt {
            board.render(
                CompareBoardState(
                    primary = provider(id = "google", role = ProviderRole.PRIMARY, status = ProviderStatus.SUCCESS, text = "bonjour"),
                    secondaries = listOf(
                        provider(id = "yandex", status = ProviderStatus.FAILURE),
                        provider(id = "bing", status = sibling, text = if (sibling == ProviderStatus.SUCCESS) "salut" else "")
                    )
                )
            )
        }
        show(ProviderStatus.LOADING)
        val yandex = board.secondaryViewForTest("yandex")!!
        click(yandex)
        assertTrue(yandex.bodyVisibleForTest())

        show(ProviderStatus.SUCCESS)
        assertTrue(board.secondaryViewForTest("yandex")!!.bodyVisibleForTest(), "the sibling finishing leaves it open")
    }

    @Test
    fun `a new translation starts the failure compact again`() {
        val view = TranslationProviderView(null)
        render(view, provider(status = ProviderStatus.FAILURE))
        click(view)
        assertTrue(view.bodyVisibleForTest())

        render(view, provider(status = ProviderStatus.LOADING))
        assertTrue(view.bodyVisibleForTest(), "loading shows its own body")
        assertFalse(view.detailsButtonForTest().isVisible)

        render(view, provider(status = ProviderStatus.FAILURE))
        assertFalse(view.bodyVisibleForTest(), "the same failure is compact again")
        assertFalse(view.detailsButtonForTest().isSelected)
    }

    @Test
    fun `a different error or provider does not inherit the expansion`() {
        val view = TranslationProviderView(null)
        render(view, provider(status = ProviderStatus.FAILURE))
        click(view)
        render(view, provider(status = ProviderStatus.FAILURE, error = "HTTP 503"))
        assertFalse(view.bodyVisibleForTest(), "a different message starts compact")

        click(view)
        render(view, provider(id = "deepl", status = ProviderStatus.FAILURE, error = "HTTP 503"))
        assertFalse(view.bodyVisibleForTest(), "a different provider starts compact")
    }

    @Test
    fun `success after a failure shows the result normally`() {
        val view = TranslationProviderView(null)
        render(view, provider(status = ProviderStatus.FAILURE))
        render(view, provider(status = ProviderStatus.SUCCESS, text = "salut"))
        assertTrue(view.bodyVisibleForTest())
        assertEquals("salut", view.textPaneForTest().text)
    }

    @Test
    fun `details stays in the logical header actions in right to left`() {
        val view = TranslationProviderView(null)
        render(view, provider(status = ProviderStatus.FAILURE))
        onEdt { view.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT }
        val actions = view.headerActionsForTest().components.toList()
        assertTrue(actions.indexOf(view.statusLabelForTest()) < actions.indexOf(view.detailsButtonForTest()))
        click(view)
        assertTrue(view.bodyVisibleForTest())
    }
}
