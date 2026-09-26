package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Final pre-merge controls for PR #292: single primary identity, chevron order,
 * inline failure state, Quick duplicate actions, tag tone, and action order.
 *
 * Structural assertions only — no pixel or screenshot comparisons.
 */
class CompareProviderControlsTest {
    private val font = FontConfig("Dialog", 14)

    private fun selectorState(id: String = "google", name: String = "Google Translate") =
        TranslatorSelectorState(
            availableTranslators = listOf(ServiceInfo(id, name, null, ServiceRole.TRANSLATOR)),
            selectedTranslatorId = id,
            isLoading = false
        )

    private fun primary(
        presentation: ProviderPresentation = ProviderPresentation.MAIN,
        status: ProviderStatus = ProviderStatus.SUCCESS,
        text: String = "primary result",
        isTtsPlaying: Boolean = false,
        selectorState: TranslatorSelectorState? = null
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
        selectorState = selectorState
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

    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, IconManager::class.java) as IconManager
    }

    private fun renderOnEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait(block)
        SwingUtilities.invokeAndWait { }
    }

    private fun descendants(component: Component): List<Component> =
        listOf(component) + if (component is Container) {
            component.components.flatMap(::descendants)
        } else emptyList()

    // 1. MAIN primary shows one provider icon/identity only.
    @Test
    fun `main primary with selector shows one identity owned by the selector`() {
        val selector = TranslatorPopupButton(blindIconManager(), {})
        val board = CompareBoard(primarySelector = selector, iconManager = null)
        renderOnEdt {
            board.render(CompareBoardState(primary = primary(selectorState = selectorState()), secondaries = emptyList()))
        }
        renderOnEdt { }
        var selectorVisible = false
        var providerIconVisible = false
        var providerNameVisible = false
        SwingUtilities.invokeAndWait {
            selectorVisible = selector.isVisible
            providerIconVisible = board.primaryProviderView.providerIconForTest().isVisible
            providerNameVisible = board.primaryProviderView.providerNameForTest().isVisible
        }
        assertTrue(selectorVisible, "primary selector must stay visible")
        assertFalse(providerIconVisible, "separate provider icon must not duplicate the selector identity")
        assertFalse(providerNameVisible, "separate provider name must not duplicate the selector identity")
        var buttonText: String? = null
        SwingUtilities.invokeAndWait { buttonText = selector.buttonForTest().text }
        assertEquals("Google Translate", buttonText)
    }

    // 2. Text-mode translator selector orders icon -> provider name -> chevron.
    @Test
    fun `text mode selector orders icon then name then chevron`() {
        val selector = TranslatorPopupButton(blindIconManager(), {})
        renderOnEdt {
            selector.textMode = true
            selector.render(selectorState())
        }
        lateinit var button: JButton
        lateinit var chevron: JLabel
        SwingUtilities.invokeAndWait {
            button = selector.buttonForTest()
            chevron = selector.chevronForTest()
        }
        assertNotNull(button.icon, "selector button must own the provider icon")
        assertEquals("Google Translate", button.text)
        assertEquals(SwingConstants.LEADING, button.horizontalAlignment)
        assertTrue(chevron.isVisible, "text-mode chevron must be visible")
        // Logical trailing position: CENTER button first, LINE_END chevron last,
        // so LTR reads icon -> name -> chevron and RTL mirrors without hard-coding sides.
        val layout = selector.layout as BorderLayout
        assertEquals(BorderLayout.CENTER, layout.getConstraints(button))
        assertEquals(BorderLayout.LINE_END, layout.getConstraints(chevron))
    }

    @Test
    fun `icon only selector keeps working with no trailing chevron`() {
        val selector = TranslatorPopupButton(blindIconManager(), {})
        renderOnEdt {
            selector.textMode = false
            selector.render(selectorState())
        }
        var buttonText: String? = "unset"
        var chevronVisible = true
        SwingUtilities.invokeAndWait {
            buttonText = selector.buttonForTest().text
            chevronVisible = selector.chevronForTest().isVisible
        }
        assertNull(buttonText, "icon-only mode must not show text")
        assertFalse(chevronVisible, "icon-only mode keeps its composite affordance, not the trailing chevron")
        assertNotNull(selector.buttonForTest().icon)
    }

    // 3. Provider FAILURE does not increase header height via a dialog-sized icon.
    @Test
    fun `failure state never uses a dialog sized icon`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/output/TranslationProviderView.kt").readText()
        assertFalse(source.contains("OptionPane.errorIcon"), "header must not use the dialog-scale OptionPane icon")

        val view = TranslationProviderView(null)
        renderOnEdt { view.render(primary(status = ProviderStatus.FAILURE, text = "")) }
        var icon: javax.swing.Icon? = null
        var failureText = ""
        SwingUtilities.invokeAndWait {
            icon = view.statusLabelForTest().icon
            failureText = view.statusLabelForTest().text
        }
        // Without an icon manager the failure is text-only: no icon can grow the header.
        assertNull(icon, "text-only failure without an icon manager keeps the header height stable")
        assertEquals("Translation failed", failureText)
        // With an icon manager the glyph must stay at the standard action size.
        assertTrue(
            source.contains("Icons.WARNING") && source.contains("UIScale.scale(16)"),
            "failure glyph must use the existing warning icon at the standard action size"
        )
    }

    // 4 + 5. QUICK primary provider header does not expose Copy or Listen.
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

    // 6. Quick top-level Copy/Listen remain available.
    @Test
    fun `quick top level copy and listen remain wired`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/quicktranslate/QuickTranslateDialog.kt").readText()
        assertTrue(source.contains("private val listenButton"), "top-level Quick Listen must remain")
        assertTrue(source.contains("private val copyButton"), "top-level Quick Copy must remain")
        assertTrue(source.contains("add(listenButton)"), "top-level Quick Listen must stay on the top bar")
        assertTrue(source.contains("add(copyButton)"), "top-level Quick Copy must stay on the top bar")
        assertTrue(source.contains("onCopy()"), "top-level Quick Copy must keep its existing behavior")
        assertTrue(source.contains("onListen()"), "top-level Quick Listen must keep its existing behavior")
    }

    // 7. Secondary Quick result still exposes Copy.
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

    // 8. MAIN primary retains Copy and Listen.
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

    // 9. Action order remains logical in LTR and RTL.
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

        // RTL mirrors through the logical layout: the relative order never changes.
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
    fun `primary tag uses muted foreground while the accent rail remains`() {
        val muted = java.awt.Color(123, 124, 125)
        val old = UIManager.get("Label.disabledForeground")
        UIManager.put("Label.disabledForeground", muted)
        try {
            val view = TranslationProviderView(null)
            renderOnEdt { view.render(primary(presentation = ProviderPresentation.MAIN)) }
            var tagColor: java.awt.Color? = null
            var tagText = ""
            SwingUtilities.invokeAndWait {
                tagColor = view.primaryTagForTest().foreground
                tagText = view.primaryTagForTest().text
            }
            assertEquals("Primary", tagText)
            assertEquals(muted, tagColor, "Primary tag must use the semantic muted foreground, not accent text")
            val source = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/output/TranslationProviderView.kt").readText()
            assertTrue(
                source.contains("rail.isVisible = state.role == ProviderRole.PRIMARY"),
                "the 2px primary rail remains the canonical accent marker"
            )
        } finally {
            UIManager.put("Label.disabledForeground", old)
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
