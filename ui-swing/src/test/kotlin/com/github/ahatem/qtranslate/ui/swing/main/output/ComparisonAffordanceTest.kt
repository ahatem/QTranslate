package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two Comparison controls that used to be too easy to miss: the Details control of a failed
 * provider, and the Primary translator selector. Structural assertions only.
 */
class ComparisonAffordanceTest {
    private val font = FontConfig("Dialog", 14)

    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, IconManager::class.java) as IconManager
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

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

    // --- Details -----------------------------------------------------------------------------

    @Test
    fun `details shows its label and a disclosure icon`() {
        val button = detailsView().detailsButtonForTest()
        assertTrue(button.isVisible)
        assertEquals("Details", button.text, "the label is visible text, not only an icon")
        assertNotNull(button.icon, "the disclosure icon is shown beside it")
    }

    @Test
    fun `collapsed and expanded details use different disclosure icons`() {
        val view = detailsView()
        val button = view.detailsButtonForTest()
        val collapsed = button.icon

        onEdt { button.doClick(0) }
        assertTrue(button.isSelected)
        assertNotNull(button.icon)
        assertNotEquals(collapsed, button.icon, "the icon says whether the details are open")

        onEdt { button.doClick(0) }
        assertFalse(button.isSelected)
        assertEquals(collapsed, button.icon, "closing restores the collapsed icon")
    }

    @Test
    fun `details is a real focusable control that is not colour only`() {
        val button = detailsView().detailsButtonForTest()
        assertTrue(button.isFocusable, "reachable from the keyboard")
        assertTrue(button.isEnabled)
        assertEquals(SwingConstants.LEADING, button.horizontalTextPosition, "text first, then the chevron, in either direction")
        assertTrue(button.actionListeners.isNotEmpty(), "it is a native action, not a mouse listener")
        assertTrue(button.mouseListeners.none { it.javaClass.name.contains("qtranslate") }, "no hand-rolled mouse handling")
    }

    @Test
    fun `details keeps working in a right to left interface`() {
        val button = detailsView(ComponentOrientation.RIGHT_TO_LEFT).detailsButtonForTest()
        assertEquals("Details", button.text)
        assertNotNull(button.icon)
        assertEquals(SwingConstants.LEADING, button.horizontalTextPosition, "LEADING is logical, so it mirrors by itself")
        assertFalse(button.componentOrientation.isLeftToRight)
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

    // --- Primary selector --------------------------------------------------------------------

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
    fun `the primary selector is keyboard reachable and opens with the button's own action`() {
        val selector = textSelector()
        assertTrue(selector.isFocusable)
        assertTrue(selector.isEnabled)
        assertTrue(selector.actionListeners.isNotEmpty(), "Space and Enter reach the popup through the button action")
        assertTrue(selector.mouseListeners.none { it.javaClass.name.contains("qtranslate") })
    }

    @Test
    fun `the primary selector says what it does`() {
        assertEquals("Change primary translator", textSelector().toolTipText)
        val unlabelled = TranslatorPopupButton(blindIconManager(), {})
        onEdt { unlabelled.render(selectorState()) }
        assertEquals("Google Translate", unlabelled.toolTipText, "without a label the tooltip is still the provider")
    }

    @Test
    fun `icon, name and chevron all belong to the one control`() {
        val selector = textSelector()
        assertNotNull(selector.icon)
        assertEquals("Google Translate", selector.text)
        // The chevron is reserved room inside the button's own margin, on the trailing side.
        assertTrue(selector.margin.right > selector.margin.left, "left to right: room for the chevron on the right")
        onEdt { selector.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT) }
        assertTrue(selector.margin.left > selector.margin.right, "right to left: the chevron moves to the left")
    }

    @Test
    fun `the selector sits at its own width beside the primary label, not stretched across the header`() {
        val selector = TranslatorPopupButton(blindIconManager(), {})
        val view = TranslationProviderView(null, selector)
        val state = TranslationProviderState(
            serviceId = "google", serviceName = "Google Translate", iconPath = null,
            role = ProviderRole.PRIMARY, presentation = ProviderPresentation.MAIN,
            status = ProviderStatus.SUCCESS, text = "result",
            loadingText = "Translating...", failureText = "Translation failed", copyLabel = "Copy",
            primaryLabel = "Primary", fontConfig = font, fallbackFontConfig = font,
            selectorState = selectorState()
        )
        onEdt { view.render(state); view.setSize(700, 200); layoutTree(view) }
        onEdt { layoutTree(view) }

        assertSame(selector, view.selectorHostForTest().components.single())
        assertTrue(selector.width in 1 until 400, "the clickable area is the identity, not the whole row (${selector.width})")
        assertEquals("Primary", view.primaryTagForTest().text, "the muted label stays beside it")
        assertTrue(view.primaryTagForTest().isVisible)
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    @Test
    fun `choosing another translator still promotes it, keeping the translator set`() {
        val main = java.io.File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/MainContentView.kt").readText()
        val selector = main.substringAfter("private val comparisonPrimarySelector = TranslatorPopupButton(")
            .substringBefore("private val languageSelectionBar")
        assertTrue("SettingsIntent.PromoteTranslatorToPrimary(serviceId)" in selector, "promotion, not plain selection")
        assertFalse("UpdateServiceInActivePreset" in selector, "the translator set logic is not duplicated here")
    }
}
