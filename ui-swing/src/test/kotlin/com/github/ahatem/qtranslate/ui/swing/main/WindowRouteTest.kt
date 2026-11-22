package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.FeatureHost
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.WindowFeature
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import com.github.ahatem.qtranslate.core.settings.data.effectivePaneLayout
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.ui.swing.main.history.TranslationHistoryBar
import com.github.ahatem.qtranslate.ui.swing.main.input.InputTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.languagebar.LanguagePairChip
import com.github.ahatem.qtranslate.ui.swing.main.languagebar.LanguageSelectionBar
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoard
import com.github.ahatem.qtranslate.ui.swing.main.output.OutputTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.selector.ProviderChip
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelector
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.StatusBar
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every window the layout model can describe is built for real, and every command is found where
 * [WindowLayout.requiredHosts] says it must be: in a visible chain to the window, inside the host
 * that geometry names. No feature may lose its route when a region moves or hides.
 */
class WindowRouteTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val iconManager = TestIcons.iconManager()

    private fun localizer(rtl: Boolean = false): LocalizationManager {
        val directory = Files.createTempDirectory("qtranslate-route").toFile()
        val localizer = LocalizationManager(directory, LanguageTomlParser(), logger)
        if (rtl) {
            // A synthetic right-to-left language: only its direction matters, every string falls
            // back to English.
            File(localizer.languagesDirectory, "ar-SA.toml").writeText(
                "[meta]\nname = \"Test\"\nnative_name = \"Test\"\nlocale = \"ar-SA\"\nrtl = true\n"
            )
            runBlocking { localizer.loadLanguage(LanguageCode("ar-SA")) }
            assertTrue(localizer.isRtl, "the synthetic language reads right to left")
        }
        return localizer
    }

    private val settingsWrites = mutableListOf<SettingsIntent>()

    private fun view(localizer: LocalizationManager): MainContentView = onEdt {
        MainContentView(
            iconManager = iconManager,
            localizer = localizer,
            dispatch = { _: MainIntent -> },
            dispatchSettings = { settingsWrites += it },
            onOpenSnippingTool = {},
            onOpenOcrSettings = {},
            onOpenDocumentTranslation = {},
            onNotificationsClicked = {},
            onConfigureService = {},
            onOpenServiceSettings = {},
            onEnsureLookupDockRoom = {},
            onOpenImageSource = {},
        )
    }

    /** Two usable translators, the second configured for comparison, so a board can be built. */
    private val eligibleState = MainState(
        availableServices = listOf(
            ServiceInfo("google", "Google", null, ServiceRole.TRANSLATOR),
            ServiceInfo("bing", "Bing", null, ServiceRole.TRANSLATOR),
        )
    )
    private val ineligibleState = MainState(
        availableServices = listOf(ServiceInfo("google", "Google", null, ServiceRole.TRANSLATOR))
    )

    private val base = Configuration.DEFAULT.copy(
        servicePresets = listOf(
            ServicePreset(
                id = "preset",
                name = "preset",
                selectedServices = mapOf(ServiceRole.TRANSLATOR to "google"),
                comparisonTranslatorIds = listOf("bing"),
            )
        ),
        activeServicePresetId = "preset",
    )

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun render(view: MainContentView, state: MainState, layout: WindowLayout) {
        val configuration = base.withWindowLayout(layout)
        onEdt { view.render(state, SettingsState(originalConfiguration = configuration, workingConfiguration = configuration)) }
        // The rebuild is queued with invokeLater; drain it, and anything it queues in turn.
        repeat(2) { onEdt { } }
    }

    private fun descendants(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(descendants(it)) }
    }

    private inline fun <reified T : Component> find(root: Component): T? =
        descendants(root).filterIsInstance<T>().firstOrNull()

    private fun named(root: Component, name: String): AbstractButton? =
        descendants(root).filterIsInstance<AbstractButton>().firstOrNull { it.name == name }

    /**
     * Whether [component] is shown inside [root]: mounted under it with every link of the chain
     * visible. Headless tests have no screen, so `isShowing` is always false; this is the part of
     * it that the window controls.
     */
    private fun visibleIn(component: Component, root: Component): Boolean {
        var current: Component? = component
        while (current != null) {
            if (!current.isVisible) return false
            if (current === root) return true
            current = current.parent
        }
        return false
    }

    /** The slot a mounted region component sits in, read from the structure SlotLayout builds. */
    private fun slotOf(view: MainContentView, component: Component): RegionSlot {
        val statusBar = view.statusBar
        val bottomBar = statusBar.parent as JComponent
        val root = bottomBar.parent as JComponent
        val content = (root.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as Container
        val top = (content.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)
        val bottom = (bottomBar.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)
        val input = find<InputTextPanel>(view)!!
        return when {
            top != null && SwingUtilities.isDescendingFrom(component, top) -> RegionSlot.TOP
            bottom != null && SwingUtilities.isDescendingFrom(component, bottom) -> RegionSlot.BOTTOM
            SwingUtilities.isDescendingFrom(component, content) &&
                !SwingUtilities.isDescendingFrom(component, input) -> RegionSlot.ABOVE_RESULTS
            else -> RegionSlot.HIDDEN
        }
    }

    /**
     * Whether [button] is the trailing component of the row that holds [history]: placed at
     * LINE_END of that row's own BorderLayout, so it ends the row whether or not other regions
     * share it.
     */
    private fun endsRowOf(button: Component, history: Component): Boolean {
        val row = button.parent as? Container ?: return false
        val border = row.layout as? BorderLayout ?: return false
        return border.getConstraints(button) == BorderLayout.LINE_END &&
            SwingUtilities.isDescendingFrom(history, row)
    }

    /** Every problem with the routes of [layout] as built in [view]; empty when all are right. */
    private fun routeProblems(view: MainContentView, layout: WindowLayout, effectivePanes: PaneLayout): List<String> {
        val problems = mutableListOf<String>()
        val hosts = layout.requiredHosts(effectivePanes)
        val input = find<InputTextPanel>(view) ?: return listOf("the input pane is not mounted")
        val strip = input.stripContainers
        fun inStrip(component: Component) = strip.any { SwingUtilities.isDescendingFrom(component, it) }

        fun regionComponent(region: WindowRegion): Component? = when (region) {
            WindowRegion.HISTORY -> find<TranslationHistoryBar>(view)
            WindowRegion.LANGUAGES -> find<LanguageSelectionBar>(view)
            WindowRegion.SERVICES -> find<TranslatorSelector>(view)
        }

        fun stripTargets(feature: WindowFeature): List<Component?> = when (feature) {
            WindowFeature.BACK_FORWARD -> listOf(named(input, "history_back"), named(input, "history_forward"))
            WindowFeature.IMAGE_AND_DOCUMENT ->
                listOf(named(input, "input_image_translate"), named(input, "input_document_translate"))
            WindowFeature.LANGUAGE_PAIR -> listOf(find<LanguagePairChip>(input))
            WindowFeature.TRANSLATE_ACTION -> listOf(named(input, "input_translate"))
            WindowFeature.SERVICE_SWITCH -> listOf(find<ProviderChip>(input))
            WindowFeature.NOTIFICATIONS -> listOf(view.statusBar.notificationButton)
        }

        hosts.forEach { (feature, host) ->
            when (host) {
                is FeatureHost.RegionRow -> {
                    val component = regionComponent(host.region)
                    when {
                        component == null || !visibleIn(component, view) ->
                            problems += "$feature: the ${host.region} row is not shown"
                        slotOf(view, component) != layout.slotOf(host.region) ->
                            problems += "$feature: the ${host.region} row is in ${slotOf(view, component)}, " +
                                "not ${layout.slotOf(host.region)}"
                    }
                }
                FeatureHost.InputStrip -> stripTargets(feature).forEachIndexed { index, target ->
                    // A command the strip has no room for is a menu item under its More button,
                    // which is just as visible a route.
                    val inMoreMenu = target is AbstractButton && target.name in input.overflowedActionIds &&
                        input.overflowMenu().components.any { it is JMenuItem && it.name == target.name }
                    val shown = target != null && visibleIn(target, view) && inStrip(target)
                    val folded = inMoreMenu && inStrip(target!!) && visibleIn(input.moreButton, view)
                    if (!shown && !folded) {
                        problems += "$feature: control $index is not shown in the input strip"
                    }
                }
                FeatureHost.ComparisonHeader -> {
                    val board = find<CompareBoard>(view)
                    if (board == null || !visibleIn(board, view)) problems += "$feature: the board header is not shown"
                }
                FeatureHost.StatusBar -> {
                    val button = view.statusBar.notificationButton
                    if (!visibleIn(button, view) || !SwingUtilities.isDescendingFrom(button, view.statusBar)) {
                        problems += "$feature: not shown in the status bar"
                    }
                }
                FeatureHost.HistoryRowEnd -> {
                    val history = find<TranslationHistoryBar>(view)
                    val button = view.statusBar.notificationButton
                    if (history == null || !visibleIn(button, view) || !endsRowOf(button, history)) {
                        problems += "$feature: not shown at the end of the history row"
                    }
                }
            }
        }

        // A stand-in that has nothing to stand in for must not linger in the strip.
        val pairChip = find<LanguagePairChip>(input)
        if (hosts[WindowFeature.LANGUAGE_PAIR] != FeatureHost.InputStrip && pairChip != null && visibleIn(pairChip, view)) {
            problems += "the language pair chip is shown while the language row is"
        }
        val providerChip = find<ProviderChip>(input)
        if (hosts[WindowFeature.SERVICE_SWITCH] != FeatureHost.InputStrip && providerChip != null && visibleIn(providerChip, view)) {
            problems += "the provider chip is shown while another route switches the service"
        }
        if (named(input, "history_back")?.let { visibleIn(it, view) } == true &&
            hosts[WindowFeature.BACK_FORWARD] != FeatureHost.InputStrip
        ) {
            problems += "back and forward are in the strip while the history row is shown"
        }
        val statusShown = visibleIn(view.statusBar, view)
        if (statusShown != layout.showStatusBar) problems += "the status bar is shown=$statusShown, wanted ${layout.showStatusBar}"
        return problems
    }

    /** Every window the model can describe. */
    private fun allLayouts(): List<WindowLayout> =
        PaneLayout.entries.flatMap { panes ->
            RegionSlot.entries.flatMap { history ->
                RegionSlot.entries.flatMap { languages ->
                    RegionSlot.entries.flatMap { services ->
                        ServiceWidget.entries.flatMap { widget ->
                            listOf(false, true).flatMap { share ->
                                listOf(true, false).map { status ->
                                    WindowLayout(
                                        panes = panes,
                                        history = history,
                                        languages = languages,
                                        services = services,
                                        serviceWidget = widget,
                                        shareRows = share,
                                        showStatusBar = status,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `every distinct geometry keeps every command on a visible route`() {
        val view = view(localizer())
        // Two layouts that differ only in a value that does not move anything (a strip's labels,
        // a widget that never shares a row) build the same window, so each geometry is built once.
        val geometries = allLayouts().distinctBy { layout ->
            val panes = layout.panes
            listOf(panes, layout.rowPlan(panes), layout.requiredHosts(panes), layout.showStatusBar, layout.serviceWidget)
        }
        val started = System.nanoTime()
        val failures = geometries.flatMap { layout ->
            render(view, eligibleState, layout)
            val effective = base.withWindowLayout(layout).effectivePaneLayout(eligibleState.availableTranslatorIds)
            assertEquals(layout.panes, effective, "the fixture makes comparison eligible")
            routeProblems(view, layout, effective).map { "$layout: $it" }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("WindowRouteTest: ${geometries.size} geometries of ${allLayouts().size} layouts built in $millis ms")
        if (failures.isNotEmpty()) fail("${failures.size} broken routes:\n" + failures.take(40).joinToString("\n"))
    }

    @Test
    fun `an ineligible comparison renders stacked and the preference is not rewritten`() {
        val view = view(localizer())
        val layout = WindowLayout(panes = PaneLayout.COMPARISON, history = RegionSlot.BOTTOM)
        settingsWrites.clear()
        render(view, ineligibleState, layout)

        val output = assertNotNull(find<OutputTextPanel>(view), "the stacked output pane is mounted")
        assertTrue(visibleIn(output, view))
        assertTrue(find<CompareBoard>(view).let { it == null || !visibleIn(it, view) }, "no board is drawn")
        assertTrue(visibleIn(assertNotNull(find<TranslatorSelector>(view)), view), "stacked panes draw the selector")
        assertEquals(emptyList<String>(), routeProblems(view, layout, PaneLayout.STACKED))
        assertTrue(settingsWrites.isEmpty(), "rendering never writes the layout back: $settingsWrites")
    }

    @Test
    fun `relocated back and forward arrows are mirrored in a right to left window`() {
        val back = iconManager.getIcon(Icons.NAV_BACK, 16, 16)
        val forward = iconManager.getIcon(Icons.NAV_FORWARD, 16, 16)
        assertFalse(back === forward, "the two arrows are distinct icons")

        val hidden = WindowLayout(history = RegionSlot.HIDDEN)
        val ltr = view(localizer())
        render(ltr, eligibleState, hidden)
        assertSame(back, named(ltr, "history_back")?.icon, "left to right, Back points back")
        assertSame(forward, named(ltr, "history_forward")?.icon)

        val rtl = view(localizer(rtl = true))
        render(rtl, eligibleState, hidden)
        assertSame(forward, named(rtl, "history_back")?.icon, "right to left, Back points the other way")
        assertSame(back, named(rtl, "history_forward")?.icon)
    }

    @Test
    fun `the notification button is the last component of a shared history row`() {
        listOf(false, true).forEach { rtl ->
            val view = view(localizer(rtl = rtl))
            val shared = WindowLayout(
                history = RegionSlot.TOP, languages = RegionSlot.TOP, shareRows = true, showStatusBar = false,
            )
            render(view, eligibleState, shared)
            val history = assertNotNull(find<TranslationHistoryBar>(view))
            val languages = assertNotNull(find<LanguageSelectionBar>(view))
            val button = view.statusBar.notificationButton

            val row = button.parent as Container
            assertSame(row, history.parent, "rtl=$rtl: the bell is in the row history and languages share")
            assertSame(row, languages.parent, "rtl=$rtl")
            val border = row.layout as BorderLayout
            assertEquals(BorderLayout.LINE_START, border.getConstraints(history), "rtl=$rtl")
            assertEquals(BorderLayout.CENTER, border.getConstraints(languages), "rtl=$rtl")
            assertEquals(BorderLayout.LINE_END, border.getConstraints(button), "rtl=$rtl: the bell ends the row")
            assertTrue(visibleIn(button, view))
            assertTrue(
                row.components.none { it !== button && border.getConstraints(it) == BorderLayout.LINE_END },
                "rtl=$rtl: nothing else competes for the end of the row",
            )
        }
    }

    @Test
    fun `the notification button comes back to the status bar when the bar returns`() {
        val view = view(localizer())
        render(view, eligibleState, WindowLayout(showStatusBar = false))
        val history = assertNotNull(find<TranslationHistoryBar>(view))
        assertTrue(endsRowOf(view.statusBar.notificationButton, history))

        render(view, eligibleState, WindowLayout(history = RegionSlot.HIDDEN, showStatusBar = false))
        assertTrue(find<InputTextPanel>(view)!!.stripContainers.any {
            SwingUtilities.isDescendingFrom(view.statusBar.notificationButton, it)
        })

        render(view, eligibleState, WindowLayout.DEFAULT)
        assertSame(
            view.statusBar,
            generateSequence(view.statusBar.notificationButton.parent) { it.parent }.filterIsInstance<StatusBar>().firstOrNull(),
        )
        assertSame(view.statusBar.notificationAnchor, view.statusBar.notificationButton, "the popover opens from the button")
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    /** [component]'s bounds in the coordinates of [pane]. */
    private fun boundsIn(pane: Component, component: Component): Rectangle =
        SwingUtilities.convertRectangle(component.parent, component.bounds, pane)

    @Test
    fun `with every region hidden a short input pane still routes every command and clips none`() {
        val hidden = WindowLayout(
            history = RegionSlot.HIDDEN, languages = RegionSlot.HIDDEN, services = RegionSlot.HIDDEN,
            showStatusBar = false,
        )
        listOf(false, true).forEach { rtl ->
            PaneLayout.entries.forEach { panes ->
                val layout = hidden.copy(panes = panes)
                var sawOverflow = false
                var sawFullStrip = false
                listOf(230, 330, 460, 560, 900).forEach { windowHeight ->
                    val view = view(localizer(rtl))
                    render(view, eligibleState, layout)
                    onEdt {
                        view.setSize(900, windowHeight)
                        layoutTree(view)
                    }
                    val input = find<InputTextPanel>(view)!!
                    val context = "rtl=$rtl panes=$panes window height=$windowHeight input height=${input.height}"

                    assertEquals(emptyList<String>(), routeProblems(view, layout, panes), context)

                    val pane = Rectangle(0, 0, input.width, input.height)
                    val bell = view.statusBar.notificationButton
                    val stripControls = descendants(input).filterIsInstance<AbstractButton>()
                        .filter { visibleIn(it, view) && inStrip(input, it) } + bell
                    stripControls.forEach {
                        assertTrue(pane.contains(boundsIn(input, it)), "$context: ${it.name ?: it.toolTipText} is clipped by the pane")
                    }
                    if (input.overflowedActionIds.isEmpty()) {
                        sawFullStrip = true
                        assertFalse(visibleIn(input.moreButton, view), "$context: no More button when everything fits")
                    } else {
                        sawOverflow = true
                        assertTrue(visibleIn(input.moreButton, view), "$context: the More button shows the folded actions")
                    }
                }
                assertTrue(sawOverflow, "rtl=$rtl panes=$panes: the smallest window forces an overflow")
                assertTrue(sawFullStrip, "rtl=$rtl panes=$panes: the tallest window needs none")
            }
        }
    }

    private fun inStrip(input: InputTextPanel, component: Component): Boolean =
        input.stripContainers.any { SwingUtilities.isDescendingFrom(component, it) }
}
