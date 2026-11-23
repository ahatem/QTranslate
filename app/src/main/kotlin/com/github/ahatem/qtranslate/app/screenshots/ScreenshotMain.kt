package com.github.ahatem.qtranslate.app.screenshots

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.app.AppDependencies
import com.github.ahatem.qtranslate.app.AppUiSetup
import com.github.ahatem.qtranslate.app.ConsoleLoggerFactory
import com.github.ahatem.qtranslate.app.buildDependencies
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.mvi.LookupTool
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.ocr.OcrServiceCatalog
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.SecondResultPlacement
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.ui.swing.main.MainAppFrame
import com.github.ahatem.qtranslate.ui.swing.imagesearch.ImageSearchPanel
import com.github.ahatem.qtranslate.ui.swing.document.DocumentTranslationDialog
import com.github.ahatem.qtranslate.ui.swing.main.history.TranslationHistoryBar
import com.github.ahatem.qtranslate.ui.swing.main.layout.MirroredSplitPane
import com.github.ahatem.qtranslate.ui.swing.main.layout.RegionRowMenus
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoard
import com.github.ahatem.qtranslate.ui.swing.settings.SettingsDialog
import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JSplitPane
import javax.swing.JScrollPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

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
 * `pack()`. Scenes author their window at whatever size that layout genuinely reads best at; the
 * window ends up the appearance the app itself chooses, which is the whole point of shooting the
 * real application. The one deliberate exception is the split panes: the translation zones are
 * balanced and the docked dictionary given its natural share before each capture, so the set
 * reads as arranged rather than default. The scenes themselves live in [Scenes].
 *
 * ### The composite canvas
 * The raw window paint is never the published image. [ScreenshotCompositor] lands every capture
 * on one shared 2000×1250 canvas — warm gradient matched to the scene's theme, the window drawn
 * square as the app paints it, soft shadow — so the gallery is uniform while the app inside the
 * shot stays at its own natural, flattering size. Set `QTRANSLATE_SCREENSHOT_RAW=1` to also write each uncomposited capture
 * next to the final image; useful when tuning scene sizes.
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
        if (System.getenv("QTRANSLATE_SCREENSHOT_SCENES") == "ocr-preview") {
            ocrPreview()
        } else if (System.getenv("QTRANSLATE_SCREENSHOT_SCENES") == "document") {
            documentTranslation()
        } else if (System.getenv("QTRANSLATE_SCREENSHOT_SCENES") == "selector") {
            selectorAudit()
            selectorDensityAudit()
        } else if (System.getenv("QTRANSLATE_SCREENSHOT_SCENES") == "compact-toolbar") {
            compactToolbar()
        } else if (System.getenv("QTRANSLATE_SCREENSHOT_SCENES") == "layout-slots") {
            layoutSlots()
        } else if (System.getenv("QTRANSLATE_SCREENSHOT_SCENES") == "settings-outline") {
            settingsOutline()
        } else if (System.getenv("QTRANSLATE_SCREENSHOT_SCENES") == "settings-matrix") {
            settingsMatrix()
        } else if (System.getenv("QTRANSLATE_SCREENSHOT_SCENES") == "settings-showcase") {
            // Two pages, one theme: for checking a change to one of them without re-rendering the
            // whole gallery, which is a couple of minutes of work for a single screenshot.
            settingsShowcase()
        } else if (System.getenv("QTRANSLATE_SCREENSHOT_SCENES") == "presentation") {
            mainWindow()
            layouts()
            selectorAudit()
            settingsDensityAudit()
            documentTranslation()
            settingsShowcase()
        } else {
            mainWindow()
            layouts()
            dictionary()
            rightToLeft()
            quickTranslate()
            history()
            documentTranslation()
            settings()
        }
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
    private var sceneScalePercent = Scenes.SCALE_PERCENT

    /** Whether the scene in flight uses a dark theme — picks the composite backdrop's gradient. */
    private var sceneIsDark = true

    private val ocrCatalog: OcrServiceCatalog by lazy {
        OcrServiceCatalog(
            activeServices = deps.pluginManager.activeServices,
            configuration = deps.settingsStore.state
                .map { it.workingConfiguration }
                .stateIn(deps.appScope, SharingStarted.Eagerly, deps.settingsStore.state.value.workingConfiguration),
            scope = deps.appScope,
            logger = logger,
        )
    }

    private companion object {
        /** How long one request for a floating window is given before the scene asks again. */
        const val FIRST_WAIT_MS = 15_000L
        const val RETRY_WAIT_MS = 10_000L

        /** Total requests per capture, so a scene with no popup at all still gives up. */
        const val MAX_ATTEMPTS = 3

        /** Attempts at a settings page that painted empty before the shot is published anyway. */
        const val PAGE_ATTEMPTS = 3

        /**
         * A popup is only worth capturing once it is actually a popup: a dialog that has been
         * shown but not yet packed reports a few pixels, and painting that gives a plate with a
         * speck on it. Device pixels, so the floor clears every real popup in the set.
         */
        const val MIN_POPUP_WIDTH = 200
        const val MIN_POPUP_HEIGHT = 100
    }

    // ── main window ───────────────────────────────────────────────────────────

    /** Isolated presentation fixture: sample text, translation and transient states are synthetic. */
    suspend fun ocrPreview() {
        for ((theme, scale, label) in listOf(Triple(Scenes.DARK, 100, "dark"), Triple(Scenes.LIGHT, 100, "light"), Triple(Scenes.DARK, 200, "hidpi"))) {
            val config = Scenes.classic(theme).copy(uiScale = scale)
            sceneIsDark = theme == Scenes.DARK
            sceneScalePercent = scale
            AppUiSetup.apply(config, deps.themeManager)
            runCatching { deps.localizationManager.loadLanguage(LanguageCode("en")) }
            lateinit var dialog: com.github.ahatem.qtranslate.ui.swing.ocr.OcrWorkspaceDialog
            onUi {
                dialog = com.github.ahatem.qtranslate.ui.swing.ocr.OcrWorkspaceDialog(
                    null, deps.mainStore, deps.settingsStore, deps.localizationManager,
                    deps.ocrExtractionUseCase, ocrCatalog, {}, {}, {}, {},
                ).apply { size = Dimension(900, 600); isVisible = true }
            }
            delay(1000)
            write("ocr-empty-$label", paintAfterLayout("ocr-empty-$label", dialog.rootPane))
            if (label == "hidpi") {
                onUi { dialog.size = Dimension(760, 460); dialog.validate() }
                write("ocr-empty-small-hidpi", paintAfterLayout("ocr-empty-small-hidpi", dialog.rootPane))
                onUi { dialog.size = Dimension(900, 600); dialog.validate() }
            }
            val sample = BufferedImage(500, 180, BufferedImage.TYPE_INT_RGB)
            sample.createGraphics().apply {
                color = java.awt.Color.WHITE; fillRect(0, 0, 500, 180)
                color = java.awt.Color.DARK_GRAY; font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 26)
                drawString("Welcome to QTranslate", 25, 60); drawString("Translate text from images", 25, 115); dispose()
            }
            val bytes = java.io.ByteArrayOutputStream().apply { ImageIO.write(sample, "png", this) }.toByteArray()
            onUi {
                dialog.openImage(com.github.ahatem.qtranslate.api.ocr.ImageData(bytes, "png", 500, 180), "Sample", false,
                    "Welcome to QTranslate\nTranslate text from images", listOf(
                        com.github.ahatem.qtranslate.api.ocr.OCRTextRegion("Welcome to QTranslate", 20, 32, 310, 68),
                        com.github.ahatem.qtranslate.api.ocr.OCRTextRegion("Translate text from images", 20, 87, 360, 122),
                    ))
            }
            delay(1000)
            onUi {
                val field = dialog.javaClass.getDeclaredField("translationArea").apply { isAccessible = true }
                (field.get(dialog) as javax.swing.JTextArea).text = "Bienvenue dans QTranslate\nTraduire le texte des images"
            }
            onUi {
                val field = dialog.javaClass.getDeclaredField("ocrTextArea").apply { isAccessible = true }
                (field.get(dialog) as javax.swing.JTextArea).caretPosition = 5
            }
            write("ocr-result-$label", paintAfterLayout("ocr-result-$label", dialog.rootPane))
            if (label == "dark") {
                val stateField = dialog.javaClass.getDeclaredField("lastOcrState").apply { isAccessible = true }
                val render = dialog.javaClass.getDeclaredMethod("renderOcr", com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceState::class.java).apply { isAccessible = true }
                lateinit var state: com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceState
                onUi {
                    state = stateField.get(dialog) as com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceState
                    render.invoke(dialog, state.copy(isRecognizing = true, recognizedText = "", regions = emptyList()))
                }
                write("ocr-running", paintAfterLayout("ocr-running", dialog.rootPane))
                onUi { render.invoke(dialog, state.copy(recognizedText = "", regions = emptyList(), error = com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceError.NoText)) }
                write("ocr-no-text", paintAfterLayout("ocr-no-text", dialog.rootPane))
                onUi { render.invoke(dialog, state) }
            }
            onUi { dialog.size = Dimension(760, 460); dialog.validate() }
            write("ocr-small-$label", paintAfterLayout("ocr-small-$label", dialog.rootPane))
            if (label == "dark") {
                onUi { buttonsOf(dialog).first { it.toolTipText == deps.localizationManager.getString("screen_capture.more_actions") }.doClick() }
                lateinit var popup: javax.swing.JPopupMenu
                onUi { popup = javax.swing.MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<javax.swing.JPopupMenu>().last() }
                write("ocr-overflow", paintAfterLayout("ocr-overflow", popup))
                onUi { javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath() }
            }
            if (label == "hidpi") {
                onUi {
                    val panes = descendants(dialog).filterIsInstance<JScrollPane>()
                    val textPane = panes.first { it.viewport.view is JPanel && (it.viewport.view as JPanel).layout is java.awt.GridLayout }
                    textPane.verticalScrollBar.value = textPane.verticalScrollBar.maximum
                }
                write("ocr-small-hidpi-scrolled", paintAfterLayout("ocr-small-hidpi-scrolled", dialog.rootPane))
            }
            onUi { dialog.dispose() }
        }
        AppUiSetup.apply(Scenes.classic(Scenes.DARK).copy(uiScale = 100), deps.themeManager)
        deps.localizationManager.loadLanguage(LanguageCode("ar-SA"))
        lateinit var rtlDialog: com.github.ahatem.qtranslate.ui.swing.ocr.OcrWorkspaceDialog
        onUi {
            rtlDialog = com.github.ahatem.qtranslate.ui.swing.ocr.OcrWorkspaceDialog(
                null, deps.mainStore, deps.settingsStore, deps.localizationManager,
                deps.ocrExtractionUseCase, ocrCatalog, {}, {}, {}, {},
            ).apply {
                applyComponentOrientation(java.awt.ComponentOrientation.RIGHT_TO_LEFT)
                size = Dimension(900, 600)
                isVisible = true
            }
        }
        write("ocr-rtl", paintAfterLayout("ocr-rtl", rtlDialog.rootPane))
        onUi { rtlDialog.dispose() }
        start(Scenes.classic(Scenes.DARK).copy(uiScale = 100))
        capture("ocr-main")
        onUi {
            val primary = buttonsOf(requireFrame()).first { it.text == "OCR" }
            val arrow = (primary.parent as Container).components.filterIsInstance<javax.swing.JButton>().first { it !== primary }
            arrow.doClick()
        }
        lateinit var popup: javax.swing.JPopupMenu
        onUi { popup = javax.swing.MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<javax.swing.JPopupMenu>().last() }
        write("ocr-main-menu", paintAfterLayout("ocr-main-menu", popup))
        onUi { javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath() }
    }

    suspend fun mainWindow() {
        for ((theme, suffix) in Scenes.THEMES) {
            start(Scenes.classic(theme))
            translate(LanguageCode("fr"), Scenes.LIBRARY)
            capture("main-$suffix")
        }
    }

    // ── layouts ───────────────────────────────────────────────────────────────

    suspend fun layouts() {
        for ((theme, suffix) in Scenes.THEMES) {
            start(Scenes.sideBySide(theme))
            translate(LanguageCode("de"), Scenes.VACCINE)
            capture("layout-side-by-side-$suffix")
        }

        // Below the breakpoint the same layout stacks its panes.
        start(Scenes.sideBySide(Scenes.DARK))
        resizeWindow(Scenes.NARROW_WINDOW)
        translate(LanguageCode("ar"), Scenes.PERISTALSIS)
        capture("layout-side-by-side-narrow-dark")

        // Classic in a window too narrow for anything but a stack, with a long right-to-left result.
        start(Scenes.classic(Scenes.DARK))
        resizeWindow(Scenes.NARROW_WINDOW)
        translate(LanguageCode("ar"), Scenes.PERISTALSIS)
        capture("layout-classic-narrow-arabic-dark")

        // The same Classic panes with one combined toolbar row instead of two.
        compactToolbar()

        // Use the bundled named translators and give the board a taller scene of its own.
        for ((theme, suffix) in Scenes.THEMES) {
            start(comparisonConfig(theme, listOf("Bing Translate", "DeepL", "Yandex Web")))
            translate(LanguageCode("fr"), Scenes.COMPARISON)
            awaitComparison()
            scrollComparisonToTop()
            capture("layout-comparison-$suffix")
        }

        start(comparisonConfig(Scenes.DARK, listOf("Bing Translate", "DeepL", "Yandex Web")))
        translate(LanguageCode("fr"), Scenes.COMPARISON)
        awaitComparison()
        scrollComparisonToTop()
        openDictionary("library")
        capture("layout-comparison-dock-dark")

        start(Scenes.classic(Scenes.DARK))
        translate(LanguageCode("fr"), Scenes.LIBRARY)
        openImages("library")
        capture("dock-images-dark")
        onUi {
            val panel = find<ImageSearchPanel>(requireFrame().rootPane)
            val queue = ArrayDeque<Container>()
            if (panel != null) queue.add(panel)
            val tiles = mutableListOf<Component>()
            while (queue.isNotEmpty()) {
                for (child in queue.removeFirst().components) {
                    if (child.cursor.type == Cursor.HAND_CURSOR) tiles.add(child)
                    if (child is Container) queue.add(child)
                }
            }
            val tile = tiles.getOrNull(1) ?: tiles.firstOrNull()
            tile?.dispatchEvent(MouseEvent(tile, MouseEvent.MOUSE_CLICKED, 0L, 0, 5, 5, 1, false))
        }
        delay(3_000)
        capture("dock-image-viewer-dark")

        // The hero: input, backward translation and the dictionary all at once. Backward
        // translation rather than Summary or Rewrite — those are AI-only, and without an API key
        // they would render as an authentication error.
        for ((theme, suffix) in Scenes.THEMES) {
            start(Scenes.hero(theme))
            translate(LanguageCode("fr"), Scenes.PERISTALSIS_HERO)
            openDictionary("peristalsis")
            scrollAllToTop()
            capture("hero-$suffix")
        }
    }

    /**
     * A curated list of window layouts, each in English light and Arabic dark.
     *
     * Not a sweep: the four cards and the two pane arrangements that are not cards, then one shot
     * for each placement they do not show, then each place the second result can sit. The
     * Arabic half proves the mirroring and the dark half is where contrast faults show, so one
     * pass covers both. Comparison scenes get two usable translators so the board is eligible.
     */
    suspend fun layoutSlots() {
        val hidden = RegionSlot.HIDDEN
        val scenes = listOf(
            "card-modern-classic" to Scenes.MODERN_CLASSIC_CARD,
            "card-classic" to Scenes.CLASSIC_CARD,
            "card-compact-row" to Scenes.COMPACT_ROW,
            "card-focus" to Scenes.FOCUS_CARD,
            "density-modern-compact" to Scenes.MODERN_CLASSIC_COMPACT,
            "density-classic-comfortable" to Scenes.CLASSIC_COMFORTABLE,
            "panes-side-by-side" to Scenes.SIDE_BY_SIDE,
            "panes-comparison" to Scenes.COMPARISON_BOARD,
            // History and services hidden: their commands, the provider chip and the notification
            // button all land in the input strip, and the language row stays where it is. The
            // language row never hides.
            "history-services-hidden" to WindowLayout(history = hidden, services = hidden, showStatusBar = false),
            "history-bottom" to WindowLayout(history = RegionSlot.BOTTOM),
            "stacked-languages-top" to WindowLayout(languages = RegionSlot.TOP),
            // History and languages share the top row; the service selector never shares, so it
            // sits on a row of its own beneath them.
            "services-top-own-row" to WindowLayout(
                history = RegionSlot.TOP,
                languages = RegionSlot.TOP,
                services = RegionSlot.TOP,
                shareRows = true,
            ),
            "side-by-side-languages-above-results" to WindowLayout(
                panes = PaneLayout.SIDE_BY_SIDE,
                languages = RegionSlot.ABOVE_RESULTS,
            ),
            "comparison-history-bottom" to WindowLayout(panes = PaneLayout.COMPARISON, history = RegionSlot.BOTTOM),
            "icon-strip" to WindowLayout(
                serviceWidget = ServiceWidget.STRIP,
                stripAppearance = ServiceSelectorAppearance.ICONS_ONLY,
            ),
            // The second result, a backward translation, in each place the panes let it sit.
            "stacked-below" to WindowLayout(secondResult = SecondResultPlacement.BELOW),
            "stacked-beside" to WindowLayout(secondResult = SecondResultPlacement.WITH_RESULT),
            "sbs-below" to WindowLayout(panes = PaneLayout.SIDE_BY_SIDE, secondResult = SecondResultPlacement.BELOW),
            "sbs-under-result" to WindowLayout(
                panes = PaneLayout.SIDE_BY_SIDE,
                secondResult = SecondResultPlacement.WITH_RESULT,
            ),
            "sbs-third-column" to WindowLayout(
                panes = PaneLayout.SIDE_BY_SIDE,
                secondResult = SecondResultPlacement.OWN_COLUMN,
            ),
            "comparison-below" to WindowLayout(panes = PaneLayout.COMPARISON, secondResult = SecondResultPlacement.BELOW),
        )
        val withSecondResult = setOf(
            "stacked-below", "stacked-beside", "sbs-below", "sbs-under-result", "sbs-third-column", "comparison-below",
        )
        val secondaries = listOf("Bing Translate", "DeepL", "Yandex Web")
        scenes.forEach { (name, layout) ->
            fun scene(theme: String, rtl: Boolean): Configuration {
                val base = Scenes.slots(layout, theme, rtl).let {
                    if (name in withSecondResult) it.copy(extraOutputType = ExtraOutputType.BackwardTranslate) else it
                }
                return if (layout.panes == PaneLayout.COMPARISON) comparisonConfig(theme, secondaries, base) else base
            }

            start(scene(Scenes.LIGHT, rtl = false))
            translate(LanguageCode("fr"), Scenes.LIBRARY)
            // A long board is scrolled to its top, so the primary header is whole in the frame.
            if (layout.panes == PaneLayout.COMPARISON) scrollComparisonToTop()
            capture("slots-$name-light")

            start(scene(Scenes.DARK, rtl = true))
            translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
            if (layout.panes == PaneLayout.COMPARISON) scrollComparisonToTop()
            capture("slots-$name-rtl-dark")
        }

        // The window arranges itself: the history row's own context menu, open over the window.
        start(Scenes.slots(WindowLayout.DEFAULT, Scenes.LIGHT, rtl = false))
        translate(LanguageCode("fr"), Scenes.LIBRARY)
        captureWithPopup("slots-region-menu-history-light", openHistoryRowMenu())
        closeHistoryRowMenu()

        start(Scenes.slots(WindowLayout.DEFAULT, Scenes.DARK, rtl = true))
        translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
        captureWithPopup("slots-region-menu-history-rtl-dark", openHistoryRowMenu())
        closeHistoryRowMenu()

        settingsOutline()
    }

    /**
     * The Layout page of Settings, which is an outline of the window: the default window with no
     * saved layouts, the same window with one saved layout that matches it (shown as current), and
     * a Focus window in Side by side with a second result in its own column, in Arabic and dark.
     */
    suspend fun settingsOutline() {
        val layout = listOf(7 to "layout")
        start(Scenes.settingsOutline(WindowLayout.DEFAULT, Scenes.LIGHT))
        capturePages(Scenes.LIGHT, "outline-default-light", layout, alsoScrolled = true)

        start(Scenes.settingsOutline(WindowLayout.DEFAULT, Scenes.LIGHT, withProfiles = true, secondResult = true))
        capturePages(Scenes.LIGHT, "outline-profiles-light", layout, alsoScrolled = true)

        val focusSideBySide = Scenes.FOCUS_CARD.copy(
            panes = PaneLayout.SIDE_BY_SIDE,
            secondResult = SecondResultPlacement.OWN_COLUMN,
        )
        start(Scenes.settingsOutline(focusSideBySide, Scenes.DARK, rtl = true, withProfiles = true, secondResult = true))
        capturePages(Scenes.DARK, "outline-focus-rtl-dark", layout, alsoScrolled = true)
    }

    private suspend fun closeHistoryRowMenu() {
        onUi { find<TranslationHistoryBar>(requireFrame().rootPane)?.componentPopupMenu?.isVisible = false }
    }

    /**
     * Opens the context menu of the history row, as a right-click on it would. The menu is a
     * lightweight popup inside the window, so the window's own paint includes it.
     */
    private suspend fun openHistoryRowMenu(): JPopupMenu {
        lateinit var opened: JPopupMenu
        onUi {
            val bar = requireNotNull(find<TranslationHistoryBar>(requireFrame().rootPane)) { "no history bar" }
            val popup = requireNotNull(bar.componentPopupMenu as? RegionRowMenus.RowPopup) { "the history row has no menu" }
            // Sized first so a right-to-left window can place the menu against its leading edge.
            popup.refresh()
            val margin = UIScale.scale(24)
            val x = if (bar.componentOrientation.isLeftToRight) margin else bar.width - popup.preferredSize.width - margin
            popup.show(bar, x, bar.height)
            opened = popup
        }
        delay(800)
        return opened
    }

    /**
     * The window as [capture] takes it, with [popup] painted over it where it sits on screen. A
     * popup that does not fit inside the window is its own heavyweight window, which painting the
     * window's root pane does not include.
     */
    private suspend fun captureWithPopup(name: String, popup: JPopupMenu) {
        val shot = paintAfterLayout(name, requireFrame().rootPane)
        onUi {
            val origin = requireFrame().rootPane.locationOnScreen
            val at = popup.locationOnScreen
            val g = captureGraphics(shot)
            g.translate(at.x - origin.x, at.y - origin.y)
            popup.paint(g)
            g.dispose()
        }
        write(name, shot)
    }

    /**
     * The Classic arrangement with a compact toolbar: the language controls and the history
     * controls share one row instead of taking one each.
     *
     * Part of the layout set, and selectable on its own with
     * `QTRANSLATE_SCREENSHOT_SCENES=compact-toolbar` when only this chrome is being checked.
     */
    suspend fun compactToolbar() {
        for ((theme, suffix) in Scenes.THEMES) {
            start(Scenes.classicCompact(theme))
            translate(LanguageCode("fr"), Scenes.LIBRARY)
            capture("layout-classic-compact-$suffix")
        }
        // One row is exactly where a mirrored layout goes wrong without anyone noticing: the
        // history controls have to move to the leading edge and Back/Forward swap their icons.
        start(Scenes.arabic(Scenes.COMPACT_ROW))
        translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
        capture("layout-classic-compact-rtl-dark")
    }

    suspend fun selectorAudit() {
        deps.mainStore.dispatch(MainIntent.CloseLookupDock)
        start(Scenes.classicSelector(Scenes.DARK))
        translate(LanguageCode("fr"), Scenes.SELECTION)
        capture("classic-selector-dark")
        resizeWindow(Scenes.NARROW_WINDOW)
        capture("classic-selector-narrow-dark")
    }

    suspend fun selectorDensityAudit() {
        start(Scenes.classicSelector(Scenes.DARK).copy(
            uiScale = 100,
            mainWindowSize = Size(Scenes.WINDOW.first, Scenes.WINDOW.second)
        ))
        translate(LanguageCode("fr"), Scenes.SELECTION)
        capture("classic-selector-wide-100-dark")
        resizeWindow(Scenes.NARROW_WINDOW)
        capture("classic-selector-narrow-100-dark")

        start(Scenes.arabic(Scenes.CLASSIC_SELECTOR))
        translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
        capture("classic-selector-rtl-dark")

        start(Scenes.classicSelector(Scenes.LIGHT))
        translate(LanguageCode("fr"), Scenes.SELECTION)
        capture("classic-selector-light")
    }

    suspend fun settingsDensityAudit() {
        val config = Scenes.classic(Scenes.DARK).copy(uiScale = 100, mainWindowSize = Size(1000, 650))
        start(config)
        capturePages(Scenes.DARK, "100", listOf(3 to "services"))
    }

    // ── dictionary ────────────────────────────────────────────────────────────

    suspend fun dictionary() {
        for ((theme, suffix) in Scenes.THEMES) {
            start(Scenes.classic(theme))
            translate(LanguageCode("fr"), Scenes.VACCINE)
            openDictionary("vial")
            capture("dictionary-docked-$suffix")
        }

        // The floating popup, which is what the Ctrl+D shortcut opens over other applications.
        for ((theme, suffix) in Scenes.THEMES) {
            start(Scenes.classic(theme))
            translate(LanguageCode("ar"), Scenes.PERISTALSIS)
            deps.mainStore.dispatch(MainIntent.ShowQuickDictionary("peristalsis"))
            delay(5_000)
            captureFloatingWindow(
                "dictionary-quick-$suffix",
                show = { deps.mainStore.dispatch(MainIntent.ShowQuickDictionary("peristalsis")) },
            )
            deps.mainStore.dispatch(MainIntent.HideQuickDictionary)
            delay(500)
        }
    }

    // ── right to left ─────────────────────────────────────────────────────────

    suspend fun rightToLeft() {
        for ((theme, suffix) in Scenes.THEMES) {
            start(Scenes.arabic(Scenes.CLASSIC, theme))
            translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
            // The dictionary is the panel that mirrors, so this shot is also the check that it
            // lands on the leading side rather than staying pinned to the right.
            openDictionary("peristalsis")
            capture("rtl-dictionary-$suffix")

            start(Scenes.arabic(Scenes.CLASSIC, theme))
            translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
            capture("rtl-main-$suffix")
        }

        start(Scenes.arabic(Scenes.SIDE_BY_SIDE))
        translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
        capture("rtl-side-by-side")

        start(Scenes.arabic(Scenes.SIDE_BY_SIDE))
        translate(LanguageCode("en"), Scenes.ARABIC_PERISTALSIS)
        openDictionary("peristalsis")
        capture("rtl-side-by-side-dock")
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
            captureFloatingWindow(
                "quick-translate-${theme.second}",
                show = { deps.mainStore.dispatch(MainIntent.ShowQuickTranslate(Scenes.SELECTION)) },
            )
            deps.mainStore.dispatch(MainIntent.HideQuickTranslate)
            delay(500)
        }
    }

    // ── dialogs opened through the app's own actions ──────────────────────────

    suspend fun history() {
        for ((theme, suffix) in Scenes.THEMES) {
            start(Scenes.classic(theme))
            // Every earlier scene left its translations behind, so the table would otherwise be
            // the same few passages repeated down the page.
            deps.mainStore.dispatch(MainIntent.ClearHistory)
            delay(800)
            // A history table with one row in it says nothing; fill it the way a session would.
            for ((target, text) in Scenes.HISTORY_WARMUP) translate(target, text)
            invokeAppAction("SHOW_HISTORY")
            delay(1_500)
            captureFloatingWindow(
                "history-$suffix",
                size = Scenes.WINDOW,
                show = { invokeAppAction("SHOW_HISTORY") },
            )
        }
    }

    suspend fun documentTranslation() {
        // The path is visible in the capture, so QTRANSLATE_SCREENSHOT_DOCS can point it at a
        // neutral folder; without it the default is the machine's public documents folder, which
        // reads as a real user path without leaking the harness's own directories.
        val sampleDir = System.getenv("QTRANSLATE_SCREENSHOT_DOCS")?.let(::File)?.also(File::mkdirs)
            ?: File(System.getenv("PUBLIC"), "Documents")
        val sample = File(sampleDir, "Research notes.txt").apply {
            writeText("Peristalsis moves food through the digestive tract by rhythmic contraction of smooth muscle.\n")
        }
        for ((theme, suffix) in Scenes.THEMES) {
            start(Scenes.classic(theme))
            // Opened through the app's own action, then handed the sample file. Kept as one lambda
            // so a capture that finds no window can repeat exactly the same request.
            val openSample: suspend () -> Unit = {
                invokeAppAction("TRANSLATE_DOCUMENT")
                onUi {
                    Window.getWindows().filterIsInstance<DocumentTranslationDialog>()
                        .firstOrNull { it.isVisible }?.openWith(sample)
                }
            }
            openSample()
            delay(1_500)
            captureFloatingWindow("document-translation-$suffix", show = openSample)
        }
    }

    /**
     * Every page of the settings dialog.
     *
     * Built here rather than opened through the frame: the frame shows its own settings dialog
     * modally, which would block the event thread this harness drives.
     */
    suspend fun settings() {
        start(settingsConfig(Scenes.DARK))
        capturePages(Scenes.DARK, "dark")

        start(settingsConfig(Scenes.LIGHT))
        capturePages(Scenes.LIGHT, "light")
    }

    suspend fun settingsShowcase() {
        start(settingsConfig(Scenes.DARK))
        capturePages(Scenes.DARK, "dark", listOf(3 to "services", 5 to "languages", 11 to "plugins"))
    }

    /**
     * The Settings matrix: every page at 100%, 150% and 200%, in English and in Arabic.
     *
     * The gallery captures each page once, at the app's 200% authoring zoom. That proves the pages
     * read well but says nothing about the boundaries — where a label is clipped at 150%, where a
     * control overlaps at 200%, or whether a right-to-left page mirrors its rows. This scene walks
     * the combinations that the review is supposed to cover, at one theme, because the geometry
     * question is the same in either.
     */
    suspend fun settingsMatrix() {
        for (percent in listOf(100, 150, 200)) {
            for ((language, tag) in listOf("en" to "en", "ar-SA" to "ar")) {
                start(
                    settingsConfig(Scenes.LIGHT).copy(
                        uiScale = percent,
                        interfaceLanguage = language,
                    )
                )
                capturePages(Scenes.LIGHT, "$tag-$percent")
            }
        }
    }

    private fun settingsConfig(theme: String): Configuration =
        comparisonConfig(theme, listOf("Bing Translate", "DeepL", "Yandex Web"))
            .withWindowLayout(Scenes.CLASSIC)
            .copy(mainWindowSize = windowSize(Scenes.WINDOW))

    /**
     * @param alsoScrolled also writes `<page>-lower`, the same page scrolled to its end, for a page
     * that is taller than the dialog.
     */
    private suspend fun capturePages(
        theme: String,
        suffix: String,
        pages: List<Pair<Int, String>> = Scenes.SETTINGS_PAGES,
        alsoScrolled: Boolean = false,
    ) {
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
                ocrServiceCatalog = ocrCatalog,
                availableLanguages = { deps.mainStore.state.value.availableLanguages },
            ).apply {
                isModal = false // modal would block the event thread this harness drives
                setSize(owner.width, owner.height)
                setLocationRelativeTo(owner)
                // The frame applies the interface's direction; a dialog built directly here has to
                // be told, or the Arabic captures would show Arabic words laid out left to right.
                applyComponentOrientation(
                    if (deps.localizationManager.isRtl) java.awt.ComponentOrientation.RIGHT_TO_LEFT
                    else java.awt.ComponentOrientation.LEFT_TO_RIGHT
                )
                isVisible = true
            }
        }
        delay(1_200)

        for ((row, name) in pages) {
            var shot: BufferedImage
            var attempt = 1
            while (true) {
                selectPage(dialog, row, name)
                delay(900)
                shot = paintAfterLayout("settings-$name-$suffix", dialog.rootPane)
                // A page swap occasionally paints the card before its body exists, which yields a
                // perfectly valid-looking capture of an empty panel. Nothing throws and nothing is
                // logged, so the run used to publish it. Select the page again and repaint.
                if (!shot.paintsEmptyPage() || attempt >= PAGE_ATTEMPTS) break
                logger.warn("settings-$name-$suffix: painted an empty page; selecting it again")
                attempt++
                delay(600)
            }
            if (shot.paintsEmptyPage()) {
                logger.warn("settings-$name-$suffix: still empty after $attempt attempts")
            }
            write("settings-$name-$suffix", shot)
            if (alsoScrolled) {
                onUi {
                    // The sidebar tree has a scroll pane of its own; the page's is the one that holds a page.
                    val pane = allOf(dialog, JScrollPane::class.java).first { it.viewport.view is SettingsPanel }
                    val view = pane.viewport.view
                    pane.viewport.viewPosition = java.awt.Point(0, maxOf(0, view.height - pane.viewport.height))
                }
                delay(600)
                write("settings-$name-$suffix-lower", paintAfterLayout("settings-$name-$suffix-lower", dialog.rootPane))
            }
        }
        onUi { dialog.dispose() }
        delay(400)
        // Selecting a theme row leaves the dialog's own preview applied; put the scene back.
        onUi { deps.themeManager.applyTheme(deps.themeManager.findThemeById(theme), animate = false) }
        delay(400)
    }

    /**
     * Selects a settings page the way a user does.
     *
     * The Plugins page needs no extra step: plugin management moved to its own dialog, so the page
     * itself holds nothing but the explanation and the way in — which is what a capture should show.
     */
    private suspend fun selectPage(dialog: SettingsDialog, row: Int, name: String) {
        onUi {
            val tree = find<JTree>(dialog)
                ?: error("Settings page tree not found while capturing '$name' — dialog layout changed?")
            tree.setSelectionRow(row)
        }
    }

    /**
     * Whether a settings page painted nothing but its own background.
     *
     * The page cards are built on selection, and a paint that lands before the card's body is laid
     * out gives an empty panel that still looks like a plausible capture. Real pages draw labels,
     * fields and controls over at least 2% of the content area; an empty one leaves only borders,
     * which measure around a third of a percent.
     */
    private fun BufferedImage.paintsEmptyPage(): Boolean {
        val left = (width * 0.30).toInt()
        val right = (width * 0.97).toInt()
        val top = (height * 0.12).toInt()
        val bottom = (height * 0.88).toInt()
        val histogram = IntArray(256)
        var sampled = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val rgb = getRGB(x, y)
                val luma = ((rgb shr 16 and 0xFF) * 30 + (rgb shr 8 and 0xFF) * 59 + (rgb and 0xFF) * 11) / 100
                histogram[luma]++
                sampled++
                x += 3
            }
            y += 3
        }
        if (sampled == 0) return true
        var background = 0
        for (i in histogram.indices) if (histogram[i] > histogram[background]) background = i
        var ink = 0
        for (i in histogram.indices) {
            if (i < background - 24 || i > background + 24) ink += histogram[i]
        }
        return ink * 100 < sampled
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
        sceneScalePercent = configuration.uiScale
        sceneIsDark = deps.themeManager.findThemeById(configuration.themeId).isDark
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
        // Disposed, not merely hidden. Every frame subscribes to the same stores, so a hidden one
        // still answers a Show* dispatch by showing its own dialog — in the theme it was built
        // under. Disposing the frame cancels its scope (MainAppFrame does that on windowClosed),
        // which leaves the scene's own frame as the only one that can open a popup.
        onUi { previous?.dispose() }
        delay(400)
        applyAuthoredWindowSize(configuration)
        // The id of the arrangement the window actually built, not the stored preference.
        balanceSplits(configuration.windowLayout.panes.presetId)
        delay(500)
    }

    /**
     * Applies the scene's authored window size after startup.
     *
     * `pack()` sizes the window to the content's preferred size — for this layout, a wide row of
     * service controls dominates, so the saved size would be grown back and the shot would read
     * stretched no matter what the scene authored. Applying the size the way a user resize does —
     * above the frame's own minimum — is what lets a scene actually present the window at the
     * size it looks best at. The narrow shots' explicit [resizeWindow] calls stay: they re-apply
     * the same numbers and keep that intent visible at the scene.
     */
    private suspend fun applyAuthoredWindowSize(configuration: Configuration) {
        val saved = configuration.mainWindowSize ?: return
        onUi {
            val current = frame ?: return@onUi
            current.minimumSize = Dimension(0, 0)
            current.size = Dimension(saved.width, saved.height)
        }
        delay(800)
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
        onUi {
            val current = frame ?: return@onUi
            val panes = splitsOf(current.rootPane).filterIsInstance<MirroredSplitPane>()
            if (layoutPresetId == "side_by_side") {
                panes.filter { it.orientation == JSplitPane.HORIZONTAL_SPLIT }
                    .forEach { it.setLeadingProportion(0.5) }
            } else if (layoutPresetId == "comparison") {
                panes.filter { it.orientation == JSplitPane.VERTICAL_SPLIT }
                    .lastOrNull()?.let { split ->
                        // Screenshot scene only: the source is short, so ask for a small share of
                        // the height. The input still keeps the product's own minimum.
                        split.setLeadingProportion(0.12)
                    }
            } else {
                panes.filter { it.orientation == JSplitPane.VERTICAL_SPLIT }
                    .lastOrNull()?.setLeadingProportion(0.5)
            }
        }
    }

    /**
     * Resizes the running window, as a user dragging its edge would. The saved size cannot do this
     * for a narrow shot: the window's minimum size is applied on top of it.
     */
    private suspend fun resizeWindow(size: Pair<Int, Int>) {
        onUi {
            requireFrame().apply {
                minimumSize = Dimension(0, 0)
                setSize(
                    size.first * sceneScalePercent / 100,
                    size.second * sceneScalePercent / 100
                )
            }
        }
        delay(800)
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

    private suspend fun awaitComparison() {
        withTimeoutOrNull(35_000) {
            while (deps.mainStore.state.value.comparisonResults.any { it.status == ComparisonStatus.LOADING } ||
                deps.mainStore.state.value.comparisonResults.isEmpty()
            ) delay(200)
        } ?: logger.warn("comparison providers did not all settle before capture")
        deps.mainStore.state.value.comparisonResults.forEach {
            if (it.status != ComparisonStatus.SUCCESS) logger.warn("comparison ${it.serviceName}: ${it.status} ${it.errorMessage}")
        }
    }

    private suspend fun scrollComparisonToTop() {
        onUi {
            val board = find<CompareBoard>(requireFrame().rootPane)
            val scroll = board?.let { SwingUtilities.getAncestorOfClass(JScrollPane::class.java, it) as? JScrollPane }
            scroll?.verticalScrollBar?.value = 0
        }
        delay(500)
    }

    private suspend fun scrollAllToTop() {
        onUi {
            val queue = ArrayDeque<Container>().apply { add(requireFrame().rootPane) }
            while (queue.isNotEmpty()) {
                for (child in queue.removeFirst().components) {
                    if (child is JTextComponent && child.text.isNotEmpty()) child.caretPosition = 0
                    if (child is JScrollPane) child.verticalScrollBar.value = 0
                    if (child is Container) queue.add(child)
                }
            }
        }
        delay(500)
    }

    private suspend fun openDictionary(word: String) {
        deps.mainStore.dispatch(MainIntent.OpenLookupDock(LookupTool.DICTIONARY))
        delay(600)
        deps.mainStore.dispatch(MainIntent.LookupWord(word))
        // Long enough that the status bar has settled off "Looking up…".
        delay(5_000)
    }

    /** Opens the pictures tab of the lookup dock, as choosing Search Images in a wide window does. */
    private suspend fun openImages(term: String) {
        deps.mainStore.dispatch(MainIntent.OpenLookupDock(LookupTool.IMAGES))
        delay(600)
        deps.mainStore.dispatch(MainIntent.SearchImages(term))
        delay(6_000)
    }

    /**
     * A Comparison scene with Google as Primary and [secondaries] named after the loaded
     * translators, so the set is real and the ids are whatever this run's registry composed.
     */
    private fun comparisonConfig(
        theme: String,
        secondaries: List<String>,
        base: Configuration = Scenes.comparison(theme),
    ): Configuration {
        val translators = deps.mainStore.state.value.getAvailableServicesFor(ServiceRole.TRANSLATOR)
        fun idOf(name: String) = translators.firstOrNull { it.name.contains(name, ignoreCase = true) }?.id
        secondaries.filter { idOf(it) == null }.forEach { logger.warn("comparison provider unavailable: $it") }
        val preset = (base.getActivePreset() ?: ServicePreset.createDefault()).let { active ->
            active.copy(
                selectedServices = active.selectedServices +
                    (ServiceRole.TRANSLATOR to (idOf("Google") ?: active.selectedServices[ServiceRole.TRANSLATOR])),
                comparisonTranslatorIds = secondaries.mapNotNull(::idOf)
            )
        }
        return base.copy(servicePresets = listOf(preset), activeServicePresetId = preset.id)
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

    private suspend fun capture(name: String) {
        write(name, paintAfterLayout(name, requireFrame().rootPane))
    }

    /**
     * Captures the window the app just opened over the frame, then closes it.
     *
     * Swing keeps zero-sized helper windows around for heavyweight popups and tooltips and reports
     * them as visible, so the largest one is taken rather than the first.
     *
     * The request that opens the popup is not always answered: the first dispatch a scene sends
     * after building its frame sometimes produces no window at all, on every theme. A skipped
     * capture silently leaves the previous image in place, so scenes that can ask again pass
     * [show] and the capture retries instead of giving up on the first miss. Windows too small to
     * be the popup are ignored as well: a dialog that has been shown but not yet packed reports a
     * few pixels, and painting that gives a plate with a speck on it.
     */
    private suspend fun captureFloatingWindow(
        name: String,
        size: Pair<Int, Int>? = null,
        show: (suspend () -> Unit)? = null,
    ) {
        var window = awaitFloatingWindow(FIRST_WAIT_MS)
        var attempt = 1
        while (window == null && show != null && attempt < MAX_ATTEMPTS) {
            logger.warn("$name: no window became visible; repeating the request")
            onUi { closeFloatingWindows() }
            show()
            attempt++
            window = awaitFloatingWindow(RETRY_WAIT_MS)
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
        delay(1_200)
        write(name, paintAfterLayout(name, (window as RootPaneContainer).rootPane))
        onUi {
            window.dispose()
            // These popups carry no Swing owner. Earlier frames are disposed before a scene
            // starts, so nothing else should answer the next dispatch — but a dialog can linger
            // through a slow close, and one capture, one clean slate keeps the next shot honest.
            closeFloatingWindows()
        }
        delay(400)
    }

    /**
     * Polls for the floating window the scene just opened.
     *
     * Polled rather than checked once: a popup that waits on a translation can take a moment
     * longer than the scene allowed, and a single look would silently drop the shot.
     */
    private suspend fun awaitFloatingWindow(timeoutMs: Long): Window? = withTimeoutOrNull(timeoutMs) {
        var found: Window? = null
        while (found == null) {
            // Earlier frames are disposed before each scene, so in practice only this scene's
            // frame answers the dispatch. Owned windows are still preferred, and the largest is
            // the fallback for the popups that are deliberately ownerless.
            val visible = floatingWindows()
                .filter { it.width >= MIN_POPUP_WIDTH && it.height >= MIN_POPUP_HEIGHT }
            val owned = visible.filter { it.owner === frame }
            found = owned.maxByOrNull { it.width.toLong() * it.height }
                ?: visible.maxByOrNull { it.width.toLong() * it.height }
            if (found == null) delay(250)
        }
        found
    }

    /** Everything currently floating that this scene's frame does not account for. */
    private fun floatingWindows(): List<Window> =
        Window.getWindows().filter { it !== frame && it.isVisible && it is RootPaneContainer }

    /** One capture, one clean slate: every leftover popup is closed before the next scene runs. */
    private fun closeFloatingWindows() {
        floatingWindows().forEach { it.dispose() }
    }

    private suspend fun paintAfterLayout(name: String, content: JComponent): BufferedImage {
        // Painting straight from the component tree would capture anything still queued for layout
        // half-positioned. Force the pass and let the repaint drain first.
        onUi { content.revalidate(); content.repaint() }
        delay(700)
        return paint(name, content)
    }

    /** Paints the live component to an image; publishing it is [write]'s job. */
    private suspend fun paint(name: String, content: JComponent): BufferedImage {
        var image: BufferedImage? = null
        onUi {
            val captured = BufferedImage(content.width, content.height, BufferedImage.TYPE_INT_ARGB)
            val g = captureGraphics(captured)
            content.paint(g)
            g.dispose()
            image = captured
        }
        return image!!
    }

    /**
     * Lands the raw capture on the composite canvas ([ScreenshotCompositor]) and writes the
     * published PNG. With `QTRANSLATE_SCREENSHOT_RAW=1` the uncomposited capture is written
     * alongside it, which is how scene sizes get tuned without squinting through the gradient.
     */
    private fun write(name: String, shot: BufferedImage) {
        if (System.getenv("QTRANSLATE_SCREENSHOT_RAW") == "1") {
            ImageIO.write(shot, "png", File(outputDir, "$name-raw.png"))
        }
        ScreenshotCompositor.write(name, shot, sceneIsDark, outputDir)
    }
}

/**
 * The graphics a window is captured through, set up so text measures as it does on screen.
 *
 * A fresh image graphics starts with no platform text hints. That changed glyph advances by enough
 * to matter: at 13 pt, "Startup & Updates" measured 121 px through the label's own metrics and
 * 131 px through the capture graphics, so Swing's paint was truncated at the label's bounds. Every
 * Settings heading in the gallery lost its final character while the live window — painted through
 * the screen graphics — was complete, which a Robot capture of the same dialog confirmed.
 *
 * Copying `awt.font.desktophints` (LCD text and its contrast) makes the capture resolve text
 * exactly like the screen, which is what these images are supposed to show.
 */
internal fun captureGraphics(image: BufferedImage): Graphics2D {
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    @Suppress("UNCHECKED_CAST")
    val desktopHints = Toolkit.getDefaultToolkit()
        .getDesktopProperty("awt.font.desktophints") as? Map<Any?, Any?>
    desktopHints?.forEach { (key, value) ->
        if (key is RenderingHints.Key && value != null) g.setRenderingHint(key, value)
    }
    return g
}

/** First component of type [T] in [root]'s tree. Breadth-first, so shallower matches win. */
private fun <T : Component> allOf(root: Container, type: Class<T>): List<T> =
    root.components.flatMap { child ->
        (if (type.isInstance(child)) listOf(type.cast(child)) else emptyList()) +
            if (child is Container) allOf(child, type) else emptyList()
    }

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

private fun descendants(root: Container): List<Component> {
    val result = mutableListOf<Component>()
    val queue = ArrayDeque<Container>().apply { add(root) }
    while (queue.isNotEmpty()) {
        for (child in queue.removeFirst().components) {
            result.add(child)
            if (child is Container) queue.add(child)
        }
    }
    return result
}

private fun buttonsOf(root: Container) = descendants(root).filterIsInstance<javax.swing.JButton>()

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
