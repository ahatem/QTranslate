package com.github.ahatem.qtranslate.app.screenshots

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.app.AppDependencies
import com.github.ahatem.qtranslate.app.AppUiSetup
import com.github.ahatem.qtranslate.app.ConsoleLoggerFactory
import com.github.ahatem.qtranslate.app.buildDependencies
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.ui.swing.main.MainAppFrame
import com.github.ahatem.qtranslate.ui.swing.main.layout.MirroredSplitPane
import com.github.ahatem.qtranslate.ui.swing.settings.SettingsDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTree
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Regenerates the application screenshots.
 *
 * Every image is produced by driving the real [MainAppFrame] with the real plugins and real
 * translation requests, then painting the live window to a PNG. Screenshots assembled from
 * lookalike components drift from the shipped UI as soon as anything changes, and cannot show a
 * genuine translation at all.
 *
 * ### Scenes are configuration, not overrides
 * Each scene writes a [Configuration] and then starts the app against it, following the same order
 * as `Main.kt`: apply the configuration to the look and feel, load the interface language, build
 * the frame. Nothing is imposed on the window afterwards — no forced theme, no forced size, no
 * `pack()`. The window ends up the size and appearance the app itself chooses, which is the whole
 * point of shooting the real application. The one deliberate exception is the split panes: the
 * translation zones are balanced and the docked dictionary given its natural share before each
 * capture, so the set reads as arranged rather than default. The scenes themselves live in [Scenes].
 *
 * ### Scaling
 * The scenes set the app's own zoom, [Scenes.SCALE_PERCENT], through [Configuration.uiScale]: the
 * app scales its fonts from that, FlatLaf derives its size factor back out of the fonts, and icons,
 * insets and the window follow — the same path a 200% display takes, so the output is natively
 * high-DPI rather than an upscale. Nothing sets a JVM-wide scale and nothing multiplies by hand,
 * apart from mapping the authored window size to the device pixels the zoom implies.
 *
 * Needs a display and a network connection. Google translates and Google Dictionary looks up
 * without an API key, so a default configuration is enough for every result to be real. Scenes
 * deliberately avoid the AI services, which would need a key and would render as auth errors.
 *
 * Run with: `./gradlew captureScreenshots`
 */
fun main(args: Array<String>): Unit = runBlocking {
    val appData = File(args.getOrElse(0) { "build/screenshots/app-data" }).also(File::mkdirs)
    val outputDir = File(args.getOrElse(1) { "build/screenshots" }).also(File::mkdirs)

    val loggerFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.WARN)
    val logger = loggerFactory.getLogger("Screenshots")
    val settingsRepository = SettingsRepository(
        appData,
        Json { ignoreUnknownKeys = true; isLenient = true },
        loggerFactory.getLogger("ScreenshotSettings")
    )

    settingsRepository.updateConfiguration(Scenes.BASE)
    val deps = buildDependencies(appData, loggerFactory, settingsRepository, Scenes.BASE)
    deps.pluginManager.loadAndProcessPlugins()

    // Services arrive through a flow; wait for one rather than racing it.
    withTimeoutOrNull(20_000) {
        while (deps.mainStore.state.value.availableServices.isEmpty()) delay(100)
    } ?: logger.warn("No services became available; shots will show the empty state.")

    with(Shots(deps, outputDir, logger)) {
        mainWindow()
        layouts()
        dictionary()
        rightToLeft()
        quickTranslate()
        history()
        documentTranslation()
        settings()
        close()
    }

    println("screenshots written to ${outputDir.absolutePath}")
    kotlin.system.exitProcess(0)
}

/**
 * A window size in the units the app persists.
 *
 * `mainWindowSize` holds what a real user's window measured when they last closed it, so it is in
 * device pixels. The scenes are authored at 100%; the configured [Scenes.SCALE_PERCENT] zoom is
 * the conversion from those density-independent numbers, applied by hand here because the app
 * keeps a saved size as its own device pixels.
 */
internal fun windowSize(size: Pair<Int, Int>) =
    Size(size.first * Scenes.OUTPUT_SCALE, size.second * Scenes.OUTPUT_SCALE)

private class Shots(
    private val deps: AppDependencies,
    private val outputDir: File,
    private val logger: Logger,
) {
    private var frame: MainAppFrame? = null

    // ── main window ───────────────────────────────────────────────────────────

    suspend fun mainWindow() {
        start(Scenes.classic(Scenes.DARK))
        translate(LanguageCode("ar"), Scenes.PERISTALSIS)
        capture("main-dark")

        start(Scenes.classic(Scenes.LIGHT))
        translate(LanguageCode("fr"), Scenes.LIBRARY)
        capture("main-light")
    }

    // ── layouts ───────────────────────────────────────────────────────────────

    suspend fun layouts() {
        start(Scenes.sideBySide(Scenes.DARK))
        translate(LanguageCode("de"), Scenes.VACCINE)
        capture("layout-side-by-side-dark")

        start(Scenes.sideBySide(Scenes.LIGHT))
        translate(LanguageCode("es"), Scenes.PITCH)
        capture("layout-side-by-side-light")

        start(Scenes.compact(Scenes.LIGHT))
        translate(LanguageCode("fr"), Scenes.LIBRARY)
        showOutputTab()
        capture("layout-compact-light")

        start(Scenes.compact(Scenes.DARK))
        translate(LanguageCode("ar"), Scenes.PERISTALSIS)
        showOutputTab()
        capture("layout-compact-dark")

        // The hero: input, backward translation and the dictionary all at once. Backward
        // translation rather than Summary or Rewrite — those are AI-only, and without an API key
        // they would render as an authentication error.
        start(Scenes.hero(Scenes.DARK))
        translate(LanguageCode("ar"), Scenes.PITCH)
        openDictionary("peristalsis")
        capture("hero-dark")

        start(Scenes.hero(Scenes.LIGHT))
        translate(LanguageCode("fr"), Scenes.PITCH)
        openDictionary("threshold")
        capture("hero-light")
    }

    // ── dictionary ────────────────────────────────────────────────────────────

    suspend fun dictionary() {
        start(Scenes.classic(Scenes.DARK))
        translate(LanguageCode("fr"), Scenes.VACCINE)
        openDictionary("vial")
        capture("dictionary-docked-dark")

        start(Scenes.classic(Scenes.LIGHT))
        translate(LanguageCode("de"), Scenes.LIBRARY)
        openDictionary("holiday")
        capture("dictionary-docked-light")

        // The floating popup, which is what the Ctrl+D shortcut opens over other applications.
        start(Scenes.classic(Scenes.DARK))
        translate(LanguageCode("ar"), Scenes.PERISTALSIS)
        deps.mainStore.dispatch(MainIntent.ShowQuickDictionary("peristalsis"))
        delay(5_000)
        captureFloatingWindow("dictionary-quick-dark")
        deps.mainStore.dispatch(MainIntent.HideQuickDictionary)
        delay(500)
    }

    // ── right to left ─────────────────────────────────────────────────────────

    suspend fun rightToLeft() {
        start(Scenes.arabic("classic"))
        translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
        // The dictionary is the panel that mirrors, so this shot is also the check that it lands
        // on the leading side rather than staying pinned to the right.
        openDictionary("peristalsis")
        capture("rtl-dictionary")

        start(Scenes.arabic("classic"))
        translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
        capture("rtl-main")

        start(Scenes.arabic("compact"))
        translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
        showOutputTab()
        capture("rtl-compact")
    }

    // ── quick translate ───────────────────────────────────────────────────────

    suspend fun quickTranslate() {
        for (theme in listOf(Scenes.DARK to "dark", Scenes.LIGHT to "light")) {
            start(Scenes.classic(theme.first))
            // Without this the popup inherits the previous scene's target and renders EN → EN.
            deps.mainStore.dispatch(MainIntent.SelectTargetLanguage(LanguageCode("es")))
            delay(400)
            deps.mainStore.dispatch(MainIntent.ShowQuickTranslate(Scenes.SELECTION))
            delay(6_000)
            captureFloatingWindow("quick-translate-${theme.second}")
            deps.mainStore.dispatch(MainIntent.HideQuickTranslate)
            delay(500)
        }
    }

    // ── dialogs opened through the app's own actions ──────────────────────────

    suspend fun history() {
        start(Scenes.classic(Scenes.DARK))
        // Every earlier scene left its translations behind, so the table would otherwise be the
        // same few passages repeated down the page.
        deps.mainStore.dispatch(MainIntent.ClearHistory)
        delay(800)
        // A history table with one row in it says nothing; fill it the way a session would.
        for ((target, text) in Scenes.HISTORY_WARMUP) translate(target, text)
        invokeAppAction("SHOW_HISTORY")
        delay(1_500)
        captureFloatingWindow("history", size = Scenes.WINDOW)
    }

    suspend fun documentTranslation() {
        start(Scenes.classic(Scenes.LIGHT))
        invokeAppAction("TRANSLATE_DOCUMENT")
        delay(1_500)
        captureFloatingWindow("document-translation")
    }

    /**
     * Every page of the settings dialog.
     *
     * Built here rather than opened through the frame: the frame shows its own settings dialog
     * modally, which would block the event thread this harness drives.
     */
    suspend fun settings() {
        start(Scenes.classic(Scenes.DARK))
        capturePages(Scenes.DARK, "dark")

        start(Scenes.classic(Scenes.LIGHT))
        capturePages(Scenes.LIGHT, "light")
    }

    private suspend fun capturePages(theme: String, suffix: String) {
        val owner = requireFrame()
        lateinit var dialog: SettingsDialog
        onUi {
            dialog = SettingsDialog(
                owner = owner,
                settingsStore = deps.settingsStore,
                pluginManager = deps.pluginManager,
                iconManager = deps.iconManager,
                themeManager = deps.themeManager,
                localizationManager = deps.localizationManager,
                availableLanguages = { deps.mainStore.state.value.availableLanguages },
            ).apply {
                isModal = false // modal would block the event thread this harness drives
                setSize(owner.width, owner.height)
                setLocationRelativeTo(owner)
                isVisible = true
            }
        }
        delay(1_200)

        for ((row, name) in Scenes.SETTINGS_PAGES) {
            onUi { find<JTree>(dialog)?.setSelectionRow(row) }
            delay(900)
            paint("settings-$name-$suffix", dialog.rootPane)
        }
        onUi { dialog.dispose() }
        delay(400)
        // Selecting a theme row leaves the dialog's own preview applied; put the scene back.
        onUi { deps.themeManager.applyTheme(deps.themeManager.findThemeById(theme), animate = false) }
        delay(400)
    }

    suspend fun close() {
        onUi { frame?.isVisible = false }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    /**
     * Starts the app against [configuration], in the same order `Main.kt` does.
     *
     * A fresh frame per scene rather than reconfiguring the live one: theme, interface language
     * and window size are all read during startup, so a running window keeps whatever it was built
     * with. Rebuilding is also what makes the shot honest — it is the window a user with this
     * configuration would actually get.
     *
     * The previous frame is hidden rather than disposed. `MainAppFrame` treats `windowClosed` as
     * the application quitting and calls `exitProcess`, so disposing one would end the run.
     */
    private suspend fun start(configuration: Configuration) {
        deps.settingsStore.dispatch(SettingsIntent.ToggleSetting { configuration })
        delay(900)

        AppUiSetup.apply(configuration, deps.themeManager)
        runCatching { deps.localizationManager.loadLanguage(LanguageCode(configuration.interfaceLanguage)) }

        val previous = frame
        onUi {
            frame = MainAppFrame(
                mainStore = deps.mainStore,
                settingsStore = deps.settingsStore,
                iconManager = deps.iconManager,
                themeManager = deps.themeManager,
                localizer = deps.localizationManager,
                pluginManager = deps.pluginManager,
                notificationBus = deps.notificationBus,
                logger = logger
            )
        }
        // The frame finishes building itself in a queued invokeLater (pack, listeners, visibility).
        delay(2_500)
        onUi { previous?.isVisible = false }
        delay(400)
        balanceSplits(configuration.layoutPresetId)
        delay(500)
    }

    /**
     * Latest UI polish, applied after the frame is up and at its final size.
     *
     * The translation zones — the classic input/output split, the side-by-side columns — read
     * best evenly divided. Balance them so the panes settle where a composed shot would arrange
     * them instead of wherever the layout left them. The docked dictionary is left to the app,
     * which gives it the column width its first show sets up.
     */
    private suspend fun balanceSplits(layoutPresetId: String) {
        if (layoutPresetId == "compact") return
        onUi {
            val current = frame ?: return@onUi
            val panes = splitsOf(current.rootPane).filterIsInstance<MirroredSplitPane>()
            if (layoutPresetId == "side_by_side") {
                panes.filter { it.orientation == JSplitPane.HORIZONTAL_SPLIT }
                    .forEach { it.setLeadingProportion(0.5) }
            } else {
                panes.filter { it.orientation == JSplitPane.VERTICAL_SPLIT }
                    .lastOrNull()?.setLeadingProportion(0.5)
            }
        }
    }

    private fun requireFrame(): MainAppFrame = requireNotNull(frame) { "no frame; call start() first" }

    private suspend fun translate(target: LanguageCode, text: String) {
        deps.mainStore.dispatch(MainIntent.UpdateInputText(""))
        deps.mainStore.dispatch(MainIntent.SelectTargetLanguage(target))
        deps.mainStore.dispatch(MainIntent.UpdateInputText(text))
        delay(400)
        deps.mainStore.dispatch(MainIntent.Translate())

        withTimeoutOrNull(30_000) {
            while (deps.mainStore.state.value.translatedText.isBlank()) delay(150)
        } ?: logger.warn("translation did not arrive; capturing anyway")
        delay(1_400)
    }

    private suspend fun openDictionary(word: String) {
        if (!deps.mainStore.state.value.isDictionaryPanelVisible) {
            deps.mainStore.dispatch(MainIntent.ToggleDictionaryPanel)
            delay(600)
        }
        deps.mainStore.dispatch(MainIntent.LookupWord(word))
        // Long enough that the status bar has settled off "Looking up…".
        delay(5_000)
        // The dictionary opens at whatever the app last remembers; the shot needs the column the
        // same every time it appears, so pin its split once it has settled.
        onUi {
            splitsOf(requireFrame().rootPane).filterIsInstance<MirroredSplitPane>()
                .filter { it.orientation == JSplitPane.HORIZONTAL_SPLIT }
                .forEach { it.setLeadingProportion(Scenes.DICTIONARY_SPLIT) }
        }
        delay(500)
    }

    /** Compact stacks the panes into tabs; show Output so the shot has a translation in it. */
    private suspend fun showOutputTab() {
        onUi { find<JTabbedPane>(requireFrame().rootPane)?.selectedIndex = 1 }
        delay(700)
    }

    /**
     * Fires one of the frame's own local shortcut actions by name.
     *
     * The history and document dialogs are created inside private methods, so this goes through
     * the action the shortcut is bound to instead of reaching for the fields — the same code path
     * a user takes, and nothing in the app has to be opened up for it.
     */
    private suspend fun invokeAppAction(action: String) {
        onUi {
            val key = "localHotkey_$action"
            val handler = requireFrame().rootPane.actionMap.get(key)
            if (handler == null) logger.warn("no action registered for $key")
            else handler.actionPerformed(ActionEvent(requireFrame(), ActionEvent.ACTION_PERFORMED, key))
        }
        delay(800)
    }

    private suspend fun capture(name: String) = paintAfterLayout(name, requireFrame().rootPane)

    /**
     * Captures the window the app just opened over the frame, then closes it.
     *
     * Swing keeps zero-sized helper windows around for heavyweight popups and tooltips and reports
     * them as visible, so the largest one is taken rather than the first.
     */
    private suspend fun captureFloatingWindow(name: String, size: Pair<Int, Int>? = null) {
        // Polled rather than checked once: a popup that waits on a translation can take a moment
        // longer than the scene allowed, and a single look would silently drop the shot.
        val window = withTimeoutOrNull(15_000) {
            var found: Window? = null
            while (found == null) {
                found = Window.getWindows()
                    .filter { it !== frame && it.isVisible && it is RootPaneContainer }
                    .filter { it.width > 0 && it.height > 0 }
                    .maxByOrNull { it.width.toLong() * it.height }
                if (found == null) delay(250)
            }
            found
        }

        if (window == null) {
            logger.warn("$name: no window became visible; skipping")
            return
        }
        onUi {
            // Dialogs open under the pointer, so whatever control they land on paints its rollover
            // state into the shot. Moving them is enough; the size is left to the app unless the
            // scene asked for one.
            size?.let { window.size = Dimension(UIScale.scale(it.first), UIScale.scale(it.second)) }
            window.setLocation(40, 40)
            window.validate()
        }
        delay(900)
        paintAfterLayout(name, (window as RootPaneContainer).rootPane)
        onUi { window.dispose() }
        delay(400)
    }

    private suspend fun paintAfterLayout(name: String, content: JComponent) {
        // Painting straight from the component tree would capture anything still queued for layout
        // half-positioned. Force the pass and let the repaint drain first.
        onUi { content.revalidate(); content.repaint() }
        delay(700)
        paint(name, content)
    }

    private suspend fun paint(name: String, content: JComponent) = onUi {
        val image = BufferedImage(content.width, content.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        content.paint(g)
        g.dispose()
        val target = File(outputDir, "$name.png")
        ImageIO.write(image, "png", target)
        println("wrote ${target.name}  ${image.width}x${image.height}")
    }
}

/** First component of type [T] in [root]'s tree. Breadth-first, so shallower matches win. */
private inline fun <reified T : Component> find(root: Container): T? {
    val queue = ArrayDeque<Container>().apply { add(root) }
    while (queue.isNotEmpty()) {
        for (child in queue.removeFirst().components) {
            if (child is T) return child
            if (child is Container) queue.add(child)
        }
    }
    return null
}

/** Every split pane in [root]'s tree, breadth-first so outer panes come first. */
private fun splitsOf(root: Container): List<JSplitPane> {
    val found = mutableListOf<JSplitPane>()
    val queue = ArrayDeque<Container>().apply { add(root) }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (current is JSplitPane) found.add(current)
        for (child in current.components) if (child is Container) queue.add(child)
    }
    return found
}

private suspend fun onUi(block: () -> Unit) {
    val done = java.util.concurrent.CountDownLatch(1)
    SwingUtilities.invokeLater { try { block() } finally { done.countDown() } }
    while (done.count > 0L) delay(50)
}
