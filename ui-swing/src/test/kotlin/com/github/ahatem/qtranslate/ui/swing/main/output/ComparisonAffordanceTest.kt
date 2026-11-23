package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Details control of a failed provider and the Primary translator selector. */
class ComparisonAffordanceTest {
    private val font = FontConfig("Dialog", 14)

    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, IconManager::class.java) as IconManager
    }

    private fun failedSecondary() = TranslationProviderState(
        serviceId = "yandex", serviceName = "Yandex", iconPath = null,
        role = ProviderRole.SECONDARY, presentation = ProviderPresentation.MAIN,
        status = ProviderStatus.FAILURE, text = "", errorMessage = "HTTP 429: too many requests",
        loadingText = "Translating...", failureText = "Translation failed", copyLabel = "Copy",
        detailsLabel = "Details", primaryLabel = "Primary",
        fontConfig = font, fallbackFontConfig = font, onCopy = {}
    )

    private fun detailsView(orientation: ComponentOrientation = ComponentOrientation.LEFT_TO_RIGHT): TranslationProviderView {
        val view = TranslationProviderView(null)
        onEdt {
            view.applyComponentOrientation(orientation)
            view.render(failedSecondary())
        }
        onEdt { }
        return view
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    @Test
    fun `details shows its label and a disclosure icon`() {
        val button = detailsView().detailsButtonForTest()
        assertTrue(button.isVisible)
        assertEquals("Details", button.text, "the label is visible text, not only an icon")
        assertNotNull(button.icon, "the disclosure icon is shown beside it")
    }

    @Test
    fun `a failed provider header shows its whole message and the whole Details control`() {
        try {
            listOf(1f, 2.25f).forEach { zoom ->
                com.formdev.flatlaf.util.UIScale.setZoomFactor(zoom)
                assertHeaderFits((640 * zoom).toInt())
            }
        } finally {
            com.formdev.flatlaf.util.UIScale.setZoomFactor(1f)
        }
    }

    private fun assertHeaderFits(width: Int) {
        listOf(ComponentOrientation.LEFT_TO_RIGHT, ComponentOrientation.RIGHT_TO_LEFT).forEach { orientation ->
            val view = detailsView(orientation)
            onEdt { view.setSize(width, 120); layoutTree(view) }
            onEdt { layoutTree(view) }
            val status = view.statusLabelForTest()
            val details = view.detailsButtonForTest()
            assertTrue(status.width >= status.preferredSize.width, "the failure text is not clipped (${status.width} of ${status.preferredSize.width})")
            assertTrue(details.width >= details.preferredSize.width, "Details keeps its label and chevron")
            val actions = view.headerActionsForTest()
            assertTrue(
                status.x >= 0 && details.x >= 0 && details.x + details.width <= actions.width && status.x + status.width <= actions.width,
                "both stay inside the header's action area"
            )
        }
    }

    private fun selectorState() = TranslatorSelectorState(
        availableTranslators = listOf(
            ServiceInfo("google", "Google Translate", null, ServiceRole.TRANSLATOR),
            ServiceInfo("bing", "Bing", null, ServiceRole.TRANSLATOR)
        ),
        selectedTranslatorId = "google",
        isLoading = false
    )

    private fun textSelector(onSelected: (String) -> Unit = {}): TranslatorPopupButton {
        val selector = TranslatorPopupButton(blindIconManager(), onSelected)
        onEdt {
            selector.textMode = true
            selector.actionTooltip = "Change primary translator"
            selector.render(selectorState())
        }
        return selector
    }

    @Test
    fun `the primary selector is one button with no child controls`() {
        val selector = textSelector()
        assertTrue(selector is JButton)
        assertEquals(0, selector.componentCount)
        assertTrue(descendants(selector).none { it is JLabel }, "no separate clickable chevron label remains")
    }

    @Test
    fun `the primary selector says what it does`() {
        assertEquals("Change primary translator", textSelector().toolTipText)
        val unlabelled = TranslatorPopupButton(blindIconManager(), {})
        onEdt { unlabelled.render(selectorState()) }
        assertEquals("Google Translate", unlabelled.toolTipText, "without a label the tooltip is still the provider")
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }
}
