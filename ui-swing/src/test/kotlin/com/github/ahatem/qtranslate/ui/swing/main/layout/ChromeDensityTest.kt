package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.Density
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelector
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The window's density: it sets the spacing the chrome is built with and the margin of the chrome
 * buttons, and leaves everything the theme owns to the theme.
 */
class ChromeDensityTest {

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** A plain panel that records the density the window gave it. */
    private class Probe : JPanel(), DensityAware {
        override var density: Density = Density.COMFORTABLE
    }

    private class Window(val registry: ComponentRegistry, root: JComponent) {
        val content = (root.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as JComponent
    }

    private fun build(layout: WindowLayout): Window = onEdt {
        val leaves = List(8) { JPanel() }
        val registry = ComponentRegistry(
            historyBar = Probe(), inputPanel = leaves[1], languageBar = Probe(),
            outputPanel = leaves[3], compareBoard = leaves[4], extraOutputPanel = leaves[5],
            translatorSelector = Probe(), statusBar = leaves[7],
        )
        Window(registry, SlotLayout.arrange(registry, layout, layout.panes).rootComponent)
    }

    @Test
    fun `compact halves the window padding and the gaps between rows`() {
        val comfortable = UISpacing.chrome(Density.COMFORTABLE)
        val compact = UISpacing.chrome(Density.COMPACT)

        assertEquals(UISpacing.PADDING, comfortable.PADDING, "comfortable is the plain spacing")
        assertEquals(UISpacing.V_GAP, comfortable.V_GAP)
        assertTrue(compact.PADDING < comfortable.PADDING)
        assertTrue(compact.V_GAP < comfortable.V_GAP)

        Density.entries.forEach { density ->
            val spacing = UISpacing.chrome(density)
            val window = build(WindowLayout(density = density))
            onEdt {
                val insets = window.content.border.getBorderInsets(window.content)
                assertEquals(spacing.PADDING, insets.left, "$density: the content keeps the density's side padding")
                assertEquals(spacing.PADDING, insets.top, "$density: and the padding above")
                assertEquals(spacing.V_GAP, (window.content.layout as BorderLayout).vgap, "$density: row gap")
            }
        }
    }

    @Test
    fun `the window hands its density to the rows it arranges`() {
        Density.entries.forEach { density ->
            val window = build(WindowLayout(density = density))
            listOf(window.registry.historyBar, window.registry.languageBar, window.registry.translatorSelector).forEach {
                assertEquals(density, (it as Probe).density)
            }
        }
    }

    @Test
    fun `a density change rebuilds the window`() {
        val container = JPanel(BorderLayout())
        val leaves = List(8) { JPanel() }
        val manager = LayoutManager(
            ComponentRegistry(
                historyBar = leaves[0], inputPanel = leaves[1], languageBar = leaves[2],
                outputPanel = leaves[3], compareBoard = leaves[4], extraOutputPanel = leaves[5],
                translatorSelector = leaves[6], statusBar = leaves[7],
            ),
            container,
        )
        fun switchTo(layout: WindowLayout): Component? {
            SwingUtilities.invokeAndWait { manager.switchLayout(layout) }
            SwingUtilities.invokeAndWait { }
            SwingUtilities.invokeAndWait { }
            return container.components.firstOrNull()
        }

        val first = assertNotNull(switchTo(WindowLayout(panes = PaneLayout.SIDE_BY_SIDE, density = Density.COMFORTABLE)))
        val second = assertNotNull(switchTo(WindowLayout(panes = PaneLayout.SIDE_BY_SIDE, density = Density.COMPACT)))
        assertNotSame(first, second, "the compact density is built, not ignored")
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun tabs(root: Container): List<JToggleButton> =
        root.components.flatMap { listOf<Component>(it) + if (it is Container) tabs(it) else emptyList() }
            .filterIsInstance<JToggleButton>()

    /** Runs [block] with a theme that gives toolbar buttons a margin of its own, as a user theme can. */
    private fun withThemeToolbarMargin(block: () -> Unit) {
        val previous = UIManager.getLookAndFeel()
        try {
            FlatLaf.setGlobalExtraDefaults(mapOf("Button.toolbar.margin" to "6,6,6,6"))
            FlatLightLaf.setup()
            block()
        } finally {
            FlatLaf.setGlobalExtraDefaults(null)
            UIManager.setLookAndFeel(previous)
        }
    }

    @Test
    fun `compact packs the strip tabs closer and comfortable gives the theme margin back`() = withThemeToolbarMargin {
        val services = listOf("a", "b").map { ServiceInfo(it, "Service $it", iconPath = null, type = ServiceRole.TRANSLATOR) }
        val selector = onEdt { TranslatorSelector(TestIcons.iconManager(), { _, _ -> }) }
        fun show(selected: String) {
            onEdt {
                selector.render(
                    TranslatorSelectorState(
                        availableTranslators = services, selectedTranslatorId = selected, isLoading = false,
                        style = ServiceSelectorStyle.CLASSIC,
                    ),
                )
                selector.setSize(900, 40)
                layoutTree(selector)
            }
            onEdt { layoutTree(selector) }
        }
        show("a")
        fun tabHeight() = onEdt { tabs(selector).first().let { it.ui.getPreferredSize(it).height } }

        val comfortable = tabHeight()
        onEdt { selector.density = Density.COMPACT }
        val compact = tabHeight()
        assertTrue(compact < comfortable, "compact packs the tab closer than the theme does: $compact < $comfortable")

        // A rebuild keeps the density, and going back to comfortable gives the theme's margin back.
        show("b")
        assertEquals(compact, tabHeight())
        onEdt { selector.density = Density.COMFORTABLE }
        assertEquals(comfortable, tabHeight())
    }
}
