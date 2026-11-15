package com.github.ahatem.qtranslate.ui.swing.ocr

import com.formdev.flatlaf.ui.FlatLineBorder
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.ocr.OcrExtractionUseCase
import com.github.ahatem.qtranslate.core.ocr.OcrProviderInfo
import com.github.ahatem.qtranslate.core.ocr.OcrServiceCatalog
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceError
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceState
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceStore
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.main.layout.BorderSplitPaneUI
import com.github.ahatem.qtranslate.ui.swing.main.layout.MirroredSplitPane
import com.github.ahatem.qtranslate.ui.swing.main.layout.UISpacing
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.ThemeIcon
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContentClassifier
import com.github.ahatem.qtranslate.ui.swing.shared.util.connectedScreenBounds
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.util.isPositionReachable
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.CaretEvent
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The dedicated OCR utility window.
 *
 * It is intentionally compact: image and recognition on the left, editable text and translation
 * on the right. It owns OCR work through [OcrWorkspaceStore]; translation is dispatched to the
 * canonical [MainStore] and observed back, so this window never becomes a second translator.
 */
class OcrWorkspaceDialog(
    /**
     * Deliberately nullable and normally null: the main frame is hidden/iconified while a
     * capture is in flight, and an owned dialog can be suppressed or pull that owner back.
     * The workspace is an independent utility window; MainAppFrame disposes it explicitly.
     */
    owner: Frame?,
    private val mainStore: MainStore,
    private val settingsStore: SettingsStore,
    private val localizer: LocalizationManager,
    extraction: OcrExtractionUseCase,
    catalog: OcrServiceCatalog,
    private val onCaptureRequested: () -> Unit,
    private val onShowMainWindow: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onClosed: () -> Unit = {},
    /**
     * The application's icon manager, so the pane action columns are populated from the same
     * plugin-aware icons as the main window.
     */
    private val iconManager: IconManager,
    private val loggerFactory: LoggerFactory,
) : JDialog(owner, localizer.getString("ocr_workspace.title"), false) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("OcrWorkspaceDialog"))
    /**
     * Lifecycle for the translation-language fallback, which has to read the language list
     * before the shared translation workspace has loaded it.
     */
    private val languageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("OcrWorkspaceLanguages"))
    private val store = OcrWorkspaceStore(
        scope = scope,
        extraction = extraction,
        catalog = catalog,
        configuration = { settingsStore.state.value.workingConfiguration },
        ocrSettings = { settingsStore.state.value.workingConfiguration.ocr },
        translationSource = { mainStore.state.value.sourceLanguage },
    )

    private val imagePanel = OcrImagePanel()
    private val imageScroll = JScrollPane(imagePanel)
    private val imageCards = JPanel(CardLayout())
    private lateinit var imageControls: JPanel
    private val providerCombo = JComboBox<OcrProviderInfo>()
    private val languageCombo = JComboBox<LanguageCode>()
    private val recognizeButton = JButton()
    private val cancelRecognitionButton = JButton()
    private val statusLabel = JLabel(" ")
    private val infoLabel = JLabel(" ")
    private val busySpinner = com.github.ahatem.qtranslate.ui.swing.shared.widgets.LoadingSpinner().apply {
        isVisible = false
    }
    private val zoomLabel = JButton()
    private val annotationsCheck = JCheckBox()
    /**
     * The workspace's text surfaces are the application's, not Swing's: the same pane the main
     * window uses, so select-all, copy, cut, shaping, RTL and font fallback behave identically
     * here. Both are read-only; recognition output is created elsewhere.
     */
    private val ocrTextPane = AdvancedTextPane(
        onTextChanged = {},
        onTranslateRequest = { translateCurrentText(it) },
        onListenRequest = { listen(it, TextSource.Input) },
    )
    private val translationPane = AdvancedTextPane(
        onTextChanged = {},
        onTranslateRequest = { translateCurrentText(ocrTextPane.text.ifBlank { it }) },
        onListenRequest = { listen(it, TextSource.Output) },
    )
    private val recognitionSettings = JPanel(GridBagLayout())
    private val toolbarPanel = JPanel(BorderLayout())
    private val textBox = JPanel(BorderLayout(0, 4))
    private val sourceCombo = JComboBox<LanguageCode>()
    private val targetCombo = JComboBox<LanguageCode>()
    private val swapButton = JButton("⇄")
    private val translateButton = JButton()
    private val copyOcrButton = JButton()
    private val copyTranslationButton = JButton()
    private val listenOcrButton = JButton()
    private val listenTranslationButton = JButton()
    private lateinit var acquisitionBar: OcrAcquisitionBar

    private var updatingCombos = false
    private var lastOcrState: OcrWorkspaceState? = null
    private var expectedTranslationInput: String? = null
    // Written from the translation callback (Default dispatcher) via invokeLater
    // in recordTranslationRequest, so all dialog reads stay on the EDT.
    private var expectedRequestId: Long? = null
    /** OCR capture identity from the OpenOcrWorkspace event, for workspace translations. */
    private var currentOcrToken: Long? = null
    private var translatedTextForCurrent: String = ""
    private var autoTranslatePending = false
    private var regionRanges: List<IntRange> = emptyList()

    @Volatile
    private var closed = false

    @Volatile
    private var shutdownComplete = false
    private var closeNotified = false

    /** The last image actually shown, restored when a newer materialization fails. */
    private var lastGoodPanelImage: BufferedImage? = null

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(760, 460)
        size = Dimension(900, 600)
        buildUi()
        bindEvents()
        registerShortcuts()
        observeState()
        restoreBounds()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                shutdownNow()
                if (!closeNotified) {
                    closeNotified = true
                    persistBounds()
                    onClosed()
                }
            }
        })
    }

    /** Opens [image] and, when requested, starts recognition immediately. */
    fun openImage(
        image: ImageData,
        label: String,
        autoTranslate: Boolean,
        initialText: String? = null,
        initialRegions: List<OCRTextRegion> = emptyList(),
        initialLanguage: LanguageCode? = null,
        ocrToken: Long? = null,
    ) {
        currentOcrToken = ocrToken
        // Claim input ownership before any decoding. A second open/paste/capture immediately
        // supersedes this one even while its bytes are still being materialized.
        val token = claimImageInput() ?: return
        scope.launch {
            val decoded = OcrImageMaterializer.decode(image)
            withContext(Dispatchers.Swing) {
                if (decoded == null) {
                    failMaterialization(token, OcrWorkspaceError.ImageOpenFailed)
                    return@withContext
                }
                finishMaterialization(
                    token = token,
                    decoded = decoded,
                    image = image,
                    label = label,
                    autoTranslate = autoTranslate,
                    initialText = initialText,
                    initialRegions = initialRegions,
                    initialLanguage = initialLanguage,
                )
            }
        }
    }

    /** Opens a screenshot captured by this workspace, encoding it off the EDT. */
    fun openCapturedImage(image: BufferedImage, label: String) {
        currentOcrToken = null
        val token = claimImageInput() ?: return
        scope.launch {
            val materialized = OcrImageMaterializer.encode(image)
            withContext(Dispatchers.Swing) {
                if (materialized == null) {
                    failMaterialization(token, OcrWorkspaceError.ImageOpenFailed)
                    return@withContext
                }
                finishMaterialization(
                    token = token,
                    decoded = materialized.image,
                    image = materialized.data,
                    label = label,
                    autoTranslate = false,
                )
            }
        }
    }

    fun isWorkspaceClosed(): Boolean = closed

    fun closeWorkspace() {
        dispatchEvent(WindowEvent(this, WindowEvent.WINDOW_CLOSING))
    }

    override fun dispose() {
        // WINDOW_CLOSED is queued only after peer disposal. Stop reuse synchronously, including
        // direct dispose() and application-exit paths, before that delayed notification exists.
        shutdownNow()
        super.dispose()
    }

    /** Synchronous teardown for application exit, before AWT posts WINDOW_CLOSED. */
    fun shutdownNow() {
        if (shutdownComplete) return
        shutdownComplete = true
        closed = true
        busySpinner.isRunning = false
        store.close()
        scope.cancel()
    }

    /** Claims the visible input slot synchronously, before any file or clipboard work starts. */
    private fun claimImageInput(): Long? {
        if (closed) return null
        cancelWorkspaceTranslationIfRunning()
        imagePanel.currentImage()?.let { lastGoodPanelImage = it }
        val token = store.beginImageInput() ?: return null
        imagePanel.setImage(null)
        invalidateWorkspaceTranslation()
        autoTranslatePending = false
        return token
    }

    /** A new input supersedes the translation that belonged to the old one. Cancellation is
     * gated by request identity in MainStore: a newer manual request with the same text
     * is never cancelled here. */
    private fun cancelWorkspaceTranslationIfRunning() {
        val expected = expectedTranslationInput ?: return
        val main = mainStore.state.value
        if (main.isLoading && main.inputText == expected) {
            expectedRequestId?.let { mainStore.dispatch(MainIntent.CancelTranslationIfCurrent(it)) }
        }
    }

    private fun failMaterialization(token: Long, error: OcrWorkspaceError) {
        if (store.failImageInput(token, error)) {
            imagePanel.setImage(lastGoodPanelImage)
            lastGoodPanelImage?.let { imagePanel.fitToWindow(imageScroll.viewport.extentSize) }
            flashStatus(errorText(error), isError = true)
            updateInfo()
        }
    }

    /**
     * Publishes materialized bytes only when [token] still owns the input. The image panel, OCR
     * auto-run and optional initial translation all follow that same decision, so text and
     * geometry from image A can never attach to an image B shown later.
     */
    private fun finishMaterialization(
        token: Long,
        decoded: BufferedImage,
        image: ImageData,
        label: String,
        autoTranslate: Boolean,
        initialText: String? = null,
        initialRegions: List<OCRTextRegion> = emptyList(),
        initialLanguage: LanguageCode? = null,
    ) {
        val accepted = store.completeImageInput(
            token = token,
            image = image,
            label = label,
            autoRun = initialText == null,
            initialText = initialText,
            initialRegions = initialRegions,
            recognizedLanguage = initialLanguage,
        )
        if (!accepted) return

        imagePanel.setImage(decoded)
        lastGoodPanelImage = decoded
        autoTranslatePending = autoTranslate && initialText == null
        updateInfo()
        if (!isVisible) {
            isVisible = true
        }
        toFront()
        SwingUtilities.invokeLater {
            if (!store.isImageInputCurrent(token)) return@invokeLater
            imagePanel.fitToWindow(imageScroll.viewport.extentSize)
            zoomLabel.text = "${(imagePanel.zoomScale() * 100).toInt()}%"
            if (autoTranslate && initialText != null) {
                translateCurrentText(initialText)
                autoTranslatePending = false
            }
            ocrTextPane.requestFocusInWindow()
        }
    }

    private fun buildUi() {
        contentPane.layout = BorderLayout()
        contentPane.add(JPanel(BorderLayout()).apply {
            add(buildAcquisitionBar(), BorderLayout.CENTER)
            add(JSeparator(), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        val split = MirroredSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, buildImageSide(), buildTextSide()).apply {
            leadingResizeWeight = 0.46
            isMirrored = localizer.isRtl
            // Dictionary dock's border line, not the grip: image and text are separate
            // regions, so the seam reads as a border like the dock's.
            setUI(BorderSplitPaneUI())
            dividerSize = UISpacing.DOCK_DIVIDER_HIT_WIDTH
            clearBorder()
            setLeadingProportion(0.46)
        }
        contentPane.add(split, BorderLayout.CENTER)
        contentPane.add(JPanel(BorderLayout()).apply {
            add(JSeparator(), BorderLayout.NORTH)
            add(buildBottomBar(), BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    private fun buildAcquisitionBar(): JPanel {
        acquisitionBar = OcrAcquisitionBar(
            OcrAcquisitionBar.Labels(
                capture = localizer.getString("ocr_workspace.capture"),
                open = localizer.getString("ocr_workspace.open_image"),
                paste = localizer.getString("ocr_workspace.paste_image"),
                copy = localizer.getString("ocr_workspace.copy_image"),
                save = localizer.getString("ocr_workspace.save_image"),
                settings = localizer.getString("ocr_workspace.settings"),
                more = localizer.getString("screen_capture.more_actions"),
            ), onCaptureRequested, ::chooseImageFile, ::pasteImage, ::copyCurrentImage, ::saveCurrentImage, onOpenSettings,
        )
        return acquisitionBar
    }

    private fun buildImageSide(): JPanel {
        imagePanel.accessibleContext.accessibleName = localizer.getString("ocr_workspace.image_group")
        imagePanel.emptyMessage = localizer.getString("ocr_workspace.empty_image")
        imagePanel.accessibleContext.accessibleDescription = imagePanel.emptyMessage
        imageScroll.clearBorder()
        imageScroll.viewport.background = imagePanel.background
        annotationsCheck.text = localizer.getString("ocr_workspace.show_boxes")
        annotationsCheck.isOpaque = false
        annotationsCheck.isEnabled = false
        annotationsCheck.addActionListener { store.toggleAnnotations() }
        zoomLabel.toolTipText = localizer.getString("ocr_workspace.actual_size")
        zoomLabel.accessibleContext.accessibleName = zoomLabel.toolTipText
        fun zoomChanged() { zoomLabel.text = "${(imagePanel.zoomScale() * 100).toInt()}%" }
        zoomLabel.addActionListener { imagePanel.actualSize(); zoomChanged() }
        zoomLabel.putClientProperty("JButton.buttonType", "toolBarButton")
        // Fixed width so 67% / 100% / 200% do not make the floating bar jump
        // sideways every time the zoom changes.
        zoomLabel.preferredSize = Dimension(UIScale.scale(52), zoomLabel.preferredSize.height)
        zoomLabel.minimumSize = zoomLabel.preferredSize
        zoomLabel.maximumSize = zoomLabel.preferredSize
        zoomChanged()
        val more = compactButton("⋯", "screen_capture.more_actions") {}
        val fit = actionButton("ocr_workspace.fit") { imagePanel.fitToWindow(imageScroll.viewport.extentSize); zoomChanged() }.apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
        }
        more.addActionListener {
            JPopupMenu().apply {
                add(JMenuItem(fit.text).apply { addActionListener { fit.doClick() } })
                add(JCheckBoxMenuItem(annotationsCheck.text, annotationsCheck.isSelected).apply {
                    isEnabled = annotationsCheck.isEnabled
                    addActionListener { annotationsCheck.doClick() }
                })
            }.let { it.show(more, 0, -it.preferredSize.height) }
        }
        val zoomOut = compactButton("−", "ocr_workspace.zoom_out") { imagePanel.zoomOut(); zoomChanged() }
        val zoomIn = compactButton("+", "ocr_workspace.zoom_in") { imagePanel.zoomIn(); zoomChanged() }
        val zoom = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(2), UIScale.scale(1))).apply {
            add(zoomOut); add(zoomLabel); add(zoomIn); add(fit); add(annotationsCheck); add(more)
            // Floating card above the image: opaque so the image does not show through the
            // controls, rounded with the same arc the toolbar buttons use.
            isOpaque = true
            border = object : FlatLineBorder(Insets(1, 1, 1, 1), null, 1f, -1) {
                override fun getLineColor() = UIManager.getColor("Component.borderColor") ?: Color.GRAY
                override fun getArc(): Int = UIManager.getInt("Button.arc")
            }
            background = UIManager.getColor("Panel.background") ?: Color.DARK_GRAY
        }
        imageControls = zoom
        imageControls.isVisible = false
        val empty = object : JPanel(GridBagLayout()) {
            override fun updateUI() {
                super.updateUI()
                background = UIManager.getColor("TextArea.background") ?: UIManager.getColor("Panel.background")
            }
        }.apply {
            val c = GridBagConstraints().apply { gridx = 0; fill = GridBagConstraints.NONE; anchor = GridBagConstraints.CENTER }
            add(JLabel(IconSet.load(Icons.OCR, 28, 28)).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
            }, c)
            c.gridy = 1; c.insets = Insets(8, 12, 2, 12)
            add(JLabel(localizer.getString("ocr_workspace.empty_title")).apply {
                font = font.deriveFont(Font.BOLD)
                border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
            }, c)
            c.gridy = 2; c.insets = Insets(0, 12, 8, 12)
            add(JLabel(localizer.getString("ocr_workspace.empty_hint")).apply {
                border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
            }, c)
            c.gridy = 3; c.insets = Insets(0, 0, 0, 0)
            add(actionButton("ocr_workspace.capture") { onCaptureRequested() }, c)
        }
        imageCards.add(imageScroll, "image")
        imageCards.add(empty, "empty")
        (imageCards.layout as CardLayout).show(imageCards, "empty")

        // The zoom controls float in the bottom centre *over the image*.
        //
        // This is a plain panel with one layout manager and no layering. The previous version
        // was a JLayeredPane whose doLayout() also decided which controls were visible; a
        // layout manager must not change the components it is laying out, because the change
        // invalidates them and can re-enter the layout that made it. Visibility is now decided
        // in one place, on resize, and the layout only positions what is already visible.
        val overlay = object : JPanel(OverlayPlacement()) {
            /** True once the bar is wide enough for the full row of controls. */
            private var full = false

            init {
                isOpaque = false
                // The bar is marked rather than recognised by position: the layout has to know
                // it floats, and the add order is what Swing paints in.
                zoom.putClientProperty(OverlayPlacement.OVERLAY_KEY, true)
                add(imageCards)
                add(zoom)
                // Decided on resize, never from inside layout.
                addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent?) = applyWidth()
                })
            }

            override fun addNotify() {
                super.addNotify()
                // The first real width arrives with the first layout, not with construction,
                // so the decision is repeated once the panel exists on screen.
                javax.swing.SwingUtilities.invokeLater { applyWidth() }
            }

            /**
             * Chooses which controls fit, then lays the bar out once.
             *
             * Separate from [doLayout] on purpose: assigning `isVisible` invalidates the child,
             * and doing that while laying the parent out is what made the bar repaint in a loop
             * and stay blank until the pointer moved over it.
             */
            fun applyWidth() {
                val available = width - 2 * UIScale.scale(8)
                val essential = listOf(zoomOut, zoomLabel, zoomIn).sumOf { it.preferredSize.width }
                val fullWidth = essential + fit.preferredSize.width +
                    annotationsCheck.preferredSize.width + UIScale.scale(24)
                val fitWidth = essential + fit.preferredSize.width + more.preferredSize.width +
                    UIScale.scale(20)
                // Hysteresis: once overflowing, need extra room before going full again, so a
                // pixel of scrollbar or hover jitter cannot flip the bar between two shapes.
                full = if (full) {
                    available >= fullWidth - UIScale.scale(24)
                } else {
                    available >= fullWidth
                }
                val showFit = full || available >= fitWidth
                if (annotationsCheck.isVisible != full) annotationsCheck.isVisible = full
                if (fit.isVisible != showFit) fit.isVisible = showFit
                if (more.isVisible != full) more.isVisible = !full
                // The visibility just changed, so the bar's preferred size did too. Revalidate
                // now, outside any layout pass, and let the normal layout cycle position it.
                revalidate()
            }
        }
        return JPanel(BorderLayout()).apply {
            // The essential controls must fit on one line even when the divider is dragged.
            val essentialWidth = listOf(zoomOut, zoomLabel, zoomIn, more).sumOf { it.preferredSize.width } + UIScale.scale(12)
            minimumSize = Dimension(essentialWidth, 0)
            add(overlay, BorderLayout.CENTER)
        }
    }

    private fun buildTextSide(): JScrollPane {
        val sections = object : JPanel(GridLayout(2, 1, 0, 4)), Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight(): Boolean =
                (parent as? JViewport)?.height?.let { it >= preferredSize.height } == true
            override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = getFontMetrics(font).height
            override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = r.height
        }.apply {
            border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            add(buildOcrTextPanel()); add(buildTranslationPanel())
        }
        return JScrollPane(sections).apply {
            clearBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            minimumSize = Dimension(0, 0)
        }
    }

    /**
     * A section heading plus its actions, above the component they head.
     *
     * The heading is its own panel in `BorderLayout.NORTH`, never a `SOUTH` component of the
     * header: a `SOUTH` child contributes its full preferred height to the header, and inside an
     * equal-height grid that pushed the text pane down to a single visible line.
     */
    private fun sectionHeader(key: String, component: JComponent, vararg buttons: JButton): JPanel =
        JPanel(BorderLayout(0, 4)).apply {
            add(JPanel(BorderLayout(0, 2)).apply {
                isOpaque = false
                add(JLabel(localizer.getString(key)).apply {
                    font = font.deriveFont(Font.BOLD)
                    border = BorderFactory.createEmptyBorder(
                        UIScale.scale(2),
                        UIScale.scale(if (localizer.isRtl) 12 else 2),
                        UIScale.scale(2),
                        UIScale.scale(if (localizer.isRtl) 12 else 2),
                    )
                }, BorderLayout.LINE_START)
                add(
                    JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply {
                        isOpaque = false
                        buttons.forEach(::add)
                    },
                    BorderLayout.LINE_END,
                )
            }, BorderLayout.NORTH)
            add(component, BorderLayout.CENTER)
        }

    /**
     * A result pane: the application's own text surface, read-only, with the scroll pane that
     * gives it a viewport to wrap into.
     */
    private fun textSurface(pane: AdvancedTextPane, hint: String): JScrollPane =
        JScrollPane(pane.apply {
            isEditable = false
            hintText = hint
            accessibleContext.accessibleDescription = hint
            getContextMenuLabel = { key -> localizer.getString("main_window_editor_context_menu.$key") }
            updateFontsAndRescanDocument(
                newPrimary = font,
                newFallback = Font(Font.DIALOG, Font.PLAIN, font.size),
            )
        }).apply {
            isFocusable = false
            clearBorder()
            viewport.background = pane.background
            minimumSize = Dimension(0, 0)
        }

    private fun compactButton(glyph: String, key: String, action: () -> Unit): JButton = JButton(glyph).apply {
        toolTipText = localizer.getString(key)
        accessibleContext.accessibleName = toolTipText
        putClientProperty("JButton.buttonType", "toolBarButton")
        addActionListener { action() }
    }

    private fun configureSecondary(button: JButton, iconPath: String, key: String) {
        button.icon = IconSet.load(iconPath, 16, 16)
        button.toolTipText = localizer.getString(key)
        button.accessibleContext.accessibleName = button.toolTipText
        button.putClientProperty("JButton.buttonType", "toolBarButton")
    }

    /** Prevents a list control from collapsing to a sliver when the column is narrow. */
    private fun <T> widen(combo: JComboBox<T>, items: Int = 12): JComboBox<T> = combo.apply {
        minimumSize = Dimension(getFontMetrics(font).charWidth('W') * items, preferredSize.height)
    }

    private fun buildOcrTextPanel(): JPanel {
        providerCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component =
                super.getListCellRendererComponent(list, (value as? OcrProviderInfo)?.name ?: localizer.getString("settings_ocr.provider_automatic"), index, selected, focused)
        }
        languageCombo.renderer = languageRenderer()
        providerCombo.toolTipText = localizer.getString("ocr_workspace.provider")
        providerCombo.accessibleContext.accessibleName = providerCombo.toolTipText
        widen(providerCombo, items = 16)
        languageCombo.toolTipText = localizer.getString("ocr_workspace.language")
        languageCombo.accessibleContext.accessibleName = languageCombo.toolTipText
        widen(languageCombo, items = 16)
        statusLabel.labelFor = ocrTextPane
        configureSecondary(copyOcrButton, Icons.COPY, "ocr_workspace.copy_text")
        configureSecondary(listenOcrButton, Icons.SPEAK, "ocr_workspace.listen")
        recognizeButton.text = localizer.getString("ocr_workspace.recognize")
        cancelRecognitionButton.text = localizer.getString("ocr_workspace.cancel")
        cancelRecognitionButton.isVisible = false

        // Provider and recognition language are what the user adjusts per image, so they get a
        // full-width row of their own above the text instead of being squeezed under it. Each
        // control is labelled, because two unlabelled lists side by side do not say which is
        // which once their first item is long.
        recognitionSettings.apply {
            val c = GridBagConstraints().apply { fill = GridBagConstraints.HORIZONTAL }
            c.gridx = 0; c.gridy = 0; c.weightx = 1.0
            add(labelled("ocr_workspace.provider", providerCombo), c)
            c.gridx = 1; c.gridy = 0; c.weightx = 1.0
            c.insets = Insets(0, UIScale.scale(8), 0, 0)
            add(labelled("ocr_workspace.language", languageCombo), c)
            c.gridx = 2; c.gridy = 0; c.weightx = 0.0; c.fill = GridBagConstraints.NONE
            c.insets = Insets(UIScale.scale(14), UIScale.scale(8), 0, 0)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 2, 0)).apply {
                add(recognizeButton); add(cancelRecognitionButton)
            }, c)
        }
        toolbarPanel.add(recognitionSettings, BorderLayout.CENTER)

        textBox.apply {
            clearBorder()
            // Based on the current font, so the editor remains useful at every UI scale.
            preferredSize = Dimension(1, getFontMetrics(font).height * 7)
            add(toolbarPanel, BorderLayout.NORTH)
            add(textSurface(ocrTextPane, localizer.getString("ocr_workspace.no_text")), BorderLayout.CENTER)
        }
        return JPanel(BorderLayout(0, 6)).apply {
            add(sectionHeader("ocr_workspace.text_group", textBox, copyOcrButton, listenOcrButton), BorderLayout.CENTER)
        }
    }

    /** A list control under its own small label, sized to the row it sits in. */
    private fun labelled(key: String, control: JComponent) = JPanel(BorderLayout(0, 2)).apply {
        isOpaque = false
        add(JLabel(localizer.getString(key)).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = UIManager.getColor("Label.disabledForeground")
        }, BorderLayout.NORTH)
        add(control, BorderLayout.CENTER)
    }

    private fun buildTranslationPanel(): JPanel {
        sourceCombo.renderer = languageRenderer(); targetCombo.renderer = languageRenderer()
        widen(sourceCombo, items = 10); widen(targetCombo, items = 10)
        sourceCombo.toolTipText = localizer.getString("ocr_workspace.source_language")
        sourceCombo.accessibleContext.accessibleName = sourceCombo.toolTipText
        targetCombo.toolTipText = localizer.getString("ocr_workspace.target_language")
        targetCombo.accessibleContext.accessibleName = targetCombo.toolTipText
        configureSecondary(copyTranslationButton, Icons.COPY, "ocr_workspace.copy_translation")
        configureSecondary(listenTranslationButton, Icons.SPEAK, "ocr_workspace.listen")
        translateButton.text = localizer.getString("ocr_workspace.translate")
        swapButton.toolTipText = localizer.getString("main_window_language_bar.swap_languages_tooltip")
        swapButton.accessibleContext.accessibleName = swapButton.toolTipText
        swapButton.putClientProperty("JButton.buttonType", "toolBarButton")
        swapButton.isRequestFocusEnabled = false
        val direction = OcrTranslationControls(sourceCombo, targetCombo, translateButton, swapButton)
        val heading = JPanel(BorderLayout(0, 4)).apply {
            add(JSeparator(), BorderLayout.NORTH)
            add(sectionHeader("ocr_workspace.translation_group", direction, copyTranslationButton, listenTranslationButton), BorderLayout.CENTER)
        }
        return JPanel(BorderLayout(0, 2)).apply {
            preferredSize = Dimension(1, getFontMetrics(font).height * 7)
            add(heading, BorderLayout.NORTH)
            add(textSurface(translationPane, localizer.getString("ocr_workspace.translation_empty")), BorderLayout.CENTER)
        }
    }

    private fun buildBottomBar(): JPanel {
        val open = JButton(localizer.getString("ocr_workspace.open_in")).apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            icon = ThemeIcon("Table.descendingSortIcon")
            horizontalTextPosition = SwingConstants.LEADING
            isRequestFocusEnabled = false
        }
        open.addActionListener {
            val menu = JPopupMenu().apply {
                add(JMenuItem(localizer.getString("ocr_workspace.send_main")).apply { addActionListener { sendCurrentToMain() } })
                add(JMenuItem(localizer.getString("ocr_workspace.send_quick")).apply { addActionListener { sendCurrentToQuick() } })
            }
            // Anchor by the menu, not the button: left-aligning a wider menu to a
            // right-edge button pushed it off the dialog and Swing moved it
            // sideways to stay on screen, so it read as detached.
            val menuSize = menu.preferredSize
            menu.show(open, open.width - menuSize.width, -menuSize.height)
        }
        statusLabel.border = BorderFactory.createEmptyBorder(0, 10, 0, 10)
        infoLabel.foreground = UIManager.getColor("Label.disabledForeground")
        infoLabel.border = BorderFactory.createEmptyBorder(0, 10, 0, 0)
        return JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
            // GridBag anchors centre every child vertically; FlowLayout top-aligns
            // shorter labels, which read as off-centre next to the button strip.
            add(
                JPanel(GridBagLayout()).apply {
                    isOpaque = false
                    val c = GridBagConstraints().apply { anchor = GridBagConstraints.WEST; gridy = 0 }
                    c.gridx = 0; c.insets = Insets(0, 0, 0, 6); add(busySpinner, c)
                    c.gridx = 1; c.weightx = 1.0; add(statusLabel, c)
                },
                BorderLayout.CENTER,
            )
            add(
                JPanel(GridBagLayout()).apply {
                    isOpaque = false
                    val c = GridBagConstraints().apply { anchor = GridBagConstraints.EAST; gridy = 0 }
                    c.gridx = 0; c.insets = Insets(0, 0, 0, 8); add(infoLabel, c)
                    c.gridx = 1; add(open, c)
                },
                BorderLayout.LINE_END,
            )
        }
    }

    private fun bindEvents() {
        providerCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            store.selectProvider((providerCombo.selectedItem as? OcrProviderInfo)?.id)
        }
        languageCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            (languageCombo.selectedItem as? LanguageCode)?.let(store::selectLanguage)
        }
        recognizeButton.addActionListener { store.runOcr() }
        cancelRecognitionButton.addActionListener { store.cancelOcr() }
        copyOcrButton.addActionListener { copyText(ocrTextPane.copyTextOrAll()) }
        copyTranslationButton.addActionListener { copyText(translatedTextForCurrent) }
        listenOcrButton.addActionListener { listenOcrText() }
        listenTranslationButton.addActionListener { listen(translatedTextForCurrent, TextSource.Output) }
        translateButton.addActionListener { translateCurrentText(ocrTextPane.text) }
        swapButton.addActionListener { swapLanguages() }
        sourceCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            (sourceCombo.selectedItem as? LanguageCode)?.let {
                mainStore.dispatch(MainIntent.SelectSourceLanguage(it))
                retranslateIfVisible()
            }
        }
        targetCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            (targetCombo.selectedItem as? LanguageCode)?.let {
                mainStore.dispatch(MainIntent.SelectTargetLanguage(it))
                retranslateIfVisible()
            }
        }

        ocrTextPane.addCaretListener(::highlightRegionForCaret)
        imagePanel.onRegionClicked = ::selectRegion
        imagePanel.onZoomChanged = { zoomLabel.text = "${(it * 100).toInt()}%" }
        imagePanel.onFileDropped = ::openImageFile
        imageScroll.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                // A fitted image stays fitted when the window is resized; manual zoom is left
                // alone. The refit only revalidates when the scale actually moved, so this
                // listener cannot drive the viewport into resizing itself again.
                if (lastOcrState?.image != null) {
                    imagePanel.refitIfFitted(imageScroll.viewport.extentSize)
                    zoomLabel.text = "${(imagePanel.zoomScale() * 100).toInt()}%"
                }
            }
        })
    }

    private fun registerShortcuts() {
        fun bind(code: Int, modifiers: Int, action: () -> Unit) {
            rootPane.registerKeyboardAction(
                { action() },
                KeyStroke.getKeyStroke(code, modifiers),
                JComponent.WHEN_IN_FOCUSED_WINDOW,
            )
        }
        // Focus ownership is explicit: text editing never loses its chords
        // (notably Ctrl+V for pasting text) to destructive/modal actions.
        fun isEditingText(): Boolean = focusOwner is JTextArea

        fun bindUnlessEditing(code: Int, modifiers: Int, action: () -> Unit) {
            bind(code, modifiers) { if (!isEditingText()) action() }
        }

        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        // Esc: stop a running recognition first; close only when there is nothing to stop.
        bind(java.awt.event.KeyEvent.VK_ESCAPE, 0) {
            if (lastOcrState?.isRecognizing == true) store.cancelOcr() else closeWorkspace()
        }
        // Ctrl/Cmd+Enter mirrors the main window's TRANSLATE binding; safe while editing
        // (plain Enter still inserts a newline).
        bind(java.awt.event.KeyEvent.VK_ENTER, mask) { if (translateButton.isEnabled) translateCurrentText(ocrTextPane.text) }
        // Destructive or modal actions only when the user is not editing text, so edited
        // text is never overwritten by an accidental chord and Ctrl+V is never stolen.
        bindUnlessEditing(java.awt.event.KeyEvent.VK_R, mask) { if (lastOcrState?.canRunOcr == true) store.runOcr() }
        bindUnlessEditing(java.awt.event.KeyEvent.VK_O, mask) { if (!closed) chooseImageFile() }
        bindUnlessEditing(java.awt.event.KeyEvent.VK_S, mask) { if (imagePanel.currentImage() != null) saveCurrentImage() }
        bind(java.awt.event.KeyEvent.VK_0, mask) { imagePanel.fitToWindow(imageScroll.viewport.extentSize) }
        bind(java.awt.event.KeyEvent.VK_1, mask) { imagePanel.actualSize() }
        bind(java.awt.event.KeyEvent.VK_EQUALS, mask) { imagePanel.zoomIn() }
        bind(java.awt.event.KeyEvent.VK_MINUS, mask) { imagePanel.zoomOut() }
        rootPane.defaultButton = recognizeButton
    }

    private fun observeState() {
        scope.launch {
            store.state.collect { state ->
                withContext(Dispatchers.Swing) { renderOcr(state) }
            }
        }
        scope.launch(Dispatchers.Swing) {
            mainStore.state.collect { renderMain(it) }
        }
    }

    private fun renderOcr(state: OcrWorkspaceState) {
        val previous = lastOcrState
        lastOcrState = state
        (imageCards.layout as CardLayout).show(imageCards, if (state.hasImage || state.isLoadingImage) "image" else "empty")
        imageControls.isVisible = state.hasImage
        if (previous != null && previous.recognizedText != state.recognizedText) {
            // The translation workspace stores one source string. A new recognition result or
            // an edit makes any previous workspace translation describe different text.
            invalidateWorkspaceTranslation()
        }

        val busy = state.isRecognizing || state.isLoadingImage
        updatingCombos = true
        providerCombo.syncItems(state.providers, state.providers.firstOrNull { it.id == state.providerId })
        providerCombo.isEnabled = state.providers.isNotEmpty() && !busy
        val shownLanguages = if (state.language == LanguageCode.AUTO &&
            LanguageCode.AUTO !in state.languages
        ) {
            // Providers such as System OCR accept AUTO as their default even when their
            // advertised list only names concrete recognition languages.
            state.languages + LanguageCode.AUTO
        } else {
            state.languages
        }
        languageCombo.syncItems(shownLanguages, state.language.takeIf { it in shownLanguages })
        languageCombo.isEnabled = state.languages.isNotEmpty() && !busy
        updatingCombos = false

        recognizeButton.isEnabled = state.canRunOcr
        cancelRecognitionButton.isEnabled = state.isRecognizing
        cancelRecognitionButton.isVisible = state.isRecognizing
        recognizeButton.isVisible = !state.isRecognizing
        val translation = mainStore.state.value
        val translatingHere = expectedTranslationInput != null && translation.inputText == expectedTranslationInput && translation.isLoading
        translateButton.isEnabled = state.canTranslate && !state.isRecognizing && !translatingHere
        copyOcrButton.isEnabled = state.hasText
        listenOcrButton.isEnabled = state.hasText
        acquisitionBar.setImageAvailable(state.hasImage)
        copyTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
        listenTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
        annotationsCheck.isEnabled = state.regions.isNotEmpty()
        annotationsCheck.isSelected = state.showAnnotations && state.regions.isNotEmpty()
        // Accent whichever action is the natural next step; Enter fires it outside text areas.
        rootPane.defaultButton = if (state.hasText && !state.isRecognizing) translateButton else recognizeButton
        imagePanel.setRegions(state.regions)
        imagePanel.setAnnotationsVisible(state.showAnnotations && state.regions.isNotEmpty())

        busySpinner.isRunning = busy
        val error = state.error
        statusLabel.text = when {
            state.isLoadingImage -> localizer.getString("ocr_workspace.loading_image")
            state.isRecognizing -> localizer.getString("ocr_workspace.recognizing")
            error != null -> errorText(error)
            state.isEdited && state.hasText -> localizer.getString("ocr_workspace.edited")
            state.hasText || !state.hasImage -> localizer.getString("ocr_workspace.ready")
            else -> localizer.getString("ocr_workspace.no_text")
        }
        ocrTextPane.hintText = if (!state.hasText && error == null && !busy) {
            localizer.getString("ocr_workspace.no_text")
        } else statusLabel.text
        ocrTextPane.accessibleContext.accessibleDescription = ocrTextPane.hintText
        statusLabel.foreground = when {
            error != null -> errorColor()
            busy -> UIManager.getColor("Label.foreground")
            else -> UIManager.getColor("Label.disabledForeground") ?: UIManager.getColor("Label.foreground")
        }

        // Unreported write: the pane is read-only here and the recognition result is not
        // something the user authored through it, so it must not enter the undo history.
        if (ocrTextPane.text != state.recognizedText) {
            ocrTextPane.showText(state.recognizedText)
        }
        updateInfo()
        regionRanges = buildRegionRanges(state.recognizedText, state.regions)

        if (previous != null && previous.isRecognizing && !state.isRecognizing) {
            if (state.hasText) {
                ocrTextPane.requestFocusInWindow()
                if (autoTranslatePending) {
                    autoTranslatePending = false
                    translateCurrentText(state.recognizedText)
                }
            } else {
                autoTranslatePending = false
            }
        }
    }

    private fun renderMain(state: MainState) {
        val languages = state.availableLanguages.ifEmpty { fallbackTranslationLanguages() }
        updatingCombos = true
        sourceCombo.syncItems(languages, state.sourceLanguage)
        sourceCombo.isEnabled = state.availableLanguages.isNotEmpty()
        val targets = languages.filter { it != LanguageCode.AUTO }
        targetCombo.syncItems(targets, state.targetLanguage)
        targetCombo.isEnabled = state.availableLanguages.isNotEmpty() && targets.isNotEmpty()
        updatingCombos = false
        // Swapping with an auto-detected source would make the target "auto", which is invalid.
        swapButton.isEnabled = state.availableLanguages.isNotEmpty() &&
            state.sourceLanguage != LanguageCode.AUTO && targets.isNotEmpty()

        val expected = expectedTranslationInput
        val isThisRequest = expected != null && state.inputText == expected
        if (isThisRequest) {
            translatedTextForCurrent = if (!state.isLoading && !state.translationFailed) {
                state.translatedText
            } else {
                ""
            }
            translationPane.showText(when {
                state.isLoading -> localizer.getString("ocr_workspace.translating")
                state.translationFailed && state.translatedText.isBlank() ->
                    localizer.getString("ocr_workspace.translation_failed")
                else -> state.translatedText
            })
            translationPane.foreground = if (state.translationFailed) {
                errorColor()
            } else {
                UIManager.getColor("TextArea.foreground")
            }
            translateButton.isEnabled = ocrTextPane.text.isNotBlank() &&
                !state.isLoading &&
                lastOcrState?.isRecognizing != true
            copyTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
            listenTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
        } else if (state.inputText != expected) {
            // Another surface now owns the shared translation workspace. Do not display an
            // unrelated result as if it belonged to this OCR source.
            invalidateWorkspaceTranslation()
        }
    }

    /**
     * The list to *show* while the shared translator has not published its languages yet.
     *
     * A provider that declares [SupportedLanguages.All] or [SupportedLanguages.Dynamic] has no
     * list until a fetch lands, and the shared state is empty for that window. Two empty lists
     * read as broken controls. [LanguageCode.commonLanguages] is what the shared use case shows
     * for the same providers, so this is the same set, and it is display-only: the combos stay
     * disabled until [MainState.availableLanguages] is real.
     */
    private fun fallbackTranslationLanguages(): List<LanguageCode> =
        commonTranslationLanguages()

    private fun commonTranslationLanguages(): List<LanguageCode> = with(LanguageCode) {
        listOf(
            AUTO, ENGLISH, CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, HINDI, SPANISH, FRENCH,
            ARABIC, BENGALI, RUSSIAN, PORTUGUESE_BRAZIL, PORTUGUESE_PORTUGAL, INDONESIAN, URDU,
            GERMAN, JAPANESE, TURKISH, VIETNAMESE, KOREAN, ITALIAN, THAI, FARSI, POLISH,
            UKRAINIAN, DUTCH, GREEK, HEBREW, HUNGARIAN, CZECH, SWEDISH, ROMANIAN, DANISH,
            FINNISH, BULGARIAN, NORWEGIAN, SLOVAK, CATALAN, SERBIAN, CROATIAN, MALAY,
        )
    }

    private fun invalidateWorkspaceTranslation() {
        expectedTranslationInput = null
        expectedRequestId = null
        translatedTextForCurrent = ""
        translationPane.showText("")
        translationPane.foreground = UIManager.getColor("TextArea.foreground")
        copyTranslationButton.isEnabled = false
        listenTranslationButton.isEnabled = false
    }

    private fun translateCurrentText(text: String) {
        if (text.isBlank()) return
        mainStore.dispatch(MainIntent.UpdateInputText(text))
        val prepared = mainStore.state.value.inputText
        if (prepared.isBlank()) return
        expectedTranslationInput = prepared
        expectedRequestId = null
        translatedTextForCurrent = ""
        translationPane.showText(localizer.getString("ocr_workspace.translating"))
        translationPane.foreground = UIManager.getColor("TextArea.foreground")
        copyTranslationButton.isEnabled = false
        listenTranslationButton.isEnabled = false
        // ocrWorkspaceToken files this request under the capture that opened the
        // workspace (null for local file/paste/capture): a newer OCR workflow then
        // supersedes it, so it can never publish or auto-copy over newer work.
        // onTranslationClaimed captures its exact identity synchronously inside
        // dispatch (before this call returns) for the same reason, including the
        // local token-less path.
        mainStore.dispatch(
            MainIntent.Translate(
                text = prepared,
                onTranslationClaimed = { requestId -> recordTranslationRequest(prepared, requestId) },
                ocrWorkspaceToken = currentOcrToken,
            )
        )
    }

    private fun retranslateIfVisible() {
        val text = expectedTranslationInput ?: return
        translatedTextForCurrent = ""
        expectedRequestId = null
        mainStore.dispatch(
            MainIntent.Translate(
                text = text,
                onTranslationClaimed = { requestId -> recordTranslationRequest(text, requestId) },
                ocrWorkspaceToken = currentOcrToken,
            )
        )
    }

    /**
     * Records the claimed request identity only while it is still expected. Workspace
     * dispatch runs on the EDT and the store claims synchronously inside dispatch, so this
     * still lands before any later image/text replacement can run; off-EDT callers fall
     * back to the EDT without losing that ordering.
     */
    private fun recordTranslationRequest(prepared: String, requestId: Long) {
        if (SwingUtilities.isEventDispatchThread()) {
            if (!closed && expectedTranslationInput == prepared) {
                expectedRequestId = requestId
            }
        } else {
            SwingUtilities.invokeLater {
                if (!closed && expectedTranslationInput == prepared) {
                    expectedRequestId = requestId
                }
            }
        }
    }

    private fun swapLanguages() {
        val source = sourceCombo.selectedItem as? LanguageCode ?: return
        val target = targetCombo.selectedItem as? LanguageCode ?: return
        if (source == LanguageCode.AUTO) return
        mainStore.dispatch(MainIntent.SelectSourceLanguage(target))
        mainStore.dispatch(MainIntent.SelectTargetLanguage(source))
        retranslateIfVisible()
    }

    private fun sendCurrentToMain() {
        val text = ocrTextPane.text
        if (text.isBlank()) return
        if (translatedTextForCurrent.isNotBlank() &&
            expectedTranslationInput == mainStore.state.value.inputText
        ) {
            onShowMainWindow()
            return
        }
        translateCurrentText(text)
        onShowMainWindow()
    }

    private fun sendCurrentToQuick() {
        // Quick Translate always receives the recognized/edited source text. Sending
        // translatedTextForCurrent here would feed an English target into the same
        // Japanese->English pair and translate it a second time.
        val text = ocrTextPane.text
        if (text.isBlank()) return
        mainStore.dispatch(MainIntent.ShowQuickTranslate(text))
    }

    private fun selectRegion(index: Int) {
        val region = lastOcrState?.regions?.getOrNull(index) ?: return
        val range = regionRanges.getOrNull(index) ?: return
        imagePanel.setHighlightedRegion(index)
        ocrTextPane.requestFocusInWindow()
        ocrTextPane.select(range.first, range.last + 1)
    }

    private fun highlightRegionForCaret(event: CaretEvent) {
        val index = regionRanges.indexOfFirst { event.dot in it }
        if (index >= 0) imagePanel.setHighlightedRegion(index, reveal = true)
    }

    private fun chooseImageFile() {
        val chooser = JFileChooser().apply {
            dialogTitle = localizer.getString("ocr_workspace.open_image_title")
            fileFilter = FileNameExtensionFilter(
                localizer.getString("ocr_workspace.image_filter"),
                "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff", "webp",
            )
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            ocrTextPane.requestFocusInWindow()
            return
        }
        openImageFile(chooser.selectedFile)
    }

    /** Shared by the file chooser and drag & drop. */
    private fun openImageFile(file: File) {
        currentOcrToken = null
        val token = claimImageInput() ?: return
        scope.launch {
            val materialized = OcrImageMaterializer.readFile(file)
            withContext(Dispatchers.Swing) {
                if (materialized == null) {
                    failMaterialization(token, OcrWorkspaceError.ImageOpenFailed)
                } else {
                    finishMaterialization(
                        token = token,
                        decoded = materialized.image,
                        image = materialized.data,
                        label = file.name,
                        autoTranslate = false,
                    )
                }
            }
        }
    }

    private fun pasteImage() {
        // Clipboard access stays on the EDT; classification, file reads and decoding do not.
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val transferable = runCatching { clipboard.getContents(null) }.getOrNull()
        if (transferable == null) {
            flashStatus(localizer.getString("ocr_workspace.clipboard_empty"), isError = true)
            return
        }
        if (!DroppedContentClassifier.canAccept(transferable)) {
            // Plain text and other unsupported contents must not discard the current image.
            flashStatus(localizer.getString("ocr_workspace.clipboard_empty"), isError = true)
            return
        }
        currentOcrToken = null
        val token = claimImageInput() ?: return
        scope.launch {
            val materialized = OcrImageMaterializer.readClipboard(transferable)
            withContext(Dispatchers.Swing) {
                if (materialized == null) {
                    failMaterialization(token, OcrWorkspaceError.ClipboardEmpty)
                } else {
                    finishMaterialization(
                        token = token,
                        decoded = materialized.image,
                        image = materialized.data,
                        label = localizer.getString("ocr_workspace.clipboard_label"),
                        autoTranslate = false,
                    )
                }
            }
        }
    }

    private fun copyCurrentImage() {
        val image = imagePanel.currentImage() ?: return
        val transferable = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) {
                    throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                }
                return image
            }
        }
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null) }
        mainStore.dispatch(MainIntent.NotifyTextCopied)
    }

    private fun saveCurrentImage() {
        val image = imagePanel.currentImage() ?: return
        val chooser = JFileChooser().apply {
            dialogTitle = localizer.getString("ocr_workspace.save_image_title")
            fileFilter = FileNameExtensionFilter(localizer.getString("ocr_workspace.save_image_filter"), "png")
            selectedFile = File("ocr-image.png")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var file = chooser.selectedFile
        if (!file.name.endsWith(".png", ignoreCase = true)) file = File("${file.absolutePath}.png")
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { ImageIO.write(image, "PNG", file) }.getOrDefault(false)
            }
            withContext(Dispatchers.Swing) {
                if (saved) {
                    flashStatus(localizer.getString("ocr_workspace.image_saved"), isError = false)
                } else {
                    flashStatus(localizer.getString("ocr_workspace.image_save_failed"), isError = true)
                }
            }
        }
    }

    private fun copyText(text: String) {
        if (text.isBlank()) return
        text.copyToClipboard()
        mainStore.dispatch(MainIntent.NotifyTextCopied)
    }

    private fun listenOcrText() {
        if (ocrTextPane.text.isBlank()) return
        // The recognized text's own provenance, not the dropdown for the next OCR run and not
        // an unrelated main-window detection.
        val ocrLanguage = lastOcrState?.recognizedLanguage ?: LanguageCode.AUTO
        mainStore.dispatch(
            MainIntent.ListenToText(
                textSource = TextSource.Input,
                text = ocrTextPane.text,
                language = ocrLanguage,
            )
        )
    }

    private fun listen(text: String, source: TextSource) {
        if (text.isBlank()) return
        mainStore.dispatch(
            MainIntent.ListenToText(
                textSource = source,
                text = text,
                language = mainStore.state.value.targetLanguage,
            )
        )
    }

    private fun errorText(error: OcrWorkspaceError): String = when (error) {
        OcrWorkspaceError.NoImage -> localizer.getString("ocr_workspace.no_image")
        OcrWorkspaceError.NoProvider -> localizer.getString("status_bar.no_ocr_active")
        OcrWorkspaceError.NoText -> localizer.getString("status_bar.no_text_in_image")
        OcrWorkspaceError.Timeout -> localizer.getString("status_bar.ocr_timeout")
        OcrWorkspaceError.ImageOpenFailed -> localizer.getString("ocr_workspace.image_open_failed")
        OcrWorkspaceError.ClipboardEmpty -> localizer.getString("ocr_workspace.clipboard_empty")
        is OcrWorkspaceError.Failed -> localizer.getString("ocr_workspace.recognition_failed")
    }

    private fun updateInfo() {
        val dims = imagePanel.currentImage()?.let { "${it.width}\u00D7${it.height}" }
        infoLabel.text = dims ?: " "
    }

    private fun flashStatus(message: String, isError: Boolean) {
        statusLabel.text = message
        statusLabel.foreground = if (isError) errorColor() else UIManager.getColor("Label.foreground")
    }

    private fun errorColor(): Color = UIManager.getColor("Label.errorForeground") ?: Color.RED

    /** Replaces combo items only when they actually changed, so an open popup never flickers. */
    private fun <T> JComboBox<T>.syncItems(items: List<T>, selected: T?) {
        @Suppress("UNCHECKED_CAST")
        val m = model as DefaultComboBoxModel<T>
        val current = (0 until m.size).map(m::getElementAt)
        if (current != items) {
            m.removeAllElements()
            items.forEach(m::addElement)
        }
        if (selectedItem != selected) selectedItem = selected
    }

    private fun actionButton(key: String, onClick: () -> Unit): JButton =
        JButton(localizer.getString(key)).apply { addActionListener { onClick() } }

    private fun languageRenderer(): DefaultListCellRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
        ): Component = super.getListCellRendererComponent(
            list,
            (value as? LanguageCode)?.getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect")) ?: "",
            index, selected, focused,
        )
    }

    private fun buildRegionRanges(text: String, regions: List<OCRTextRegion>): List<IntRange> {
        var cursor = 0
        return regions.map { region ->
            val needle = region.text.takeIf { it.isNotBlank() } ?: return@map IntRange.EMPTY
            val found = text.indexOf(needle, cursor).takeIf { it >= 0 } ?: text.indexOf(needle)
            if (found < 0) {
                IntRange.EMPTY
            } else {
                cursor = found + needle.length
                found until (found + needle.length)
            }
        }
    }

    private fun restoreBounds() {
        val config = settingsStore.state.value.workingConfiguration
        val savedSize = config.ocrWorkspaceSize
        val savedPosition = config.ocrWorkspacePosition
        if (savedSize != null && savedSize.width > 0 && savedSize.height > 0) {
            size = Dimension(savedSize.width, savedSize.height)
        }
        if (savedPosition != null &&
            isPositionReachable(Rectangle(savedPosition.x, savedPosition.y, width, height), connectedScreenBounds())
        ) {
            setLocation(savedPosition.x, savedPosition.y)
        } else {
            setLocationRelativeTo(owner)
        }
    }

    private fun persistBounds() {
        settingsStore.dispatch(
            SettingsIntent.ToggleSetting {
                it.copy(
                    ocrWorkspaceSize = Size(width, height),
                    ocrWorkspacePosition = Position(x, y),
                )
            }
        )
    }
}
